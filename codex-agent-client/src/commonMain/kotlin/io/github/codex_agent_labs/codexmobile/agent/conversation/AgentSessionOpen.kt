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


internal suspend fun CodexAgentClient.openConversationAction(
    conversationId: ConversationId?,
    settings: AgentConversationSettings,
    workingDirectory: String,
    features: Set<CodexRuntimeFeature>,
    owner: Any,
): ConversationId {
    require(workingDirectory.isAbsoluteHostPath()) { "Working directory must be absolute" }
    if (CodexRuntimeFeature.PLUGINS in features &&
        builtInToolDefinitions.any(BuiltInToolDefinition::requiresEnabledPlugin)
    ) {
        connection.ensureStarted()
        refreshBuiltInPluginEnablement(workingDirectory)
    }
    val developerInstructions = buildList {
        add("Answer conversationally using Markdown.")
        if (CodexRuntimeFeature.SHELL_COMMANDS in features) {
            add("The shell starts in the user's selected workspace and may use ordinary shell commands to inspect and modify files.")
        }
        if (CodexRuntimeFeature.PLUGINS in features) {
            add("Use enabled plugin tools through their advertised typed contracts.")
        }
        add(
            "Use the built-in web search tool only when the user input contains the structured " +
                "'${AgentCapability.WEB_SEARCH.promptLabel}' prompt tag.",
        )
    }.joinToString(" ")
    val config = buildJsonObject {
        put("web_search", "live")
        putJsonObject("tools") {
            putJsonObject("experimental_request_user_input") { put("enabled", true) }
        }
        putJsonObject("features") {
            put("shell_tool", CodexRuntimeFeature.SHELL_COMMANDS in features)
            put("code_mode", false)
            put("multi_agent", false)
            put("apps", CodexRuntimeFeature.CONNECTORS in features)
            put("enable_mcp_apps", CodexRuntimeFeature.CONNECTORS in features)
            put("plugins", CodexRuntimeFeature.PLUGINS in features)
            put("image_generation", false)
            put("goals", false)
            put("hooks", CodexRuntimeFeature.HOOKS in features)
            put("skill_mcp_dependency_install", false)
            put("workspace_dependencies", false)
            put("standalone_web_search", false)
        }
        if (CodexRuntimeFeature.SHELL_COMMANDS in features) {
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
    }
    val opened = if (conversationId == null) {
        val result = connection.request(
            AppServerClientMethods.ThreadStart,
            ThreadStartParams(
                approvalPolicy = JsonPrimitive(settings.approvalPreset.wireApprovalPolicy()),
                approvalsReviewer = approvalsReviewer(settings.approvalPreset),
                config = config,
                cwd = workingDirectory,
                developerInstructions = developerInstructions,
                ephemeral = false,
                sandbox = SandboxMode.DANGER_FULL_ACCESS,
                serviceTier = settings.serviceTier,
                dynamicTools = toolProvider?.let {
                    builtInDynamicTools(
                        if (CodexRuntimeFeature.PLUGINS in features) {
                            builtInPluginEnabled.filterValues { it }.keys
                        } else {
                            emptySet()
                        },
                        builtInToolDefinitions,
                    )
                },
            ),
        )
        AgentEvent.ConversationOpened(
            conversationId = ConversationId(result.thread.id),
            model = result.model,
            effort = result.reasoningEffort,
            serviceTier = result.serviceTier,
        )
    } else {
        val result = connection.request(
            AppServerClientMethods.ThreadResume,
            ThreadResumeParams(
                threadId = conversationId.value,
                approvalPolicy = JsonPrimitive(settings.approvalPreset.wireApprovalPolicy()),
                approvalsReviewer = approvalsReviewer(settings.approvalPreset),
                config = config,
                cwd = workingDirectory,
                developerInstructions = developerInstructions,
                sandbox = SandboxMode.DANGER_FULL_ACCESS,
                serviceTier = settings.serviceTier,
            ),
        )
        AgentEvent.ConversationOpened(
            conversationId = ConversationId(result.thread.id),
            model = result.model,
            effort = null,
            serviceTier = settings.serviceTier,
        )
    }
    return withContext(NonCancellable) {
        val openedId = opened.conversationId
        conversationOwnershipLock.withLock {
            stateLock.withLock {
                openedConversations += openedId
                conversationOwners[openedId] = owner
                conversationRuntimeSettings[openedId] = ConversationRuntimeSettings(
                    workspace = workingDirectory,
                    approvalPreset = settings.approvalPreset,
                    model = opened.model,
                    effort = opened.effort,
                )
            }
        }
        eventsChannel.send(opened)
        openedId
    }
}
