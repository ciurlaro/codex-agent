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


internal suspend fun CodexAgentClient.openSessionAction(previous: SessionId?, settings: AgentRuntimeSettings): SessionId {
    if (builtInToolDefinitions.any(BuiltInToolDefinition::requiresEnabledPlugin)) {
        connection.ensureStarted()
        refreshBuiltInPluginEnablement(settings.workingDirectory ?: "/")
    }
    val developerInstructions =
        "Answer conversationally using Markdown. The shell starts in the user's selected " +
            "workspace and may use ordinary shell commands to inspect and modify files. Use enabled " +
            "plugin tools through their advertised typed contracts. Use the " +
            "built-in web search tool only when the user input contains the structured " +
            "'${AgentCapability.WEB_SEARCH.promptLabel}' prompt tag."
    val config = buildJsonObject {
        put("web_search", "live")
        putJsonObject("tools") {
            putJsonObject("experimental_request_user_input") { put("enabled", true) }
        }
        putJsonObject("features") {
            put("shell_tool", true)
            put("code_mode", false)
            put("multi_agent", false)
            put("apps", true)
            put("enable_mcp_apps", true)
            put("plugins", true)
            put("image_generation", false)
            put("goals", false)
            put("hooks", true)
            put("skill_mcp_dependency_install", false)
            put("workspace_dependencies", false)
            put("standalone_web_search", false)
        }
        putJsonObject("shell_environment_policy") {
            put("inherit", "all")
            put(
                "exclude",
                buildJsonArray {
                    listOf(
                        "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY",
                        "http_proxy", "https_proxy", "all_proxy", "no_proxy",
                    ).forEach { add(JsonPrimitive(it)) }
                },
            )
        }
    }
    val opened = if (previous == null) {
        val result = connection.request(
            AppServerClientMethods.ThreadStart,
            ThreadStartParams(
                approvalPolicy = JsonPrimitive(settings.approvalPreset.approvalPolicy),
                approvalsReviewer = approvalsReviewer(settings.approvalPreset),
                config = config,
                cwd = settings.workingDirectory,
                developerInstructions = developerInstructions,
                ephemeral = false,
                sandbox = SandboxMode.DANGER_FULL_ACCESS,
                serviceTier = settings.serviceTier,
                dynamicTools = builtInToolDispatcher?.let {
                    builtInDynamicTools(
                        builtInPluginEnabled.filterValues { it }.keys,
                        builtInToolDefinitions,
                    )
                },
            ),
        )
        AgentEvent.SessionOpened(
            sessionId = SessionId(result.thread.id),
            model = result.model,
            effort = result.reasoningEffort,
            serviceTier = result.serviceTier,
        )
    } else {
        val result = connection.request(
            AppServerClientMethods.ThreadResume,
            ThreadResumeParams(
                threadId = previous.value,
                approvalPolicy = JsonPrimitive(settings.approvalPreset.approvalPolicy),
                approvalsReviewer = approvalsReviewer(settings.approvalPreset),
                config = config,
                cwd = settings.workingDirectory,
                developerInstructions = developerInstructions,
                sandbox = SandboxMode.DANGER_FULL_ACCESS,
                serviceTier = settings.serviceTier,
            ),
        )
        AgentEvent.SessionOpened(
            sessionId = SessionId(result.thread.id),
            model = result.model,
            effort = null,
            serviceTier = settings.serviceTier,
        )
    }
    val sessionId = opened.sessionId
    stateLock.withLock {
        openedSessions += sessionId
        sessionRuntimeSettings[sessionId] = SessionRuntimeSettings(
            workspace = settings.workingDirectory,
            approvalPreset = settings.approvalPreset,
            model = opened.model,
            effort = opened.effort,
        )
    }
    eventsChannel.send(opened)
    return sessionId
}
