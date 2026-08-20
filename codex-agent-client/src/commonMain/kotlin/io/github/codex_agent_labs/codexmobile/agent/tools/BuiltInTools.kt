package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.DynamicToolSpec
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.DynamicToolSpecFunctionDynamicToolSpec
import io.github.codex_agent_labs.codexmobile.agent.AgentApprovalPreset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

public data class BuiltInToolDefinition(
    public val pluginId: String,
    public val name: String,
    public val description: String,
    public val inputSchema: JsonObject,
    public val isMutation: Boolean = false,
    public val requiresEnabledPlugin: Boolean = true,
)

public data class BuiltInToolCall(
    public val conversationId: ConversationId,
    public val turnId: String,
    public val callId: String,
    public val pluginId: String,
    public val tool: String,
    public val arguments: JsonObject,
    public val workspacePath: String,
    public val argumentsHash: String,
    public val deadlineEpochMillis: Long = Long.MAX_VALUE,
)

public data class BuiltInToolResult(
    public val content: List<BuiltInToolContent>,
    public val success: Boolean,
) {
    public companion object {
        public fun text(value: String, success: Boolean = true): BuiltInToolResult =
            BuiltInToolResult(listOf(BuiltInToolContent.Text(value)), success)
    }
}

public sealed interface BuiltInToolContent {
    public data class Text(public val value: String) : BuiltInToolContent
    public data class Image(public val dataUrl: String) : BuiltInToolContent
}

public fun interface CodexToolProvider {
    public fun definitions(): List<BuiltInToolDefinition> = emptyList()

    @Throws(Exception::class)
    public suspend fun execute(
        call: BuiltInToolCall,
        context: CodexToolExecutionContext,
    ): BuiltInToolResult
}

public class CodexToolExecutionContext internal constructor(
    private val checkActiveAction: suspend () -> Unit,
    private val beforeMutationAction: suspend () -> Unit,
) {
    @Throws(Exception::class)
    public suspend fun checkActive(): Unit = checkActiveAction()

    @Throws(Exception::class)
    public suspend fun beforeMutation(): Unit = beforeMutationAction()
}

internal enum class TypedMutationAuthority { DIRECT, USER_APPROVAL }

internal fun typedMutationAuthority(preset: AgentApprovalPreset): TypedMutationAuthority = when (preset) {
    AgentApprovalPreset.NEVER -> TypedMutationAuthority.DIRECT
    AgentApprovalPreset.AUTO_REVIEW,
    AgentApprovalPreset.ASK_ME,
    AgentApprovalPreset.STRICT,
    -> TypedMutationAuthority.USER_APPROVAL
}

internal fun builtInDynamicTools(
    enabledPluginIds: Set<String>,
    definitions: List<BuiltInToolDefinition>,
): List<DynamicToolSpec> = definitions.filter { definition ->
    !definition.requiresEnabledPlugin || definition.pluginId in enabledPluginIds
}.map { definition ->
    DynamicToolSpecFunctionDynamicToolSpec(
        name = definition.name,
        description = definition.description,
        inputSchema = definition.inputSchema,
    )
}

internal fun canonicalJson(value: JsonElement): String = when (value) {
    is JsonObject -> value.entries.sortedBy(Map.Entry<String, JsonElement>::key)
        .joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${JsonPrimitive(key)}:${canonicalJson(item)}"
        }
    is JsonArray -> value.joinToString(prefix = "[", postfix = "]", transform = ::canonicalJson)
    else -> value.toString()
}

internal fun sha256(value: String): String = value.sha256Hex()
