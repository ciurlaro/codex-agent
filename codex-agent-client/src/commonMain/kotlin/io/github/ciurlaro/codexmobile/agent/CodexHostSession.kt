package io.github.ciurlaro.codexmobile.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class CodexHostStatus {
    NEW,
    RESTORING,
    WORKSPACE_REQUIRED,
    PREPARING,
    READY,
    FAILED,
    CLOSED,
}

data class CodexHostState(
    val status: CodexHostStatus = CodexHostStatus.NEW,
    val workspace: CodexWorkspace? = null,
    val workspaceRequirement: CodexWorkspaceResolution.SelectionRequired? = null,
    val client: CodexAgentClient? = null,
    val authentication: AgentAuthenticationSession? = null,
    val interactions: AgentInteractionSession? = null,
    val mcpAuthorization: AgentMcpAuthorizationSession? = null,
    val conversation: AgentConversationSession? = null,
    val error: String? = null,
)

class CodexHostSession(
    private val platform: CodexPlatformSupport,
    parentScope: CoroutineScope,
    private val clientVersion: String,
    private val requestTimeoutMillis: Long = 20_000,
) {
    private val lock = Mutex()
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )
    private val mutableState = MutableStateFlow(CodexHostState())
    private var graph: OwnedHostGraph? = null

    val state: StateFlow<CodexHostState> = mutableState

    init {
        require(clientVersion.isNotBlank()) { "Client version must not be blank" }
        require(requestTimeoutMillis > 0) { "Request timeout must be positive" }
    }

    suspend fun start() = lock.withLock {
        check(mutableState.value.status in STARTABLE_STATUSES) { "Host session has already been started" }
        closeGraph()
        restoreWorkspace()
    }

    suspend fun selectWorkspace(selection: CodexWorkspaceSelection) = lock.withLock {
        check(mutableState.value.status != CodexHostStatus.CLOSED) { "Host session is closed" }
        closeGraph()
        mutableState.value = CodexHostState(status = CodexHostStatus.RESTORING)
        val resolution = try {
            platform.workspaces.select(selection)
        } catch (error: Throwable) {
            fail(null, error)
            return@withLock
        }
        resolveWorkspace(resolution)
    }

    suspend fun retry() = lock.withLock {
        val current = mutableState.value
        check(current.status == CodexHostStatus.FAILED) { "Host session has not failed" }
        closeGraph()
        current.workspace?.let { prepare(it) } ?: restoreWorkspace()
    }

    suspend fun openConversation(
        previous: SessionId? = null,
        settings: AgentRuntimeSettings = AgentRuntimeSettings(),
    ): AgentConversationSession = lock.withLock {
        val owned = graph
        check(mutableState.value.status == CodexHostStatus.READY && owned != null) { "Host session is not ready" }
        owned.conversation?.close()
        val conversation = AgentConversationSession(owned.client, owned.interactions, scope)
        owned.conversation = conversation
        mutableState.value = readyState(owned, conversation)
        try {
            conversation.open(
                previous,
                settings.copy(
                    workingDirectory = settings.workingDirectory ?: owned.prepared.workspacePath,
                ),
            )
        } catch (error: Throwable) {
            conversation.close()
            owned.conversation = null
            mutableState.value = readyState(owned, error = error.message ?: "Could not open conversation")
            throw error
        }
        conversation
    }

    suspend fun closeConversation() = lock.withLock {
        val owned = graph ?: return@withLock
        owned.conversation?.close()
        owned.conversation = null
        if (mutableState.value.status == CodexHostStatus.READY) {
            mutableState.value = readyState(owned)
        }
    }

    suspend fun close() = lock.withLock {
        if (mutableState.value.status == CodexHostStatus.CLOSED) return@withLock
        closeGraph()
        scope.cancel()
        mutableState.value = CodexHostState(status = CodexHostStatus.CLOSED)
    }

    private suspend fun restoreWorkspace() {
        mutableState.value = CodexHostState(status = CodexHostStatus.RESTORING)
        val resolution = try {
            platform.workspaces.restore()
        } catch (error: Throwable) {
            fail(null, error)
            return
        }
        resolveWorkspace(resolution)
    }

    private suspend fun resolveWorkspace(resolution: CodexWorkspaceResolution) {
        when (resolution) {
            is CodexWorkspaceResolution.Available -> prepare(resolution.workspace)
            is CodexWorkspaceResolution.SelectionRequired -> {
                mutableState.value = CodexHostState(
                    status = CodexHostStatus.WORKSPACE_REQUIRED,
                    workspaceRequirement = resolution,
                )
            }
        }
    }

    private suspend fun prepare(workspace: CodexWorkspace) {
        mutableState.value = CodexHostState(
            status = CodexHostStatus.PREPARING,
            workspace = workspace,
        )
        val prepared = try {
            platform.prepare(workspace)
        } catch (error: Throwable) {
            fail(workspace, error)
            return
        }
        val client = prepared.createClient(clientVersion, requestTimeoutMillis)
        val authentication = AgentAuthenticationSession(client, scope, platform.browser)
        val interactions = AgentInteractionSession(client, scope, platform.browser)
        val mcpAuthorization = AgentMcpAuthorizationSession(client, scope, platform.browser)
        val owned = OwnedHostGraph(
            workspace,
            prepared,
            client,
            authentication,
            interactions,
            mcpAuthorization,
        )
        graph = owned
        mutableState.value = readyState(owned)
    }

    private suspend fun closeGraph() {
        val owned = graph ?: return
        graph = null
        owned.conversation?.close()
        owned.mcpAuthorization.close()
        owned.interactions.close()
        owned.authentication.close()
        owned.client.closeSuspendingAction()
    }

    private fun readyState(
        owned: OwnedHostGraph,
        conversation: AgentConversationSession? = owned.conversation,
        error: String? = null,
    ) = CodexHostState(
        status = CodexHostStatus.READY,
        workspace = owned.workspace,
        client = owned.client,
        authentication = owned.authentication,
        interactions = owned.interactions,
        mcpAuthorization = owned.mcpAuthorization,
        conversation = conversation,
        error = error,
    )

    private fun fail(workspace: CodexWorkspace?, error: Throwable) {
        mutableState.value = CodexHostState(
            status = CodexHostStatus.FAILED,
            workspace = workspace,
            error = error.message ?: "Could not prepare Codex",
        )
    }

    private class OwnedHostGraph(
        val workspace: CodexWorkspace,
        val prepared: CodexPreparedRuntime,
        val client: CodexAgentClient,
        val authentication: AgentAuthenticationSession,
        val interactions: AgentInteractionSession,
        val mcpAuthorization: AgentMcpAuthorizationSession,
        var conversation: AgentConversationSession? = null,
    )

    private companion object {
        val STARTABLE_STATUSES = setOf(
            CodexHostStatus.NEW,
            CodexHostStatus.WORKSPACE_REQUIRED,
            CodexHostStatus.FAILED,
        )
    }
}
