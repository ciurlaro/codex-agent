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


internal suspend fun CodexAgentClient.finishTurnAction(sessionId: SessionId, turnId: String?) {
    turnStateLock.withLock {
        if (turnId == null || activeTurns[sessionId] == turnId) activeTurns.remove(sessionId)
        if (turnId != null && sessionId in startingTurns) {
            terminalDuringStart[sessionId] = turnId
        }
        cancellingTurns -= sessionId
        if (turnId == null || cancelledTurns[sessionId] == turnId) cancelledTurns -= sessionId
    }
    cancelPendingBuiltInTools(sessionId, turnId, "Built-in tool call is no longer active")
    val removedWork = stateLock.withLock {
        workItems.entries.removeAll { it.value.first == sessionId }
    }
    if (removedWork) eventsChannel.send(AgentEvent.WorkActivityChanged(sessionId, null))
}

internal suspend fun CodexAgentClient.updateItemActivityAction(
    threadId: String,
    turnId: String,
    item: ThreadItem,
    started: Boolean,
) {
    val sessionId = SessionId(threadId)
    val itemId = when (item) {
        is ThreadItemCommandExecutionThreadItem -> item.id
        is ThreadItemFileChangeThreadItem -> item.id
        else -> return
    }
    if (
        started && item is ThreadItemCommandExecutionThreadItem &&
        item.source == CommandExecutionSource.USER_SHELL
    ) {
        stateLock.withLock { userShellItems += itemId }
        turnStateLock.withLock { activeTurns[sessionId] = turnId }
    }
    val activity = when (item) {
        is ThreadItemCommandExecutionThreadItem -> AgentWorkActivity.RUNNING_COMMAND
        is ThreadItemFileChangeThreadItem -> AgentWorkActivity.WRITING_FILES
    }
    if (started) {
        stateLock.withLock { workItems[itemId] = sessionId to activity }
        eventsChannel.send(AgentEvent.WorkActivityChanged(sessionId, activity))
    } else if (!started && stateLock.withLock { workItems.remove(itemId) != null }) {
        val activity = stateLock.withLock {
            workItems.values.lastOrNull { it.first == sessionId }?.second
        }
        eventsChannel.send(
            AgentEvent.WorkActivityChanged(
                sessionId,
                activity,
            ),
        )
    }
}

internal suspend fun CodexAgentClient.completeUserShellItemAction(threadId: String, turnId: String, item: ThreadItem) {
    if (item !is ThreadItemCommandExecutionThreadItem) return
    if (stateLock.withLock { userShellItems.remove(item.id) } ||
        item.source == CommandExecutionSource.USER_SHELL
    ) {
        runCatching {
            shellTranscriptStore.upsert(
                threadId,
                ShellTranscript(
                    turnId = turnId,
                    itemId = item.id,
                    command = item.command,
                    output = item.aggregatedOutput.orEmpty().boundedShellTranscript(),
                    exitCode = item.exitCode?.toInt(),
                ),
            )
        }
        eventsChannel.send(
            AgentEvent.ShellCommandCompleted(
                sessionId = SessionId(threadId),
                exitCode = item.exitCode?.toInt(),
            ),
        )
    }
}
