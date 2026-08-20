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


internal fun CodexAgentClient.shellTranscriptMessagesAction(transcript: ShellTranscript): List<AgentMessage> = listOf(
    AgentMessage(
        id = "shell-user-${transcript.itemId}",
        clientMessageId = null,
        role = AgentMessageRole.USER,
        text = "!${transcript.command}",
        shellCommand = transcript.command,
    ),
    AgentMessage(
        id = transcript.itemId,
        clientMessageId = null,
        role = AgentMessageRole.ASSISTANT,
        text = transcript.output,
        shellCommand = transcript.command,
        exitCode = transcript.exitCode,
    ),
)

internal fun CodexAgentClient.pluginReadParamsAction(plugin: AgentPluginReference) = PluginReadParams(
    pluginName = plugin.appServerPluginName(),
    marketplacePath = plugin.marketplacePath,
    remoteMarketplaceName = plugin.marketplaceName.takeIf { plugin.marketplacePath == null },
)

internal fun CodexAgentClient.pluginInstallParamsAction(plugin: AgentPluginReference) = PluginInstallParams(
    pluginName = plugin.appServerPluginName(),
    marketplacePath = plugin.marketplacePath,
    remoteMarketplaceName = plugin.marketplaceName.takeIf { plugin.marketplacePath == null },
)

internal fun CodexAgentClient.pluginUninstallParamsAction(plugin: AgentPluginReference) = PluginUninstallParams(
    pluginId = if (plugin.marketplacePath == null) plugin.appServerPluginName() else plugin.id,
)

internal fun CodexAgentClient.pluginEnablementParamsAction(pluginId: String, enabled: Boolean) = ConfigValueWriteParams(
    keyPath = "plugins.$pluginId.enabled",
    value = JsonPrimitive(enabled),
    mergeStrategy = MergeStrategy.UPSERT,
)

internal fun CodexAgentClient.approvalsReviewerAction(preset: AgentApprovalPreset) = when (preset) {
    AgentApprovalPreset.AUTO_REVIEW -> ApprovalsReviewer.AUTO_REVIEW
    else -> ApprovalsReviewer.USER
}

internal fun AgentApprovalPreset.wireApprovalPolicy(): String = when (this) {
    AgentApprovalPreset.NEVER -> "never"
    AgentApprovalPreset.AUTO_REVIEW,
    AgentApprovalPreset.ASK_ME,
    -> "on-request"
    AgentApprovalPreset.STRICT -> "untrusted"
}

internal fun CodexAgentClient.elicitationResponseAction(response: AgentElicitationResponse): McpServerElicitationRequestResponse {
    val content = if (response.action == AgentElicitationAction.ACCEPT) {
        buildJsonObject {
            response.content.forEach { (name, value) ->
                put(
                    name,
                    when (value) {
                        is AgentFormValue.Text -> JsonPrimitive(value.value)
                        is AgentFormValue.Number -> JsonPrimitive(value.value)
                        is AgentFormValue.BooleanValue -> JsonPrimitive(value.value)
                        is AgentFormValue.TextList -> JsonArray(value.value.map(::JsonPrimitive))
                    },
                )
            }
        }
    } else {
        null
    }
    return McpServerElicitationRequestResponse(
        action = when (response.action) {
            AgentElicitationAction.ACCEPT -> McpServerElicitationAction.ACCEPT
            AgentElicitationAction.DECLINE -> McpServerElicitationAction.DECLINE
            AgentElicitationAction.CANCEL -> McpServerElicitationAction.CANCEL
        },
        content = content,
    )
}
