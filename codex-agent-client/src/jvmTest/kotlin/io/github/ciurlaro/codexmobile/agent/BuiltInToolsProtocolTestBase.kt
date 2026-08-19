package io.github.ciurlaro.codexmobile.agent

import java.util.concurrent.CountDownLatch
import kotlinx.serialization.json.*

internal abstract class BuiltInToolsProtocolTestBase {
    protected fun mutationRuntime(response: CountDownLatch) = FakeCodexRuntime { message, server ->
        when (message.method) {
            "initialize" -> server.respond(message.id, buildJsonObject {})
            "plugin/installed" -> server.respond(message.id, pluginCatalog(alpha = false, beta = true))
            "thread/start" -> server.respond(message.id, thread("thread-1"))
            "turn/start" -> {
                server.respond(message.id, turn("turn-1"))
                server.request(950, "item/tool/call", toolCall("beta_send", "send-1"))
            }
            null -> if (message.id == 950L) response.countDown()
        }
    }

    protected fun pluginCatalog(alpha: Boolean, beta: Boolean) = buildJsonObject {
        putJsonArray("marketplaces") {
            add(buildJsonObject {
                put("name", "codex-mobile")
                putJsonArray("plugins") {
                    add(plugin(ALPHA_PLUGIN_ID, "alpha", alpha))
                    add(plugin(BETA_PLUGIN_ID, "beta", beta))
                }
            })
        }
        putJsonArray("marketplaceLoadErrors") {}
    }

    protected fun plugin(id: String, name: String, enabled: Boolean) = buildJsonObject {
        put("id", id)
        put("name", name)
        put("installed", true)
        put("enabled", enabled)
        put("installPolicy", "AVAILABLE")
        put("authPolicy", "ON_USE")
        put("availability", "AVAILABLE")
        putJsonObject("interface") {
            put("displayName", name)
            put("shortDescription", name)
            put("capabilities", buildJsonArray {})
        }
    }

    protected fun thread(id: String) = buildJsonObject {
        putJsonObject("thread") { put("id", id) }
    }

    protected fun turn(id: String) = buildJsonObject {
        putJsonObject("turn") { put("id", id) }
    }

    protected fun toolCall(tool: String, callId: String) = buildJsonObject {
        put("threadId", "thread-1")
        put("turnId", "turn-1")
        put("callId", callId)
        put("tool", tool)
        put("startedAtMs", System.currentTimeMillis())
        put(
            "arguments",
            if (tool == "alpha_read") {
                buildJsonObject { put("path", "item") }
            } else {
                buildJsonObject { put("to", "me"); put("message", "hello") }
            },
        )
    }
}

internal const val ALPHA_PLUGIN_ID = "alpha@fixture"
internal const val BETA_PLUGIN_ID = "beta@fixture"

internal val TEST_DEFINITIONS = listOf(
    "alpha_read", "alpha_view", "alpha_edit",
).map { tool -> testDefinition(ALPHA_PLUGIN_ID, tool, tool == "alpha_edit") } + listOf(
    "beta_list", "beta_send",
).map { tool -> testDefinition(BETA_PLUGIN_ID, tool, tool == "beta_send") }

internal fun testDefinition(pluginId: String, name: String, mutation: Boolean) = BuiltInToolDefinition(
    pluginId,
    name,
    name,
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {}
        put("additionalProperties", false)
    },
    mutation,
)

internal fun dispatcher(block: suspend (BuiltInToolCall) -> BuiltInToolResult) = object : CodexToolProvider {
    override fun definitions() = TEST_DEFINITIONS
    override suspend fun execute(
        call: BuiltInToolCall,
        context: CodexToolExecutionContext,
    ): BuiltInToolResult {
        if (definitions().single { it.name == call.tool }.isMutation) context.beforeMutation()
        return block(call)
    }
}
