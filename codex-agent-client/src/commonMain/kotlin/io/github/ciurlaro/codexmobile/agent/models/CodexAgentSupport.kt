package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.McpServerElicitationRequestResponse
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.ConversationId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal data class LoginCompletion(
    val loginId: String,
    val success: Boolean,
    val error: String?,
)

internal data class ConversationRuntimeSettings(
    val workspace: String?,
    val approvalPreset: AgentApprovalPreset,
    val model: String?,
    val effort: String?,
)

internal data class PendingTurnTerminal(
    val turnId: String,
    val event: AgentEvent,
)

internal data class PendingBuiltInApproval(
    val wireId: JsonElement,
    val call: BuiltInToolCall,
    val requiresPermit: Boolean,
    var permit: Boolean = false,
    var dispatch: Boolean = false,
)

internal data class PendingApproval(
    val wireId: JsonElement,
    val type: ApprovalType,
    val conversationId: ConversationId,
)

internal sealed interface PendingElicitation {
    val wireId: JsonElement
    val elicitation: AgentElicitation

    data class Mcp(
        override val wireId: JsonElement,
        override val elicitation: AgentElicitation,
    ) : PendingElicitation

    data class UserInput(
        override val wireId: JsonElement,
        override val elicitation: AgentElicitation,
    ) : PendingElicitation
}

internal enum class ApprovalType { COMMAND, FILE_CHANGE }

internal val PROTOCOL_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
}
internal const val EVENT_BUFFER_SIZE = 64
internal const val MAX_PROMPT_CHARS = 100_000
internal const val MAX_SHELL_TRANSCRIPT_CHARS = 256 * 1024
internal const val TRUNCATION_MARKER = "\n[Response truncated]"
internal const val MAX_BUILT_IN_RESULT_CHARS = 250_000
internal const val BUILT_IN_TOOL_DEADLINE_MILLIS = 120_000L
internal const val SKILL_CHUNK_BYTES = 32 * 1024
internal const val CATALOG_CACHE_TTL_MILLIS = 6 * 60 * 60 * 1000L
internal val EMPTY_PLUGIN_CATALOG_RETRY_DELAYS_MILLIS = listOf(500L, 1_000L, 2_000L)
internal const val INTERNAL_APPS_MCP_SERVER = "codex_apps"

internal fun completeUtf8Length(bytes: ByteArray, count: Int): Int {
    if (count == 0) return 0
    var lead = count - 1
    while (lead >= 0 && bytes[lead].toInt() and 0xC0 == 0x80) lead--
    if (lead < 0) return 0
    val expected = when (bytes[lead].toInt() and 0xFF) {
        in 0xC2..0xDF -> 2
        in 0xE0..0xEF -> 3
        in 0xF0..0xF4 -> 4
        else -> 1
    }
    return if (count - lead < expected) lead else count
}

internal fun Throwable.visibleMessage(): String =
    message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"
