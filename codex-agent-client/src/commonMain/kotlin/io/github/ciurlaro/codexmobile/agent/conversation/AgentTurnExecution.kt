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


internal suspend fun CodexAgentClient.sendTurnAction(sessionId: SessionId, request: AgentTurnRequest) {
    val snapshot = request.copy(
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
    require(snapshot.workingDirectory?.isAbsoluteHostPath() != false) {
        "Working directory must be absolute"
    }
    turnStateLock.withLock {
        check(sessionId !in startingTurns && !activeTurns.containsKey(sessionId)) {
            "A turn is already active for this session"
        }
        cancelledTurns -= sessionId
        startingTurns += sessionId
    }
    val previousRuntimeSettings = sessionRuntimeSettings[sessionId]
    sessionRuntimeSettings[sessionId] = SessionRuntimeSettings(
        workspace = snapshot.workingDirectory ?: previousRuntimeSettings?.workspace,
        approvalPreset = snapshot.approvalPreset,
        model = snapshot.model ?: previousRuntimeSettings?.model,
        effort = snapshot.effort ?: previousRuntimeSettings?.effort,
    )
    snapshot.clientMessageId?.takeIf { snapshot.invocations.isNotEmpty() }?.let { clientMessageId ->
        runCatching {
            turnInputMetadataStore.upsert(
                sessionId.value,
                TurnInputMetadata(clientMessageId, snapshot.invocations),
            )
        }
    }

    try {
        val result = connection.request(
            AppServerClientMethods.TurnStart,
            TurnStartParams(
                input = turnInput(snapshot),
                threadId = sessionId.value,
                approvalPolicy = JsonPrimitive(snapshot.approvalPreset.approvalPolicy),
                approvalsReviewer = approvalsReviewer(snapshot.approvalPreset),
                clientUserMessageId = snapshot.clientMessageId,
                cwd = snapshot.workingDirectory,
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
        turnStateLock.withLock {
            startingTurns -= sessionId
            if (terminalDuringStart.remove(sessionId) != turnId) {
                activeTurns[sessionId] = turnId
            }
        }
    } catch (error: Exception) {
        if (previousRuntimeSettings == null) {
            sessionRuntimeSettings -= sessionId
        } else {
            sessionRuntimeSettings[sessionId] = previousRuntimeSettings
        }
        turnStateLock.withLock {
            startingTurns -= sessionId
            terminalDuringStart.remove(sessionId)
        }
        throw error
    }
}

internal suspend fun CodexAgentClient.runShellCommandAction(sessionId: SessionId, command: String) {
    val snapshot = command.trim()
    require(snapshot.isNotEmpty()) { "Shell command must not be blank" }
    require(snapshot.length <= MAX_PROMPT_CHARS) { "Shell command is too large" }
    check(sessionId in openedSessions) { "Session is not open" }
    turnStateLock.withLock {
        check(sessionId !in startingTurns && !activeTurns.containsKey(sessionId)) {
            "A turn is already active for this session"
        }
        startingTurns += sessionId
    }
    try {
        connection.request(
            AppServerClientMethods.ThreadShellCommand,
            ThreadShellCommandParams(command = snapshot, threadId = sessionId.value),
        )
    } finally {
        turnStateLock.withLock {
            startingTurns -= sessionId
            terminalDuringStart.remove(sessionId)
        }
    }
}

internal suspend fun CodexAgentClient.cancelTurnAction(sessionId: SessionId) {
    val turnId = turnStateLock.withLock {
        val active = activeTurns[sessionId] ?: error("No active turn for this session")
        check(cancellingTurns.add(sessionId)) { "Turn cancellation is already in progress" }
        cancelledTurns[sessionId] = active
        active
    }
    cancelPendingBuiltInTools(sessionId, turnId, "Built-in tool call was cancelled")
    try {
        try {
            connection.request(
                AppServerClientMethods.TurnInterrupt,
                TurnInterruptParams(threadId = sessionId.value, turnId = turnId),
            )
        } catch (error: AppServerRpcException) {
            if (error.code != -32600L || error.detail != "no active turn to interrupt") throw error
        }
    } finally {
        turnStateLock.withLock { cancellingTurns -= sessionId }
    }
}
