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


internal fun CodexAgentClient.handleBuiltInToolCallAction(id: JsonElement, params: DynamicToolCallParams) {
    val pending = runCatching {
        checkNotNull(builtInToolDispatcher) { "Built-in tools are unavailable" }
        check(params.namespace == null) { "Built-in tools do not use namespaces" }
        val tool = params.tool
        val definition = builtInToolsByName[tool] ?: error("Unknown built-in tool")
        val pluginId = definition.pluginId
        val sessionId = SessionId(params.threadId)
        check(sessionId in openedSessions) { "Tool call session is not open" }
        val runtimeSettings = sessionRuntimeSettings[sessionId]
            ?: error("Tool call session settings are unavailable")
        val workspace = runtimeSettings.workspace
            ?: error("A selected workspace is required")
        val arguments = params.arguments as? JsonObject
            ?: error("Tool arguments must be an object")
        val call = BuiltInToolCall(
            threadId = sessionId.value,
            turnId = params.turnId,
            callId = params.callId,
            pluginId = pluginId,
            tool = tool,
            arguments = arguments,
            workspace = workspace,
            argumentsHash = sha256(canonicalJson(arguments)),
            deadlineEpochMillis = currentEpochMillis() + BUILT_IN_TOOL_DEADLINE_MILLIS,
        )
        PendingBuiltInApproval(
            wireId = id,
            call = call,
            requiresPermit = definition.mutation &&
                typedMutationAuthority(runtimeSettings.approvalPreset) ==
                TypedMutationAuthority.USER_APPROVAL,
        )
    }.getOrElse { error ->
        scope.launch {
            respondBuiltInResult(id, BuiltInToolResult.text(error.visibleMessage(), false))
        }
        return
    }

    scope.launch { continueBuiltInToolCall(pending) }
}

internal suspend fun CodexAgentClient.continueBuiltInToolCallAction(pending: PendingBuiltInApproval) {
    val replay = try {
        builtInToolGate.withLock {
            validateBuiltInCall(pending)
            checkNotNull(builtInToolDispatcher).replay(pending.call)
        }
    } catch (error: Exception) {
        respondBuiltInResult(pending.wireId, BuiltInToolResult.text(error.visibleMessage(), false))
        return
    }
    if (replay != null) {
        respondBuiltInResult(pending.wireId, replay)
        return
    }

    val runtimeSettings = sessionRuntimeSettings[SessionId(pending.call.threadId)]
        ?: return respondBuiltInResult(
            pending.wireId,
            BuiltInToolResult.text("Tool call session settings are unavailable", false),
        )
    if (builtInToolsByName[pending.call.tool]?.mutation == true) {
        when (typedMutationAuthority(runtimeSettings.approvalPreset)) {
            TypedMutationAuthority.USER_APPROVAL -> {
                val call = pending.call
                val requestId = "builtin:${call.threadId}:${call.turnId}:${call.callId}"
                if (pendingBuiltInApprovals.putIfMissing(requestId, pending) != null) {
                    respondBuiltInResult(
                        pending.wireId,
                        BuiltInToolResult.text("Duplicate approval request", false),
                    )
                    return
                }
                eventsChannel.send(
                    AgentEvent.ApprovalRequested(
                        sessionId = SessionId(call.threadId),
                        requestId = requestId,
                        title = "Approve ${call.tool.replace('_', ' ')}?",
                        details = "Plugin: ${call.pluginId}\nWorkspace: ${call.workspace}",
                    ),
                )
                return
            }
            TypedMutationAuthority.DIRECT -> Unit
        }
    }
    executeBuiltInTool(pending)
}

internal suspend fun CodexAgentClient.executeBuiltInToolAction(pending: PendingBuiltInApproval) {
    val result = runCatching {
        builtInToolGate.withLock {
            validateBuiltInCall(pending)
            checkNotNull(builtInToolDispatcher).execute(
                pending.call,
                checkActive = { validateBuiltInCall(pending) },
                beforeMutationDispatch = {
                    validateBuiltInCall(pending)
                    check(!pending.dispatch) {
                        "Built-in mutation dispatch was already used"
                    }
                    pending.dispatch = true
                    if (pending.requiresPermit) {
                        check(pending.permit) {
                            "Built-in mutation approval is missing or was already used"
                        }
                        pending.permit = false
                    }
                },
            )
        }
    }.getOrElse { error -> BuiltInToolResult.text(error.visibleMessage(), false) }
    runCatching { respondBuiltInResult(pending.wireId, result) }
}

internal suspend fun CodexAgentClient.validateBuiltInCallAction(pending: PendingBuiltInApproval) {
    val call = pending.call
    check(builtInPluginEnabled[call.pluginId] == true) { "${call.pluginId} is disabled" }
    check(currentEpochMillis() <= call.deadlineEpochMillis) {
        "Built-in tool call deadline expired"
    }
    val sessionId = SessionId(call.threadId)
    val active = turnStateLock.withLock {
        (activeTurns[sessionId] == call.turnId || sessionId in startingTurns) &&
            cancelledTurns[sessionId] != call.turnId
    }
    check(active) { "Built-in tool call is no longer active" }
}

internal suspend fun CodexAgentClient.cancelPendingBuiltInToolsAction(
    sessionId: SessionId,
    turnId: String?,
    message: String,
) {
    val cancelled = stateLock.withLock {
        pendingBuiltInApprovals.entries
        .filter { (_, pending) ->
            pending.call.threadId == sessionId.value &&
                (turnId == null || pending.call.turnId == turnId)
        }
        .mapNotNull { (requestId, pending) ->
            pending.takeIf { pendingBuiltInApprovals.remove(requestId) === pending }
        }
    }
    cancelled.forEach { pending ->
        scope.launch {
            runCatching {
                respondBuiltInResult(pending.wireId, BuiltInToolResult.text(message, false))
            }
        }
    }
}

internal suspend fun CodexAgentClient.respondBuiltInResultAction(id: JsonElement, result: BuiltInToolResult) {
    connection.respond(
        id,
        AppServerServerMethods.ItemToolCall,
        DynamicToolCallResponse(
            contentItems = result.content.map { item ->
                when (item) {
                    is BuiltInToolContent.Text ->
                        DynamicToolCallOutputContentItemInputTextDynamicToolCallOutputContentItem(
                            item.value.take(MAX_BUILT_IN_RESULT_CHARS),
                        )
                    is BuiltInToolContent.Image -> {
                        check(item.dataUrl.startsWith("data:image/")) {
                            "Built-in images must use inline data URLs"
                        }
                        DynamicToolCallOutputContentItemInputImageDynamicToolCallOutputContentItem(
                            item.dataUrl,
                        )
                    }
                }
            },
            success = result.success,
        ),
    )
}
