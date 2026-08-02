package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory
import io.github.ciurlaro.codexmobile.agent.AgentClient
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentConversation
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentElicitationAction
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentFormValue
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentHookActivity
import io.github.ciurlaro.codexmobile.agent.AgentHookCatalog
import io.github.ciurlaro.codexmobile.agent.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.agent.AgentHookTrustStatus
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentMessage
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.agent.AgentPluginDetail
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentPlanProgress
import io.github.ciurlaro.codexmobile.agent.AgentPlanStep
import io.github.ciurlaro.codexmobile.agent.AgentPlanStepStatus
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentServiceTier
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkillChunk
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.SessionId
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
import kotlinx.coroutines.runBlocking
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

internal suspend fun CodexAgentClient.uninstallPluginAction(plugin: AgentPluginReference): AgentPluginRemovalResult {
    require(plugin.id.isNotBlank()) { "Plugin ID must not be blank" }
    pluginRequest(
        AppServerClientMethods.PluginUninstall,
        pluginUninstallParams(plugin),
    )
    clearPluginCache()
    eventsChannel.send(AgentEvent.PluginsChanged)
    return AgentPluginRemovalResult(completed = true)
}

internal suspend fun CodexAgentClient.setPluginEnabledAction(pluginId: String, enabled: Boolean) {
    require(pluginId.isNotBlank() && '.' !in pluginId) { "Invalid plugin ID" }
    if (builtInPluginEnabled.containsKey(pluginId)) {
        builtInToolGate.withLock {
            pluginRequest(
                AppServerClientMethods.ConfigValueWrite,
                pluginEnablementParams(pluginId, enabled),
                retryOnTimeout = true,
            )
            builtInPluginEnabled[pluginId] = enabled
        }
    } else {
        pluginRequest(
            AppServerClientMethods.ConfigValueWrite,
            pluginEnablementParams(pluginId, enabled),
            retryOnTimeout = true,
        )
    }
    clearPluginCache()
}

internal suspend fun CodexAgentClient.listConnectorsAction(
    sessionId: SessionId?,
    forceReload: Boolean,
): List<AgentConnector> = requestAllPages(
    AppServerClientMethods.AppList,
    params = { cursor -> AppsListParams(cursor, forceReload, threadId = sessionId?.value) },
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
