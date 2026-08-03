package io.github.ciurlaro.codexmobile.agent

sealed interface AgentEvent {
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

    data class SessionOpened(
        val sessionId: SessionId,
        val model: String? = null,
        val effort: String? = null,
        val serviceTier: String? = null,
    ) : AgentEvent

    data class TextDelta(
        val sessionId: SessionId,
        val text: String,
        val itemId: String? = null,
        val isCommentary: Boolean = false,
    ) : AgentEvent

    data class ReasoningSummaryDelta(
        val sessionId: SessionId,
        val text: String,
        val itemId: String,
        val summaryIndex: Long,
    ) : AgentEvent

    data class PlanDelta(
        val sessionId: SessionId,
        val text: String,
        val itemId: String,
    ) : AgentEvent

    data class PlanUpdated(
        val sessionId: SessionId,
        val progress: AgentPlanProgress,
    ) : AgentEvent

    data class HookActivityChanged(
        val sessionId: SessionId,
        val activity: AgentHookActivity,
    ) : AgentEvent

    data class ShellOutputDelta(
        val sessionId: SessionId,
        val text: String,
    ) : AgentEvent

    data class ShellCommandCompleted(
        val sessionId: SessionId,
        val exitCode: Int?,
    ) : AgentEvent

    data class ApprovalRequested(
        val sessionId: SessionId,
        val requestId: String,
        val title: String,
        val details: String,
    ) : AgentEvent

    data class WorkActivityChanged(
        val sessionId: SessionId,
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

    data class TurnCompleted(val sessionId: SessionId) : AgentEvent

    data class Failure(
        val sessionId: SessionId?,
        val code: String,
        val message: String,
        val recoverable: Boolean,
    ) : AgentEvent
}

enum class AgentWorkActivity {
    RUNNING_COMMAND,
    WRITING_FILES,
}
