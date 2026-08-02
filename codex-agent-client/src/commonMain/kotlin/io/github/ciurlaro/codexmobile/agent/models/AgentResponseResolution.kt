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


internal suspend fun CodexAgentClient.resolveApprovalAction(requestId: String, decision: AgentApprovalDecision) {
    stateLock.withLock { pendingBuiltInApprovals.remove(requestId) }?.let { pending ->
        if (decision == AgentApprovalDecision.ACCEPT) {
            pending.permit = true
            executeBuiltInTool(pending)
        } else {
            respondBuiltInResult(
                pending.wireId,
                BuiltInToolResult.text("The user declined this built-in tool mutation.", false),
            )
        }
        return
    }
    val pending = stateLock.withLock { pendingApprovalRequests.remove(requestId) }
        ?: error("Approval request is no longer pending")
    val wireDecision = JsonPrimitive(decision.name.lowercase())
    when (pending.type) {
        ApprovalType.COMMAND -> connection.respond(
            pending.wireId,
            AppServerServerMethods.ItemCommandExecutionRequestApproval,
            CommandExecutionRequestApprovalResponse(wireDecision),
        )
        ApprovalType.FILE_CHANGE -> connection.respond(
            pending.wireId,
            AppServerServerMethods.ItemFileChangeRequestApproval,
            FileChangeRequestApprovalResponse(wireDecision),
        )
    }
}

internal suspend fun CodexAgentClient.resolveElicitationAction(
    requestId: String,
    response: AgentElicitationResponse,
) {
    val pending = stateLock.withLock { pendingElicitationRequests.remove(requestId) }
        ?: error("Elicitation request is no longer pending")
    when (pending) {
        is PendingElicitation.Mcp -> connection.respond(
            pending.wireId,
            AppServerServerMethods.McpServerElicitationRequest,
            elicitationResponse(response),
        )
        is PendingElicitation.UserInput -> connection.respond(
            pending.wireId,
            AppServerServerMethods.ItemToolRequestUserInput,
            ToolRequestUserInputResponse(
                answers = if (response.action == AgentElicitationAction.ACCEPT) {
                    response.content.mapValues { (_, value) ->
                        ToolRequestUserInputAnswer(
                            when (value) {
                                is AgentFormValue.Text -> listOf(value.value)
                                is AgentFormValue.Number -> listOf(value.value.toString())
                                is AgentFormValue.BooleanValue -> listOf(value.value.toString())
                                is AgentFormValue.TextList -> value.value
                            },
                        )
                    }
                } else {
                    emptyMap()
                },
            ),
        )
    }
}

internal fun CodexAgentClient.closeAction() {
    val closeNow = runBlocking {
        stateLock.withLock {
            if (closed) {
                false
            } else {
                closed = true
                pendingApprovalRequests.clear()
                pendingBuiltInApprovals.clear()
                pendingElicitationRequests.clear()
                workItems.clear()
                userShellItems.clear()
                commentaryItems.clear()
                knownSkillPaths.clear()
                openedSessions.clear()
                sessionRuntimeSettings.clear()
                true
            }
        }
    }
    if (!closeNow) return
    runBlocking { connection.shutdown() }
    scope.cancel()
    eventsChannel.close()
}
