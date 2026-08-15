package io.github.ciurlaro.codexmobile.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AgentMcpAuthorizationStatus {
    IDLE,
    STARTING,
    AWAITING_COMPLETION,
    AUTHENTICATED,
    FAILED,
    CLOSED,
}

data class AgentMcpAuthorizationState(
    val status: AgentMcpAuthorizationStatus = AgentMcpAuthorizationStatus.IDLE,
    val serverName: String? = null,
    val sessionId: SessionId? = null,
    val authorizationUrl: CodexAuthorizationUrl? = null,
    val browserPresented: Boolean = false,
    val error: String? = null,
)

class AgentMcpAuthorizationSession(
    private val client: AgentClient,
    scope: CoroutineScope,
    private val browser: CodexAuthorizationBrowser,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(AgentMcpAuthorizationState())
    private var presentation: CodexAuthorizationPresentation? = null
    private var generation = 0L

    val state: StateFlow<AgentMcpAuthorizationState> = mutableState

    private val observation: Job = scope.launch {
        client.events.collect(::process)
    }

    suspend fun start(serverName: String, sessionId: SessionId? = null): CodexAuthorizationUrl {
        val operation = lock.withLock {
            val current = mutableState.value
            check(current.status !in ACTIVE_STATUSES) { "Another MCP authorization is already active" }
            check(current.status != AgentMcpAuthorizationStatus.CLOSED) { "MCP authorization session is closed" }
            generation += 1
            mutableState.value = AgentMcpAuthorizationState(
                status = AgentMcpAuthorizationStatus.STARTING,
                serverName = serverName,
                sessionId = sessionId,
            )
            generation
        }
        val url = try {
            CodexAuthorizationUrl.external(client.startMcpOauth(serverName, sessionId))
        } catch (error: Throwable) {
            fail(operation, error)
            throw error
        }
        val opened = try {
            browser.open(url)
        } catch (error: Throwable) {
            fail(operation, error)
            throw error
        }
        val shouldClose = lock.withLock {
            val current = mutableState.value
            if (generation == operation && current.status == AgentMcpAuthorizationStatus.STARTING) {
                presentation = opened
                mutableState.value = current.copy(
                    status = AgentMcpAuthorizationStatus.AWAITING_COMPLETION,
                    authorizationUrl = url,
                    browserPresented = true,
                )
                false
            } else {
                true
            }
        }
        if (shouldClose) opened.close()
        return url
    }

    suspend fun dismissBrowser() {
        val owned = lock.withLock {
            val result = presentation
            presentation = null
            if (result != null) {
                mutableState.value = mutableState.value.copy(browserPresented = false)
            }
            result
        }
        owned?.close()
    }

    suspend fun close() {
        val owned = lock.withLock {
            if (mutableState.value.status == AgentMcpAuthorizationStatus.CLOSED) return
            generation += 1
            observation.cancel()
            val result = presentation
            presentation = null
            mutableState.value = AgentMcpAuthorizationState(status = AgentMcpAuthorizationStatus.CLOSED)
            result
        }
        owned?.close()
    }

    private suspend fun process(event: AgentEvent) {
        when (event) {
            is AgentEvent.McpOauthCompleted -> {
                val owned = lock.withLock {
                    val current = mutableState.value
                    if (current.status !in ACTIVE_STATUSES || current.serverName != event.serverName) return
                    val result = presentation
                    presentation = null
                    mutableState.value = current.copy(
                        status = if (event.success) {
                            AgentMcpAuthorizationStatus.AUTHENTICATED
                        } else {
                            AgentMcpAuthorizationStatus.FAILED
                        },
                        browserPresented = false,
                        error = if (event.success) null else event.error ?: "MCP authorization failed",
                    )
                    result
                }
                owned?.close()
            }
            is AgentEvent.Failure -> {
                val owned = lock.withLock {
                    val current = mutableState.value
                    if (current.status !in ACTIVE_STATUSES ||
                        event.sessionId != null && event.sessionId != current.sessionId
                    ) return
                    val result = presentation
                    presentation = null
                    mutableState.value = current.copy(
                        status = AgentMcpAuthorizationStatus.FAILED,
                        browserPresented = false,
                        error = event.message,
                    )
                    result
                }
                owned?.close()
            }
            else -> Unit
        }
    }

    private suspend fun fail(operation: Long, error: Throwable) {
        if (error is CancellationException) throw error
        val owned = lock.withLock {
            val current = mutableState.value
            if (generation != operation || current.status !in ACTIVE_STATUSES) return
            val result = presentation
            presentation = null
            mutableState.value = current.copy(
                status = AgentMcpAuthorizationStatus.FAILED,
                browserPresented = false,
                error = error.message ?: "MCP authorization failed",
            )
            result
        }
        owned?.close()
    }

    private companion object {
        val ACTIVE_STATUSES = setOf(
            AgentMcpAuthorizationStatus.STARTING,
            AgentMcpAuthorizationStatus.AWAITING_COMPLETION,
        )
    }
}
