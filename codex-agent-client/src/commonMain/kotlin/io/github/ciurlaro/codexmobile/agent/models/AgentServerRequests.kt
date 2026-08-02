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


internal suspend fun CodexAgentClient.handleConnectionEventAction(event: AppServerEvent) {
    when (event) {
        is AppServerEvent.Request -> handleServerRequest(event.value, event.descriptor.method)
        is AppServerEvent.Notification -> handleNotification(event.value)
        is AppServerEvent.Failure -> handleConnectionFailure(event.code, event.message)
    }
}

internal suspend fun CodexAgentClient.handleServerRequestAction(request: ServerRequest, method: String) {
    when (request) {
        is ServerRequestItemCommandExecutionRequestApprovalRequest -> handleApprovalRequest(
            request.id,
            request.params.threadId,
            request.params.reason,
            buildList {
                request.params.command?.let { add("Command: $it") }
                request.params.cwd?.let { add("Folder: $it") }
            },
            ApprovalType.COMMAND,
        )
        is ServerRequestItemFileChangeRequestApprovalRequest -> handleApprovalRequest(
            request.id,
            request.params.threadId,
            request.params.reason,
            buildList { request.params.grantRoot?.let { add("Folder: $it") } },
            ApprovalType.FILE_CHANGE,
        )
        is ServerRequestMcpServerElicitationRequestRequest ->
            handleElicitationRequest(request.id, request.params)
        is ServerRequestItemToolRequestUserInputRequest ->
            handleUserInputRequest(request.id, request.params)
        is ServerRequestItemToolCallRequest -> handleBuiltInToolCall(request.id, request.params)
        else -> {
            val wire = PROTOCOL_JSON.encodeToJsonElement(ServerRequest.serializer(), request).jsonObject
            rejectServerRequest(wire.getValue("id"), method)
        }
    }
}

internal suspend fun CodexAgentClient.handleElicitationRequestAction(
    id: JsonElement,
    params: McpServerElicitationRequestParams,
) {
    val elicitation = runCatching {
        val requestId = id.toString()
        val parsed = parseElicitation(requestId, params)
        check(parsed.sessionId in openedSessions) { "Elicitation session is not open" }
        check(pendingElicitationRequests.putIfMissing(requestId, PendingElicitation.Mcp(id)) == null) {
            "Elicitation request ID is already pending"
        }
        parsed
    }.getOrElse {
        connection.respond(
            id,
            AppServerServerMethods.McpServerElicitationRequest,
            McpServerElicitationRequestResponse(McpServerElicitationAction.DECLINE),
        )
        return
    }
    eventsChannel.send(AgentEvent.ElicitationRequested(elicitation))
}

internal suspend fun CodexAgentClient.handleUserInputRequestAction(id: JsonElement, params: ToolRequestUserInputParams) {
    val elicitation = runCatching {
        val requestId = id.toString()
        val parsed = parseUserInputRequest(requestId, params)
        check(parsed.sessionId in openedSessions) { "Plan session is not open" }
        check(
            pendingElicitationRequests.putIfMissing(
                requestId,
                PendingElicitation.UserInput(id),
            ) == null,
        ) { "Plan input request ID is already pending" }
        parsed
    }.getOrElse {
        connection.respond(
            id,
            AppServerServerMethods.ItemToolRequestUserInput,
            ToolRequestUserInputResponse(emptyMap()),
        )
        return
    }
    eventsChannel.send(AgentEvent.ElicitationRequested(elicitation))
}

internal suspend fun CodexAgentClient.handleApprovalRequestAction(
    id: JsonElement,
    threadId: String,
    reason: String?,
    detailLines: List<String>,
    type: ApprovalType,
) {
    val event = runCatching {
        val sessionId = SessionId(threadId)
        check(sessionId in openedSessions) { "Approval session is not open" }
        val requestId = id.toString()
        check(pendingApprovalRequests.putIfMissing(requestId, PendingApproval(id, type)) == null) {
            "Approval request ID is already pending"
        }
        val title = if (type == ApprovalType.FILE_CHANGE) {
            "Approve file changes?"
        } else {
            "Approve command?"
        }
        val details = buildList {
            reason?.let(::add)
            addAll(detailLines)
        }.joinToString("\n").ifBlank { "Codex requested permission to continue." }
        AgentEvent.ApprovalRequested(sessionId, requestId, title, details)
    }.getOrElse {
        respondServerError(id, -32602, "Invalid approval request")
        return
    }
    eventsChannel.send(event)
}

internal suspend fun CodexAgentClient.rejectServerRequestAction(id: JsonElement, method: String) {
    respondServerError(id, -32601, "Client method is not available: $method")
}

internal suspend fun CodexAgentClient.respondServerErrorAction(id: JsonElement, code: Int, message: String) =
    connection.respondError(id, code.toLong(), message)
