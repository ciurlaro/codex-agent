package io.github.ciurlaro.codexmobile.agent

import kotlinx.coroutines.flow.Flow

interface AgentClient : AutoCloseable {
    val events: Flow<AgentEvent>

    suspend fun authenticate()

    suspend fun cancelAuthentication()

    suspend fun signOut()

    suspend fun listModels(): List<AgentModel>

    suspend fun listSkills(workingDirectory: String, forceReload: Boolean = false): AgentSkillCatalog

    suspend fun readSkill(path: String, offset: Long = 0): AgentSkillChunk

    suspend fun setSkillEnabled(path: String, enabled: Boolean)

    suspend fun listInstalledPlugins(
        workingDirectory: String?,
        forceRefresh: Boolean = false,
    ): AgentPluginCatalog

    suspend fun listAvailablePlugins(
        workingDirectory: String?,
        forceRefresh: Boolean = false,
    ): AgentPluginCatalog

    suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail

    suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult

    suspend fun uninstallPlugin(plugin: AgentPluginReference): AgentPluginRemovalResult

    suspend fun setPluginEnabled(pluginId: String, enabled: Boolean)

    suspend fun listConnectors(sessionId: SessionId? = null, forceReload: Boolean = false): List<AgentConnector>

    suspend fun listMcpServers(): List<AgentMcpServer>

    suspend fun listHooks(workingDirectory: String): AgentHookCatalog

    suspend fun setHookEnabled(key: String, enabled: Boolean)

    suspend fun trustHook(key: String, currentHash: String)

    suspend fun startMcpOauth(serverName: String, sessionId: SessionId? = null): String

    suspend fun listSessions(): List<AgentConversationSummary>

    suspend fun readSession(sessionId: SessionId): AgentConversation

    suspend fun renameSession(sessionId: SessionId, name: String)

    suspend fun deleteSession(sessionId: SessionId)

    suspend fun openSession(
        previous: SessionId? = null,
        settings: AgentRuntimeSettings = AgentRuntimeSettings(),
    ): SessionId

    suspend fun sendTurn(sessionId: SessionId, request: AgentTurnRequest)

    suspend fun runShellCommand(sessionId: SessionId, command: String)

    suspend fun cancelTurn(sessionId: SessionId)

    suspend fun resolveApproval(requestId: String, decision: AgentApprovalDecision)

    suspend fun resolveElicitation(requestId: String, response: AgentElicitationResponse)
}
