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


internal suspend fun CodexAgentClient.handleNotificationAction(notification: ServerNotification) {
    when (notification) {
        is ServerNotificationAccountLoginCompletedNotification -> {
            val params = notification.params
            val completion = LoginCompletion(
                loginId = params.loginId ?: error("Login completion ID is missing"),
                success = params.success,
                error = params.error,
            )
            val applyNow = loginStateLock.withLock {
                if (cancelledLoginIds.remove(completion.loginId)) {
                    if (loginId == completion.loginId) loginId = null
                    false
                } else if (loginStarting) {
                    loginCompletedDuringStart = completion
                    false
                } else if (loginId == completion.loginId) {
                    loginId = null
                    true
                } else {
                    false
                }
            }
            if (applyNow) applyLoginCompletion(completion)
        }

        is ServerNotificationAccountUpdatedNotification -> {
            if (notification.params.authMode?.jsonPrimitive?.contentOrNull == "chatgpt") {
                emitAuthenticated()
            }
        }

        is ServerNotificationSkillsChangedNotification ->
            eventsChannel.send(AgentEvent.SkillsChanged)

        is ServerNotificationAppListUpdatedNotification ->
            eventsChannel.send(AgentEvent.ConnectorsChanged)

        is ServerNotificationMcpServerOauthLoginCompletedNotification -> eventsChannel.send(
            AgentEvent.McpOauthCompleted(
                serverName = notification.params.name,
                success = notification.params.success,
                error = notification.params.error,
            ),
        )

        is ServerNotificationItemAgentMessageDeltaNotification -> {
            val params = notification.params
            val sessionId = SessionId(params.threadId)
            eventsChannel.send(
                AgentEvent.TextDelta(
                    sessionId = sessionId,
                    text = params.delta,
                    itemId = params.itemId,
                    isCommentary = params.itemId in commentaryItems,
                ),
            )
        }

        is ServerNotificationItemReasoningSummaryTextDeltaNotification -> {
            val params = notification.params
            eventsChannel.send(
                AgentEvent.ReasoningSummaryDelta(
                    sessionId = SessionId(params.threadId),
                    text = params.delta,
                    itemId = params.itemId,
                    summaryIndex = params.summaryIndex,
                ),
            )
        }

        is ServerNotificationItemPlanDeltaNotification -> {
            val params = notification.params
            eventsChannel.send(
                AgentEvent.PlanDelta(
                    sessionId = SessionId(params.threadId),
                    text = params.delta,
                    itemId = params.itemId,
                ),
            )
        }

        is ServerNotificationTurnPlanUpdatedNotification -> {
            val params = notification.params
            eventsChannel.send(
                AgentEvent.PlanUpdated(
                    sessionId = SessionId(params.threadId),
                    progress = AgentPlanProgress(
                        explanation = params.explanation,
                        steps = params.plan.map { step ->
                            AgentPlanStep(
                                text = step.step,
                                status = enumValueOf(step.status.name),
                            )
                        },
                    ),
                ),
            )
        }

        is ServerNotificationHookStartedNotification -> eventsChannel.send(
            AgentEvent.HookActivityChanged(
                SessionId(notification.params.threadId),
                notification.params.run.toAgentHookActivity(),
            ),
        )

        is ServerNotificationHookCompletedNotification -> eventsChannel.send(
            AgentEvent.HookActivityChanged(
                SessionId(notification.params.threadId),
                notification.params.run.toAgentHookActivity(),
            ),
        )

        is ServerNotificationItemCommandExecutionOutputDeltaNotification -> {
            val params = notification.params
            if (params.itemId in userShellItems) {
                eventsChannel.send(
                    AgentEvent.ShellOutputDelta(
                        sessionId = SessionId(params.threadId),
                        text = params.delta,
                    ),
                )
            }
        }

        is ServerNotificationItemStartedNotification -> {
            val params = notification.params
            val item = params.item
            if (
                item is ThreadItemAgentMessageThreadItem &&
                (item.phase as? JsonPrimitive)?.contentOrNull == "commentary"
            ) {
                commentaryItems += item.id
            }
            updateItemActivity(params.threadId, params.turnId, item, started = true)
        }

        is ServerNotificationItemCompletedNotification -> {
            val params = notification.params
            completeUserShellItem(params.threadId, params.turnId, params.item)
            updateItemActivity(params.threadId, params.turnId, params.item, started = false)
            (params.item as? ThreadItemAgentMessageThreadItem)?.let { commentaryItems -= it.id }
        }

        is ServerNotificationTurnCompletedNotification -> {
            val params = notification.params
            val sessionId = SessionId(params.threadId)
            finishTurn(sessionId, params.turn.id)
            if (params.turn.status == TurnStatus.FAILED) {
                val detail = params.turn.error?.message ?: "Turn failed"
                eventsChannel.send(AgentEvent.Failure(sessionId, "turn_failed", detail, true))
            } else {
                eventsChannel.send(AgentEvent.TurnCompleted(sessionId))
            }
        }

        is ServerNotificationErrorNotification -> {
            val params = notification.params
            if (!params.willRetry) {
                val sessionId = SessionId(params.threadId)
                finishTurn(sessionId, params.turnId)
                eventsChannel.send(
                    AgentEvent.Failure(sessionId, "turn_error", params.error.message, true),
                )
            }
        }

        else -> Unit
    }
}
