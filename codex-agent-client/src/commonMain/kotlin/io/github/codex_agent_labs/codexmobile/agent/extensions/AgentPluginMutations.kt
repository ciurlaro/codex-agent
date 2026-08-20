package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.appserver.client.AppServerConnection
import io.github.codex_agent_labs.codexmobile.appserver.client.AppServerEvent
import io.github.codex_agent_labs.codexmobile.appserver.client.AppServerRpcException
import io.github.codex_agent_labs.codexmobile.appserver.client.AppServerTimeoutException
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.*
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntimeFactory
import io.github.codex_agent_labs.codexmobile.agent.AgentCatalogFreshness
import io.github.codex_agent_labs.codexmobile.agent.AgentCapability
import io.github.codex_agent_labs.codexmobile.agent.AgentConnector
import io.github.codex_agent_labs.codexmobile.agent.AgentCollaborationMode
import io.github.codex_agent_labs.codexmobile.agent.AgentConversation
import io.github.codex_agent_labs.codexmobile.agent.AgentConversationSummary
import io.github.codex_agent_labs.codexmobile.agent.AgentElicitationAction
import io.github.codex_agent_labs.codexmobile.agent.AgentElicitationResponse
import io.github.codex_agent_labs.codexmobile.agent.AgentEvent
import io.github.codex_agent_labs.codexmobile.agent.AgentFormValue
import io.github.codex_agent_labs.codexmobile.agent.AgentInvocation
import io.github.codex_agent_labs.codexmobile.agent.AgentHook
import io.github.codex_agent_labs.codexmobile.agent.AgentHookActivity
import io.github.codex_agent_labs.codexmobile.agent.AgentHookCatalog
import io.github.codex_agent_labs.codexmobile.agent.AgentHookRunStatus
import io.github.codex_agent_labs.codexmobile.agent.AgentHookTrustStatus
import io.github.codex_agent_labs.codexmobile.agent.AgentMcpServer
import io.github.codex_agent_labs.codexmobile.agent.AgentMessage
import io.github.codex_agent_labs.codexmobile.agent.AgentMessageRole
import io.github.codex_agent_labs.codexmobile.agent.AgentModel
import io.github.codex_agent_labs.codexmobile.agent.AgentApprovalDecision
import io.github.codex_agent_labs.codexmobile.agent.AgentApprovalPreset
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginAuthPolicy
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginCatalog
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginDetail
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginInstallResult
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginReference
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginUnavailableException
import io.github.codex_agent_labs.codexmobile.agent.AgentPlanProgress
import io.github.codex_agent_labs.codexmobile.agent.AgentPlanStep
import io.github.codex_agent_labs.codexmobile.agent.AgentPlanStepStatus
import io.github.codex_agent_labs.codexmobile.agent.AgentConversationSettings
import io.github.codex_agent_labs.codexmobile.agent.AgentServiceTier
import io.github.codex_agent_labs.codexmobile.agent.AgentSkillCatalog
import io.github.codex_agent_labs.codexmobile.agent.AgentSkillChunk
import io.github.codex_agent_labs.codexmobile.agent.AgentTurnRequest
import io.github.codex_agent_labs.codexmobile.agent.AgentWorkActivity
import io.github.codex_agent_labs.codexmobile.agent.ConversationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.KSerializer


internal suspend fun CodexAgentClient.readPluginAction(plugin: AgentPluginReference): AgentPluginDetail {
    return try {
        parsePluginDetail(
            pluginRequest(AppServerClientMethods.PluginRead, pluginReadParams(plugin)).plugin,
        )
    } catch (error: AppServerRpcException) {
        throw error.forPlugin(plugin)
    }
}

internal suspend fun CodexAgentClient.installPluginAction(plugin: AgentPluginReference): AgentPluginInstallResult {
    val result = try {
        pluginRequest(
            AppServerClientMethods.PluginInstall,
            pluginInstallParams(plugin),
            retryOnTimeout = true,
        )
    } catch (error: AppServerRpcException) {
        throw error.forPlugin(plugin)
    }
    clearPluginCache()
    eventsChannel.send(AgentEvent.PluginsChanged)
    return AgentPluginInstallResult(
        authPolicy = enumValueOf(result.authPolicy.name),
        connectorsNeedingAuthentication = result.appsNeedingAuth.map(::parseConnector),
    )
}

internal suspend fun CodexAgentClient.uninstallPluginAction(plugin: AgentPluginReference) {
    require(plugin.id.isNotBlank()) { "Plugin ID must not be blank" }
    pluginRequest(
        AppServerClientMethods.PluginUninstall,
        pluginUninstallParams(plugin),
    )
    clearPluginCache()
    eventsChannel.send(AgentEvent.PluginsChanged)
}

internal suspend fun CodexAgentClient.setPluginEnabledAction(pluginId: String, isEnabled: Boolean) {
    require(pluginId.isNotBlank() && '.' !in pluginId) { "Invalid plugin ID" }
    if (builtInPluginEnabled.containsKey(pluginId)) {
        builtInToolGate.withLock {
            pluginRequest(
                AppServerClientMethods.ConfigValueWrite,
                pluginEnablementParams(pluginId, isEnabled),
                retryOnTimeout = true,
            )
            builtInPluginEnabled[pluginId] = isEnabled
        }
    } else {
        pluginRequest(
            AppServerClientMethods.ConfigValueWrite,
            pluginEnablementParams(pluginId, isEnabled),
            retryOnTimeout = true,
        )
    }
    clearPluginCache()
}

internal suspend fun CodexAgentClient.listConnectorsAction(
    conversationId: ConversationId?,
    forceReload: Boolean,
): List<AgentConnector> = requestAllPages(
    AppServerClientMethods.AppList,
    params = { cursor -> AppsListParams(cursor, forceReload, threadId = conversationId?.value) },
    data = AppsListResponse::data,
    nextCursor = AppsListResponse::nextCursor,
    transform = ::parseConnector,
)

internal suspend fun CodexAgentClient.listMcpServersAction(): List<AgentMcpServer> =
    requestAllPages(
        AppServerClientMethods.McpServerStatusList,
        params = { ListMcpServerStatusParams(cursor = it) },
        data = ListMcpServerStatusResponse::data,
        nextCursor = ListMcpServerStatusResponse::nextCursor,
        transform = ::parseMcpServer,
    )
        .filterNot { it.name == INTERNAL_APPS_MCP_SERVER }
