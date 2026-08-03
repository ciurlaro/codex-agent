package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.DynamicToolSpec
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.DynamicToolSpecFunctionDynamicToolSpec
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class BuiltInToolDefinition(
    val pluginId: String,
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val mutation: Boolean = false,
    val requiresEnabledPlugin: Boolean = true,
)

data class BuiltInToolCall(
    val threadId: String,
    val turnId: String,
    val callId: String,
    val pluginId: String,
    val tool: String,
    val arguments: JsonObject,
    val workspace: String,
    val argumentsHash: String,
    val deadlineEpochMillis: Long = Long.MAX_VALUE,
)

data class BuiltInToolResult(
    val content: List<BuiltInToolContent>,
    val success: Boolean,
) {
    companion object {
        fun text(value: String, success: Boolean = true) =
            BuiltInToolResult(listOf(BuiltInToolContent.Text(value)), success)
    }
}

sealed interface BuiltInToolContent {
    data class Text(val value: String) : BuiltInToolContent
    data class Image(val dataUrl: String) : BuiltInToolContent
}

fun interface BuiltInToolDispatcher {
    suspend fun execute(call: BuiltInToolCall): BuiltInToolResult

    fun definitions(): List<BuiltInToolDefinition> = emptyList()

    suspend fun execute(
        call: BuiltInToolCall,
        beforeMutationDispatch: suspend () -> Unit = {},
    ): BuiltInToolResult = execute(call)

    suspend fun execute(
        call: BuiltInToolCall,
        checkActive: suspend () -> Unit,
        beforeMutationDispatch: suspend () -> Unit,
    ): BuiltInToolResult = execute(call, beforeMutationDispatch)

    suspend fun replay(call: BuiltInToolCall): BuiltInToolResult? = null
}

enum class TypedMutationAuthority { DIRECT, USER_APPROVAL }

fun typedMutationAuthority(preset: AgentApprovalPreset): TypedMutationAuthority = when (preset) {
    AgentApprovalPreset.NEVER -> TypedMutationAuthority.DIRECT
    AgentApprovalPreset.AUTO_REVIEW,
    AgentApprovalPreset.ASK_ME,
    AgentApprovalPreset.STRICT,
    -> TypedMutationAuthority.USER_APPROVAL
}

fun builtInDynamicTools(
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
