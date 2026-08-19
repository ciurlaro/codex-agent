package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory
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
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentPlanProgress
import io.github.ciurlaro.codexmobile.agent.AgentPlanStep
import io.github.ciurlaro.codexmobile.agent.AgentPlanStepStatus
import io.github.ciurlaro.codexmobile.agent.AgentConversationSettings
import io.github.ciurlaro.codexmobile.agent.AgentServiceTier
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkillChunk
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.ConversationId
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


internal suspend fun CodexAgentClient.handleBuiltInToolCallAction(id: JsonElement, params: DynamicToolCallParams) {
    val pending = runCatching {
        checkNotNull(toolProvider) { "Built-in tools are unavailable" }
        check(params.namespace == null) { "Built-in tools do not use namespaces" }
        val tool = params.tool
        val definition = builtInToolsByName[tool] ?: error("Unknown built-in tool")
        val pluginId = definition.pluginId
        val conversationId = ConversationId(params.threadId)
        val runtimeSettings = stateLock.withLock {
            check(conversationId in openedConversations) { "Tool call conversation is not open" }
            conversationRuntimeSettings[conversationId]
                ?: error("Tool call conversation settings are unavailable")
        }
        val workspace = runtimeSettings.workspace
            ?: error("A selected workspace is required")
        val arguments = params.arguments as? JsonObject
            ?: error("Tool arguments must be an object")
        val call = BuiltInToolCall(
            conversationId = conversationId,
            turnId = params.turnId,
            callId = params.callId,
            pluginId = pluginId,
            tool = tool,
            arguments = arguments,
            workspacePath = workspace,
            argumentsHash = sha256(canonicalJson(arguments)),
            deadlineEpochMillis = currentEpochMillis() + BUILT_IN_TOOL_DEADLINE_MILLIS,
        )
        PendingBuiltInApproval(
            wireId = id,
            call = call,
            requiresPermit = definition.isMutation &&
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
    val runtimeSettings = stateLock.withLock {
        conversationRuntimeSettings[pending.call.conversationId]
            ?.takeIf { pending.call.conversationId in openedConversations }
    } ?: return respondBuiltInResult(
        pending.wireId,
        BuiltInToolResult.text("Tool call conversation settings are unavailable", false),
    )
    if (builtInToolsByName[pending.call.tool]?.isMutation == true) {
        when (typedMutationAuthority(runtimeSettings.approvalPreset)) {
            TypedMutationAuthority.USER_APPROVAL -> {
                val call = pending.call
                val requestId = "builtin:${call.conversationId.value}:${call.turnId}:${call.callId}"
                val inserted = stateLock.withLock {
                    if (call.conversationId !in openedConversations) {
                        null
                    } else {
                        pendingBuiltInApprovals.putIfMissing(requestId, pending) == null
                    }
                }
                if (inserted == null) {
                    respondBuiltInResult(
                        pending.wireId,
                        BuiltInToolResult.text("Tool call conversation is closed", false),
                    )
                    return
                }
                if (!inserted) {
                    respondBuiltInResult(
                        pending.wireId,
                        BuiltInToolResult.text("Duplicate approval request", false),
                    )
                    return
                }
                eventsChannel.send(
                    AgentEvent.ApprovalRequested(
                        conversationId = call.conversationId,
                        requestId = requestId,
                        title = "Approve ${call.tool.replace('_', ' ')}?".safeApprovalText(),
                        details = "Plugin: ${call.pluginId}\nWorkspace: ${call.workspacePath}".safeApprovalText(),
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
            val definition = checkNotNull(builtInToolsByName[pending.call.tool])
            val result = checkNotNull(toolProvider).execute(
                pending.call,
                CodexToolExecutionContext(
                    checkActiveAction = { validateBuiltInCall(pending) },
                    beforeMutationAction = {
                        validateBuiltInCall(pending)
                        check(definition.isMutation) { "Only mutation tools may dispatch mutations" }
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
                ),
            )
            check(!definition.isMutation || pending.dispatch) {
                "Mutation tools must call context.beforeMutation() before dispatch"
            }
            result
        }
    }.getOrElse { error -> BuiltInToolResult.text(error.visibleMessage(), false) }
    runCatching { respondBuiltInResult(pending.wireId, result) }
}

internal suspend fun CodexAgentClient.validateBuiltInCallAction(pending: PendingBuiltInApproval) {
    val call = pending.call
    val definition = builtInToolsByName[call.tool] ?: error("Unknown built-in tool")
    check(!definition.requiresEnabledPlugin || builtInPluginEnabled[call.pluginId] == true) {
        "${call.pluginId} is disabled"
    }
    check(currentEpochMillis() <= call.deadlineEpochMillis) {
        "Built-in tool call deadline expired"
    }
    val conversationId = call.conversationId
    val active = turnStateLock.withLock {
        (activeTurns[conversationId] == call.turnId || conversationId in startingTurns) &&
            cancelledTurns[conversationId] != call.turnId
    }
    check(active) { "Built-in tool call is no longer active" }
}

internal suspend fun CodexAgentClient.cancelPendingBuiltInToolsAction(
    conversationId: ConversationId,
    turnId: String?,
    message: String,
) {
    val cancelled = stateLock.withLock {
        pendingBuiltInApprovals.entries
        .filter { (_, pending) ->
            pending.call.conversationId == conversationId &&
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
