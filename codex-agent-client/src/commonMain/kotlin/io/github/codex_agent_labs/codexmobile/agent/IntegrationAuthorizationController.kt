package io.github.codex_agent_labs.codexmobile.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

public enum class AgentIntegrationKind {
    CONNECTOR,
    MCP_SERVER,
}

public enum class AgentIntegrationAuthorizationStatus {
    IDLE,
    STARTING,
    AWAITING_COMPLETION,
    AUTHORIZED,
    FAILED,
}

public data class AgentIntegrationReference(
    public val kind: AgentIntegrationKind,
    public val id: String,
    public val displayName: String,
)

public data class AgentIntegrationAuthorizationState(
    public val status: AgentIntegrationAuthorizationStatus = AgentIntegrationAuthorizationStatus.IDLE,
    public val target: AgentIntegrationReference? = null,
    public val failure: CodexFailure? = null,
)

internal class IntegrationAuthorizationController(
    private val client: CodexAgentClient,
    scope: CoroutineScope,
    private val authorizationBrowser: CodexAuthorizationBrowser,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(AgentIntegrationAuthorizationState())
    private var presentation: CodexAuthorizationPresentation? = null
    private var generation = 0L
    private var activeConversationId: ConversationId? = null
    private var closed = false

    internal val state: StateFlow<AgentIntegrationAuthorizationState> = mutableState.asStateFlow()

    private val observation: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        client.events.collect(::process)
    }

    internal suspend fun authorizeConnector(
        connectorId: String,
        conversationId: ConversationId?,
    ) {
        require(connectorId.isNotBlank()) { "Connector ID must not be blank" }
        val begun = begin(
            AgentIntegrationReference(AgentIntegrationKind.CONNECTOR, connectorId, connectorId),
            conversationId,
        )
        val operation = begun.operation
        try {
            begun.previous?.close()
        } catch (error: Throwable) {
            throw fail(operation, error, "connector_authorization_failed", "Connector authorization failed")
        }
        val connector = try {
            client.listConnectors(conversationId, forceReload = true)
                .singleOrNull { it.id == connectorId }
                ?: error("Connector is unavailable: $connectorId")
        } catch (error: Throwable) {
            throw fail(operation, error, "connector_authorization_failed", "Connector authorization failed")
        }
        try {
            withContext(NonCancellable) {
                updateTarget(operation, connector.name)
                if (connector.isAccessible) complete(operation)
            }
            currentCoroutineContext().ensureActive()
            if (connector.isAccessible) return
        } catch (error: CancellationException) {
            if (!connector.isAccessible) abandon(operation)
            throw error
        }
        val url = try {
            CodexAuthorizationUrl.external(
                connector.installUrl ?: error("Connector does not provide an authorization URL"),
            )
        } catch (error: Throwable) {
            throw fail(operation, error, "connector_authorization_failed", "Connector authorization failed")
        }
        openAndAwait(operation, url)
    }

    internal suspend fun authorizeMcpServer(
        serverName: String,
        conversationId: ConversationId? = null,
    ) {
        require(serverName.isNotBlank()) { "MCP server name must not be blank" }
        val begun = begin(
            AgentIntegrationReference(AgentIntegrationKind.MCP_SERVER, serverName, serverName),
            conversationId,
        )
        val operation = begun.operation
        try {
            begun.previous?.close()
        } catch (error: Throwable) {
            throw fail(operation, error, "mcp_authorization_failed", "MCP authorization failed")
        }
        val url = try {
            CodexAuthorizationUrl.external(client.startMcpOauth(serverName, conversationId))
        } catch (error: Throwable) {
            throw fail(operation, error, "mcp_authorization_failed", "MCP authorization failed")
        }
        openAndAwait(operation, url)
    }

    internal suspend fun dismiss() {
        val owned = lock.withLock {
            check(!closed) { "Integration authorization is closed" }
            val target = mutableState.value.target
            generation += 1
            activeConversationId = null
            val result = presentation
            presentation = null
            mutableState.value = AgentIntegrationAuthorizationState()
            target to result
        }
        try {
            owned.second?.close()
        } catch (error: Throwable) {
            val code = if (owned.first?.kind == AgentIntegrationKind.CONNECTOR) {
                "connector_authorization_failed"
            } else {
                "mcp_authorization_failed"
            }
            val exception = error.asCodexOperationException(code, "Could not dismiss integration authorization")
            withContext(NonCancellable) {
                lock.withLock {
                    if (!closed) {
                        mutableState.value = AgentIntegrationAuthorizationState(
                            status = AgentIntegrationAuthorizationStatus.FAILED,
                            target = owned.first,
                            failure = exception.failure,
                        )
                    }
                }
            }
            throw exception
        }
    }

    internal suspend fun close() {
        val owned = lock.withLock {
            if (closed) return
            closed = true
            generation += 1
            observation.cancel()
            activeConversationId = null
            val result = presentation
            presentation = null
            result
        }
        observation.join()
        owned?.close()
    }

    private suspend fun begin(
        target: AgentIntegrationReference,
        conversationId: ConversationId?,
    ): BeginResult = lock.withLock {
        check(!closed) { "Integration authorization is closed" }
        check(mutableState.value.status !in ACTIVE_STATUSES) {
            "Another integration authorization is already active"
        }
        generation += 1
        activeConversationId = conversationId
        val previous = presentation
        presentation = null
        mutableState.value = AgentIntegrationAuthorizationState(
            status = AgentIntegrationAuthorizationStatus.STARTING,
            target = target,
        )
        BeginResult(generation, previous)
    }

    private suspend fun updateTarget(operation: Long, displayName: String) = lock.withLock {
        val current = mutableState.value
        if (!closed && generation == operation && current.status == AgentIntegrationAuthorizationStatus.STARTING) {
            mutableState.value = current.copy(
                target = current.target?.copy(displayName = displayName),
            )
        }
    }

    private suspend fun openAndAwait(operation: Long, url: CodexAuthorizationUrl) {
        val opened = try {
            authorizationBrowser.open(url)
        } catch (error: Throwable) {
            val kind = lock.withLock { mutableState.value.target?.kind }
            throw fail(
                operation,
                error,
                if (kind == AgentIntegrationKind.CONNECTOR) {
                    "connector_authorization_failed"
                } else {
                    "mcp_authorization_failed"
                },
                "Could not open the authorization URL",
            )
        }
        try {
            withContext(NonCancellable) {
                val shouldClose = lock.withLock {
                    val current = mutableState.value
                    if (!closed && generation == operation && current.status == AgentIntegrationAuthorizationStatus.STARTING) {
                        presentation = opened
                        mutableState.value = current.copy(
                            status = AgentIntegrationAuthorizationStatus.AWAITING_COMPLETION,
                        )
                        false
                    } else {
                        true
                    }
                }
                if (shouldClose) opened.close()
            }
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            abandon(operation)
            throw error
        } catch (error: Throwable) {
            val kind = lock.withLock { mutableState.value.target?.kind }
            throw fail(
                operation,
                error,
                if (kind == AgentIntegrationKind.CONNECTOR) {
                    "connector_authorization_failed"
                } else {
                    "mcp_authorization_failed"
                },
                "Could not retain the authorization presentation",
            )
        }
    }

    private suspend fun process(event: AgentEvent) {
        when (event) {
            AgentEvent.ConnectorsChanged -> refreshActiveConnector()
            is AgentEvent.McpOauthCompleted -> {
                val operation = lock.withLock {
                    val snapshot = mutableState.value
                    val target = snapshot.target
                    generation.takeIf {
                        snapshot.status in ACTIVE_STATUSES &&
                            target?.kind == AgentIntegrationKind.MCP_SERVER &&
                            target.id == event.serverName
                    }
                }
                if (operation != null) {
                    if (event.success) {
                        complete(operation)
                    } else {
                        fail(
                            operation,
                            CodexOperationException(
                                codexFailure(
                                    "mcp_authorization_failed",
                                    event.error,
                                    "MCP authorization failed",
                                    true,
                                ),
                            ),
                            "mcp_authorization_failed",
                            "MCP authorization failed",
                        )
                    }
                }
            }
            is AgentEvent.Failure -> {
                val operation = lock.withLock {
                    val current = mutableState.value
                    if (current.status !in ACTIVE_STATUSES ||
                        event.conversationId != null && event.conversationId != activeConversationId
                    ) {
                        null
                    } else {
                        generation
                    }
                }
                if (operation != null) {
                    fail(
                        operation,
                        CodexOperationException(
                            codexFailure(event.code, event.message, "Integration authorization failed", event.isRecoverable),
                        ),
                        event.code,
                        "Integration authorization failed",
                    )
                }
            }
            else -> Unit
        }
    }

    private suspend fun refreshActiveConnector() {
        val active = lock.withLock {
            val current = mutableState.value
            val target = current.target
            if (current.status !in ACTIVE_STATUSES || target?.kind != AgentIntegrationKind.CONNECTOR) {
                return
            }
            Triple(generation, target, activeConversationId)
        }
        val connector = try {
            client.listConnectors(active.third, forceReload = true).singleOrNull { it.id == active.second.id }
        } catch (error: Throwable) {
            fail(active.first, error, "connector_authorization_failed", "Connector authorization failed")
            return
        }
        if (connector?.isAccessible == true) complete(active.first)
    }

    private suspend fun complete(operation: Long) {
        val owned = lock.withLock {
            val current = mutableState.value
            if (closed || generation != operation || current.status !in ACTIVE_STATUSES) return
            val result = presentation
            presentation = null
            activeConversationId = null
            mutableState.value = current.copy(
                status = AgentIntegrationAuthorizationStatus.AUTHORIZED,
                failure = null,
            )
            result
        }
        runCatching { owned?.close() }
    }

    private suspend fun fail(
        operation: Long,
        error: Throwable,
        code: String,
        fallback: String,
    ): CodexOperationException {
        if (error is CancellationException) {
            abandon(operation)
            throw error
        }
        val exception = error.asCodexOperationException(code, fallback)
        withContext(NonCancellable) {
            val owned = lock.withLock {
                val current = mutableState.value
                if (closed || generation != operation || current.status !in ACTIVE_STATUSES) return@withLock null
                val result = presentation
                presentation = null
                activeConversationId = null
                mutableState.value = current.copy(
                    status = AgentIntegrationAuthorizationStatus.FAILED,
                    failure = exception.failure,
                )
                result
            }
            runCatching { owned?.close() }
        }
        return exception
    }

    private suspend fun abandon(operation: Long) {
        withContext(NonCancellable) {
            val owned = lock.withLock {
                if (closed || generation != operation) return@withLock null
                activeConversationId = null
                presentation.also {
                    presentation = null
                    mutableState.value = AgentIntegrationAuthorizationState()
                }
            }
            runCatching { owned?.close() }
        }
    }

    private companion object {
        val ACTIVE_STATUSES = setOf(
            AgentIntegrationAuthorizationStatus.STARTING,
            AgentIntegrationAuthorizationStatus.AWAITING_COMPLETION,
        )
    }

    private data class BeginResult(
        val operation: Long,
        val previous: CodexAuthorizationPresentation?,
    )
}
