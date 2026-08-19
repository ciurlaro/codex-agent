package io.github.ciurlaro.codexmobile.agent

public data class ConversationId(public val value: String) {
    init {
        require(value.isNotBlank()) { "Conversation ID must not be blank" }
    }
}

public data class AgentModel(
    public val id: String,
    public val displayName: String,
    public val description: String,
    public val supportedEfforts: List<String>,
    public val defaultEffort: String,
    public val isDefault: Boolean,
    public val serviceTiers: List<AgentServiceTier> = emptyList(),
    public val defaultServiceTier: String? = null,
)

public data class AgentServiceTier(
    public val id: String,
    public val name: String,
    public val description: String,
)

public enum class AgentApprovalPreset(
    public val displayName: String,
) {
    NEVER("Never"),
    AUTO_REVIEW("Auto review"),
    ASK_ME("Ask me"),
    STRICT("Strict"),
}

public enum class AgentApprovalDecision {
    ACCEPT,
    DECLINE,
}

public data class AgentConversationSettings(
    public val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.AUTO_REVIEW,
    public val serviceTier: String? = null,
)

public data class AgentConversationSummary(
    public val conversationId: ConversationId,
    public val title: String,
    public val updatedAtEpochSeconds: Long,
)

public data class AgentConversation(
    public val summary: AgentConversationSummary,
    public val messages: List<AgentMessage>,
)

public enum class AgentMessageRole { USER, ASSISTANT }

public data class AgentMessage(
    public val id: String,
    public val clientMessageId: String?,
    public val role: AgentMessageRole,
    public val text: String,
    public val collaborationMode: AgentCollaborationMode = AgentCollaborationMode.DEFAULT,
    public val reasoning: String? = null,
    public val plan: String? = null,
    public val shellCommand: String? = null,
    public val exitCode: Int? = null,
    public val capabilities: Set<AgentCapability> = emptySet(),
    public val invocations: List<AgentInvocation> = emptyList(),
)

public enum class AgentCollaborationMode { DEFAULT, PLAN }

internal const val PLAN_CLIENT_MESSAGE_PREFIX = "codex-agent:plan:"
internal const val LEGACY_PLAN_CLIENT_MESSAGE_PREFIX = "codex-mobile:plan:"

public enum class AgentPlanStepStatus { PENDING, IN_PROGRESS, COMPLETED }

public data class AgentPlanStep(public val text: String, public val status: AgentPlanStepStatus)

public data class AgentPlanProgress(
    public val explanation: String? = null,
    public val steps: List<AgentPlanStep> = emptyList(),
)

public enum class AgentHookTrustStatus { MANAGED, UNTRUSTED, TRUSTED, MODIFIED }

public data class AgentHook(
    public val key: String,
    public val currentHash: String,
    public val isEnabled: Boolean,
    public val eventName: String,
    public val handlerType: String,
    public val isManaged: Boolean,
    public val source: String,
    public val sourcePath: String,
    public val timeoutSeconds: Long,
    public val trustStatus: AgentHookTrustStatus,
    public val command: String? = null,
    public val matcher: String? = null,
    public val pluginId: String? = null,
    public val statusMessage: String? = null,
)

public data class AgentHookCatalog(
    public val hooks: List<AgentHook>,
    public val warnings: List<String> = emptyList(),
    public val errors: List<String> = emptyList(),
)

public enum class AgentHookRunStatus { RUNNING, COMPLETED, FAILED, BLOCKED, STOPPED }

public data class AgentHookActivity(
    public val id: String,
    public val eventName: String,
    public val handlerType: String,
    public val status: AgentHookRunStatus,
    public val statusMessage: String? = null,
    public val details: List<String> = emptyList(),
)

public enum class AgentCapability(
    public val id: String,
    public val displayLabel: String,
    public val icon: String?,
    public val promptLabel: String,
) {
    WEB_SEARCH("web_search", "Web search", "🌐", "Use 🌐 Web search"),
}

public data class AgentTurnRequest(
    public val prompt: String,
    public val clientMessageId: String? = null,
    public val model: String? = null,
    public val effort: String? = null,
    public val serviceTier: String? = null,
    public val approvalPreset: AgentApprovalPreset = AgentApprovalPreset.AUTO_REVIEW,
    public val capabilities: Set<AgentCapability> = emptySet(),
    public val invocations: List<AgentInvocation> = emptyList(),
    public val collaborationMode: AgentCollaborationMode = AgentCollaborationMode.DEFAULT,
)

internal fun deriveConversationTitle(
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
