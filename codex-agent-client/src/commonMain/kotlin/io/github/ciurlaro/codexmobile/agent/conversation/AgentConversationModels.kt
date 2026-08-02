package io.github.ciurlaro.codexmobile.agent

import kotlin.jvm.JvmInline

@JvmInline
value class SessionId(val value: String)

data class AgentModel(
    val id: String,
    val displayName: String,
    val description: String,
    val supportedEfforts: List<String>,
    val defaultEffort: String,
    val isDefault: Boolean,
    val serviceTiers: List<AgentServiceTier> = emptyList(),
    val defaultServiceTier: String? = null,
)

data class AgentServiceTier(
    val id: String,
    val name: String,
    val description: String,
)

enum class AgentApprovalPreset(
    val displayName: String,
    val approvalPolicy: String,
    val approvalsReviewer: String,
) {
    NEVER("Never", "never", "user"),
    AUTO_REVIEW("Auto review", "on-request", "auto_review"),
    ASK_ME("Ask me", "on-request", "user"),
    STRICT("Strict", "untrusted", "user"),
}

enum class AgentApprovalDecision {
    ACCEPT,
    DECLINE,
}

data class AgentRuntimeSettings(
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.AUTO_REVIEW,
    val serviceTier: String? = null,
    val workingDirectory: String? = null,
)

data class AgentConversationSummary(
    val sessionId: SessionId,
    val title: String,
    val updatedAtEpochSeconds: Long,
)

data class AgentConversation(
    val summary: AgentConversationSummary,
    val messages: List<AgentMessage>,
)

enum class AgentMessageRole { USER, CODEX }

data class AgentMessage(
    val id: String,
    val clientId: String?,
    val role: AgentMessageRole,
    val text: String,
    val collaborationMode: AgentCollaborationMode = AgentCollaborationMode.DEFAULT,
    val reasoning: String? = null,
    val plan: String? = null,
    val shellCommand: String? = null,
    val exitCode: Int? = null,
    val capabilities: Set<AgentCapability> = emptySet(),
    val invocations: List<AgentInvocation> = emptyList(),
)

enum class AgentCollaborationMode { DEFAULT, PLAN }

const val PLAN_CLIENT_MESSAGE_PREFIX = "codex-mobile:plan:"

enum class AgentPlanStepStatus { PENDING, IN_PROGRESS, COMPLETED }

data class AgentPlanStep(val text: String, val status: AgentPlanStepStatus)

data class AgentPlanProgress(
    val explanation: String? = null,
    val steps: List<AgentPlanStep> = emptyList(),
)

enum class AgentHookTrustStatus { MANAGED, UNTRUSTED, TRUSTED, MODIFIED }

data class AgentHook(
    val key: String,
    val currentHash: String,
    val enabled: Boolean,
    val eventName: String,
    val handlerType: String,
    val isManaged: Boolean,
    val source: String,
    val sourcePath: String,
    val timeoutSeconds: Long,
    val trustStatus: AgentHookTrustStatus,
    val command: String? = null,
    val matcher: String? = null,
    val pluginId: String? = null,
    val statusMessage: String? = null,
)

data class AgentHookCatalog(
    val hooks: List<AgentHook>,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
)

enum class AgentHookRunStatus { RUNNING, COMPLETED, FAILED, BLOCKED, STOPPED }

data class AgentHookActivity(
    val id: String,
    val eventName: String,
    val handlerType: String,
    val status: AgentHookRunStatus,
    val statusMessage: String? = null,
    val details: List<String> = emptyList(),
)

enum class AgentCapability(
    val id: String,
    val displayLabel: String,
    val icon: String?,
    val promptLabel: String,
) {
    WEB_SEARCH("web_search", "Web search", "🌐", "Use 🌐 Web search"),
}

data class AgentTurnRequest(
    val prompt: String,
    val clientMessageId: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val serviceTier: String? = null,
    val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.AUTO_REVIEW,
    val capabilities: Set<AgentCapability> = emptySet(),
    val invocations: List<AgentInvocation> = emptyList(),
    val workingDirectory: String? = null,
    val collaborationMode: AgentCollaborationMode = AgentCollaborationMode.DEFAULT,
)

fun deriveConversationTitle(
    explicitName: String?,
    firstUserText: String,
    maxLength: Int = 80,
): String {
    require(maxLength > 0)
    val title = explicitName?.trim()?.takeIf { it.isNotEmpty() }
        ?: firstUserText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: "New chat"
    return title.take(maxLength).trimEnd()
}
