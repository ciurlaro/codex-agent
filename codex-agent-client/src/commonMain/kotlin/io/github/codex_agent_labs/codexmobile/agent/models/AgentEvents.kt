package io.github.codex_agent_labs.codexmobile.agent

internal sealed interface AgentEvent {
    data class AuthenticationRequired(
        val signInUrl: String,
    ) : AgentEvent {
        override fun toString(): String = "AuthenticationRequired"
    }

    data class DeviceCodeAuthenticationRequired(
        val verificationUrl: String,
        val userCode: String,
    ) : AgentEvent {
        override fun toString(): String = "DeviceCodeAuthenticationRequired"
    }

    data object Authenticated : AgentEvent

    data class AuthenticationFailed(val message: String) : AgentEvent

    data class ConversationOpened(
        val conversationId: ConversationId,
        val model: String? = null,
        val effort: String? = null,
        val serviceTier: String? = null,
    ) : AgentEvent

    data class TextDelta(
        val conversationId: ConversationId,
        val text: String,
        val itemId: String? = null,
        val isCommentary: Boolean = false,
    ) : AgentEvent

    data class ReasoningSummaryDelta(
        val conversationId: ConversationId,
        val text: String,
        val itemId: String,
        val summaryIndex: Long,
    ) : AgentEvent

    data class PlanDelta(
        val conversationId: ConversationId,
        val text: String,
        val itemId: String,
    ) : AgentEvent

    data class PlanUpdated(
        val conversationId: ConversationId,
        val progress: AgentPlanProgress,
    ) : AgentEvent

    data class HookActivityChanged(
        val conversationId: ConversationId,
        val activity: AgentHookActivity,
    ) : AgentEvent

    data class ShellOutputDelta(
        val conversationId: ConversationId,
        val text: String,
    ) : AgentEvent

    data class ShellCommandCompleted(
        val conversationId: ConversationId,
        val exitCode: Int?,
    ) : AgentEvent

    data class ApprovalRequested(
        val conversationId: ConversationId,
        val requestId: String,
        val title: String,
        val details: String,
    ) : AgentEvent

    data class WorkActivityChanged(
        val conversationId: ConversationId,
        val activity: AgentWorkActivity?,
    ) : AgentEvent

    data object SkillsChanged : AgentEvent

    data object PluginsChanged : AgentEvent

    data object ConnectorsChanged : AgentEvent

    data class McpOauthCompleted(
        val serverName: String,
        val success: Boolean,
        val error: String? = null,
    ) : AgentEvent

    data class ElicitationRequested(val elicitation: AgentElicitation) : AgentEvent

    data class TurnCompleted(val conversationId: ConversationId) : AgentEvent

    data class Failure(
        val conversationId: ConversationId?,
        val code: String,
        val message: String,
        val isRecoverable: Boolean,
    ) : AgentEvent
}

public enum class AgentWorkActivity {
    RUNNING_COMMAND,
    WRITING_FILES,
}
