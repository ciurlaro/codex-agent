@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.codex_agent_labs.codexmobile.app.runtime.ios

import io.github.codex_agent_labs.codexmobile.agent.BuiltInToolCall
import io.github.codex_agent_labs.codexmobile.agent.BuiltInToolDefinition
import io.github.codex_agent_labs.codexmobile.agent.BuiltInToolResult
import io.github.codex_agent_labs.codexmobile.agent.CodexToolExecutionContext
import io.github.codex_agent_labs.codexmobile.agent.CodexToolProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.cinterop.toKString
import platform.posix.free
import platform.posix.realpath

internal class IosCodexWorkspaceTools(
    private val configuration: IosCodexRuntimeConfiguration,
) : CodexToolProvider {
    override fun definitions(): List<BuiltInToolDefinition> = DEFINITIONS

    override suspend fun execute(
        call: BuiltInToolCall,
        context: CodexToolExecutionContext,
    ): BuiltInToolResult {
        if (call.tool in IOS_MUTATING_TOOLS) context.beforeMutation() else context.checkActive()
        return executeCall(call)
    }

    internal suspend fun executeForTest(call: BuiltInToolCall): BuiltInToolResult = executeCall(call)

    private suspend fun executeCall(call: BuiltInToolCall): BuiltInToolResult {
        if (!sameIosWorkspace(call.workspacePath, configuration.workspacePath)) {
            return BuiltInToolResult.text("The tool workspace does not match the local iOS workspace", false)
        }
        return executeIosWorkspaceTool(configuration, call.tool, call.arguments)
    }

    private companion object {
        const val PLUGIN_ID = "ios-local-workspace"

        val DEFINITIONS = listOf(
            definition(
                name = "apply_patch",
                description = "Apply a Codex patch to files in the local iOS workspace.",
                properties = buildJsonObject {
                    stringProperty("patch", "Complete patch text from *** Begin Patch through *** End Patch")
                },
                required = listOf("patch"),
                isMutation = true,
            ),
            definition(
                name = "read_file",
                description = "Read a UTF-8 file from the local iOS workspace.",
                properties = buildJsonObject {
                    stringProperty("path", "Workspace-relative file path")
                    integerProperty("offset", "Optional UTF-8 byte offset", minimum = 0)
                    integerProperty("limit", "Optional maximum bytes, capped at 262144", minimum = 1)
                },
                required = listOf("path"),
            ),
            definition(
                name = "list_directory",
                description = "List one directory in the local iOS workspace.",
                properties = buildJsonObject {
                    stringProperty("path", "Workspace-relative directory path; defaults to .")
                },
            ),
            definition(
                name = "search_text",
                description = "Search UTF-8 files recursively in the local iOS workspace.",
                properties = buildJsonObject {
                    stringProperty("query", "Literal text to find")
                    stringProperty("path", "Workspace-relative directory path; defaults to .")
                    putJsonObject("caseSensitive") {
                        put("type", "boolean")
                        put("description", "Whether matching is case-sensitive")
                    }
                },
                required = listOf("query"),
            ),
            definition(
                name = "write_file",
                description = "Atomically write a UTF-8 file in the local iOS workspace.",
                properties = buildJsonObject {
                    stringProperty("path", "Workspace-relative file path")
                    stringProperty("content", "Complete UTF-8 file content")
                },
                required = listOf("path", "content"),
                isMutation = true,
            ),
        )

        fun definition(
            name: String,
            description: String,
            properties: JsonObject,
            required: List<String> = emptyList(),
            isMutation: Boolean = false,
        ) = BuiltInToolDefinition(
            pluginId = PLUGIN_ID,
            name = name,
            description = description,
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", properties)
                put("additionalProperties", false)
                if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
            },
            isMutation = isMutation,
            requiresEnabledPlugin = false,
        )

        fun JsonObjectBuilder.stringProperty(name: String, description: String) =
            putJsonObject(name) {
                put("type", "string")
                put("description", description)
            }

        fun JsonObjectBuilder.integerProperty(name: String, description: String, minimum: Int) =
            putJsonObject(name) {
                put("type", "integer")
                put("description", description)
                put("minimum", minimum)
            }
    }
}

internal fun sameIosWorkspace(first: String, second: String): Boolean =
    first == second || canonicalIosPath(first)?.let { it == canonicalIosPath(second) } == true

private fun canonicalIosPath(path: String): String? {
    val resolved = realpath(path, null) ?: return null
    return try {
        resolved.toKString()
    } finally {
        free(resolved)
    }
}
