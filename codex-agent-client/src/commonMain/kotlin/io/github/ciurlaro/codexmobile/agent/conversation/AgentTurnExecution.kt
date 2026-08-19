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
import kotlinx.coroutines.NonCancellable
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
import kotlinx.coroutines.withTimeoutOrNull
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
import kotlin.uuid.Uuid
import kotlin.uuid.ExperimentalUuidApi


@OptIn(ExperimentalUuidApi::class)
internal suspend fun CodexAgentClient.sendTurnAction(
    conversationId: ConversationId,
    request: AgentTurnRequest,
    workingDirectory: String,
) {
    val logicalClientMessageId = request.clientMessageId ?: Uuid.random().toString()
    require(
        request.collaborationMode == AgentCollaborationMode.PLAN ||
            !logicalClientMessageId.startsWith(PLAN_CLIENT_MESSAGE_PREFIX) &&
            !logicalClientMessageId.startsWith(LEGACY_PLAN_CLIENT_MESSAGE_PREFIX),
    ) { "Client message ID uses a reserved plan prefix" }
    val wireClientMessageId = if (request.collaborationMode == AgentCollaborationMode.PLAN) {
        PLAN_CLIENT_MESSAGE_PREFIX + logicalClientMessageId
    } else {
        logicalClientMessageId
    }
    val snapshot = request.copy(
        clientMessageId = wireClientMessageId,
        capabilities = request.capabilities.toSet(),
        invocations = request.invocations.distinctBy(AgentInvocation::key),
    )
    require(
        snapshot.prompt.isNotBlank() || snapshot.capabilities.isNotEmpty() ||
            snapshot.invocations.isNotEmpty(),
    ) {
        "Prompt must not be blank"
    }
    require(snapshot.prompt.length <= MAX_PROMPT_CHARS) { "Prompt is too large" }
    require(snapshot.clientMessageId?.isNotBlank() != false) {
        "Client message ID must not be blank"
    }
    require(snapshot.model?.isNotBlank() != false) { "Model must not be blank" }
    require(snapshot.effort?.isNotBlank() != false) { "Effort must not be blank" }
    require(snapshot.serviceTier?.isNotBlank() != false) { "Service tier must not be blank" }
    require(workingDirectory.isAbsoluteHostPath()) {
        "Working directory must be absolute"
    }
    markTurnStarting(conversationId, clearCancellation = true)
    val previousRuntimeSettings = stateLock.withLock {
        check(conversationId in openedConversations) { "Conversation is not open" }
        val previous = conversationRuntimeSettings[conversationId]
        conversationRuntimeSettings[conversationId] = ConversationRuntimeSettings(
            workspace = workingDirectory,
            approvalPreset = snapshot.approvalPreset,
            model = snapshot.model ?: previous?.model,
            effort = snapshot.effort ?: previous?.effort,
        )
        previous
    }
    snapshot.clientMessageId?.takeIf { snapshot.invocations.isNotEmpty() }?.let { clientMessageId ->
        runCatching {
            turnInputMetadataStore.upsert(
                conversationId.value,
                TurnInputMetadata(clientMessageId, snapshot.invocations),
            )
        }
    }

    try {
        val result = connection.request(
            AppServerClientMethods.TurnStart,
            TurnStartParams(
                input = turnInput(snapshot),
                threadId = conversationId.value,
                approvalPolicy = JsonPrimitive(snapshot.approvalPreset.wireApprovalPolicy()),
                approvalsReviewer = approvalsReviewer(snapshot.approvalPreset),
                clientUserMessageId = snapshot.clientMessageId,
                cwd = workingDirectory,
                effort = snapshot.effort,
                model = snapshot.model,
                collaborationMode = if (snapshot.collaborationMode == AgentCollaborationMode.PLAN) {
                    CollaborationMode(
                        mode = ModeKind.PLAN,
                        settings = Settings(
                            model = snapshot.model ?: previousRuntimeSettings?.model
                                ?: error("Active model is unavailable"),
                            developer_instructions = null,
                            reasoning_effort = "medium",
                        ),
                    )
                } else {
                    null
                },
                serviceTier = snapshot.serviceTier,
                summary = JsonPrimitive("auto"),
            ),
        )
        val turnId = result.turn.id
        var deferredTerminal: PendingTurnTerminal? = null
        turnStateLock.withLock {
            startingTurns -= conversationId
            val pending = pendingTerminalsDuringStart.remove(conversationId to turnId)
            pendingTerminalsDuringStart.keys.removeAll { it.first == conversationId }
            if (pending != null) {
                deferredTerminal = PendingTurnTerminal(turnId, pending)
            } else {
                activeTurns[conversationId] = turnId
            }
        }
        deferredTerminal?.let { publishAcceptedTerminalAction(conversationId, it) }
    } catch (error: Exception) {
        withContext(NonCancellable) {
            stateLock.withLock {
                if (conversationId in openedConversations) {
                    if (previousRuntimeSettings == null) {
                        conversationRuntimeSettings -= conversationId
                    } else {
                        conversationRuntimeSettings[conversationId] = previousRuntimeSettings
                    }
                }
            }
            turnStateLock.withLock {
                startingTurns -= conversationId
                pendingTerminalsDuringStart.keys.removeAll { it.first == conversationId }
            }
        }
        throw error
    }
}

internal suspend fun CodexAgentClient.runShellCommandAction(conversationId: ConversationId, command: String) {
    val snapshot = command.trim()
    require(snapshot.isNotEmpty()) { "Shell command must not be blank" }
    require(snapshot.length <= MAX_PROMPT_CHARS) { "Shell command is too large" }
    check(stateLock.withLock { conversationId in openedConversations }) { "Conversation is not open" }
    val startup = markShellTurnStarting(conversationId)
    try {
        connection.request(
            AppServerClientMethods.ThreadShellCommand,
            ThreadShellCommandParams(command = snapshot, threadId = conversationId.value),
        )
        check(withTimeoutOrNull(requestTimeoutMillis) { startup.await() } != null) {
            "Shell command did not start in time"
        }
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            turnStateLock.withLock {
                startingTurns -= conversationId
                shellStartupCompletions.remove(conversationId)?.cancel()
                pendingTerminalsDuringStart.keys.removeAll { it.first == conversationId }
            }
        }
        throw error
    }
}

internal suspend fun CodexAgentClient.cancelTurnAction(conversationId: ConversationId) {
    val turnId = turnStateLock.withLock {
        val active = activeTurns[conversationId] ?: error("No active turn for this conversation")
        check(cancellingTurns.add(conversationId)) { "Turn cancellation is already in progress" }
        cancelledTurns[conversationId] = active
        active
    }
    cancelPendingBuiltInTools(conversationId, turnId, "Built-in tool call was cancelled")
    try {
        try {
            connection.request(
                AppServerClientMethods.TurnInterrupt,
                TurnInterruptParams(threadId = conversationId.value, turnId = turnId),
            )
        } catch (error: AppServerRpcException) {
            if (error.code != -32600L || error.detail != "no active turn to interrupt") throw error
        }
    } finally {
        withContext(NonCancellable) {
            turnStateLock.withLock { cancellingTurns -= conversationId }
        }
    }
}
