package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.DynamicToolSpecFunctionDynamicToolSpec
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal class BuiltInToolsProtocolTest : BuiltInToolsProtocolTestBase() {
    @Test
    fun alwaysEnabledToolsSkipPluginDiscoveryAndRemainCallable(): Unit = runBlocking {
        val response = CountDownLatch(1)
        val pluginReads = AtomicInteger()
        var advertised = emptyList<String>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> pluginReads.incrementAndGet()
                "thread/start" -> {
                    advertised = message.objectValue["params"]!!.jsonObject["dynamicTools"]!!.jsonArray
                        .map { it.jsonObject["name"]!!.jsonPrimitive.content }
                    server.respond(message.id, thread("thread-1"))
                }
                "turn/start" -> {
                    server.respond(message.id, turn("turn-1"))
                    server.request(940, "item/tool/call", toolCall("ios_read_file", "read-1"))
                }
                null -> if (message.id == 940L) response.countDown()
            }
        }
        val definition = testDefinition("ios-local-filesystem", "ios_read_file", mutation = false)
            .copy(requiresEnabledPlugin = false)
        val dispatcher = object : BuiltInToolDispatcher {
            override fun definitions() = listOf(definition)
            override suspend fun execute(call: BuiltInToolCall) = BuiltInToolResult.text("local")
        }
        CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = dispatcher,
        ).use { client ->
            val session = client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
            client.sendTurn(session, AgentTurnRequest("read", workingDirectory = "/workspace"))

            assertTrue(response.await(1, TimeUnit.SECONDS))
            assertEquals(0, pluginReads.get())
            assertEquals(listOf("ios_read_file"), advertised)
        }
    }

    @Test
    fun schemasAreClosedAndContainOnlyTheStableToolSet() {
        val tools = builtInDynamicTools(setOf(ALPHA_PLUGIN_ID, BETA_PLUGIN_ID), TEST_DEFINITIONS)
        val functions = tools.map { assertIs<DynamicToolSpecFunctionDynamicToolSpec>(it) }
        assertEquals(TEST_DEFINITIONS.map { it.name }, functions.map { it.name })
        functions.forEach { function ->
            val schema = function.inputSchema.jsonObject
            assertEquals("false", schema["additionalProperties"]!!.jsonPrimitive.content)
        }
        assertFalse(Regex("\"(command|subcommand|argv|rawArguments)\"").containsMatchIn(functions.map { it.inputSchema }.toString()))
    }

    @Test
    fun newChatsAdvertiseEnabledPluginsAndStaleCallsFailImmediatelyAfterDisable(): Unit = runBlocking {
        var threadStart: JsonObject? = null
        val calls = AtomicInteger()
        val firstResponse = CountDownLatch(1)
        val disabledResponse = CountDownLatch(1)
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> server.respond(message.id, pluginCatalog(alpha = true, beta = false))
                "thread/start" -> {
                    threadStart = message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, thread("thread-1"))
                }
                "turn/start" -> {
                    server.respond(message.id, turn("turn-1"))
                    server.request(900, "item/tool/call", toolCall("alpha_read", "call-1"))
                }
                "config/value/write" -> server.respond(message.id, buildJsonObject {})
                "turn/steer" -> server.respond(message.id, buildJsonObject { put("turnId", "turn-1") })
                null -> when (message.id) {
                    900L -> firstResponse.countDown()
                    901L -> disabledResponse.countDown()
                }
            }
        }
        val client = CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = dispatcher {
                calls.incrementAndGet()
                BuiltInToolResult.text("result")
            },
        )
        try {
            val session = client.openSession(
                settings = AgentRuntimeSettings(workingDirectory = "/workspace"),
            )
            val names = threadStart!!["dynamicTools"]!!.jsonArray.map {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("alpha_read", "alpha_view", "alpha_edit"), names)

            client.sendTurn(session, AgentTurnRequest("read", workingDirectory = "/workspace"))
            assertTrue(firstResponse.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())

            client.setPluginEnabled(ALPHA_PLUGIN_ID, false)
            process.request(901, "item/tool/call", toolCall("alpha_read", "call-2"))
            assertTrue(disabledResponse.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun failedConfigWritesDoNotChangeAdvertisedEnablement(): Unit = runBlocking {
        val advertised = mutableListOf<List<String>>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> server.respond(message.id, pluginCatalog(alpha = true, beta = false))
                "thread/start" -> {
                    advertised += message.objectValue["params"]!!.jsonObject["dynamicTools"]!!.jsonArray.map {
                        it.jsonObject["name"]!!.jsonPrimitive.content
                    }
                    server.respond(message.id, thread("thread-${advertised.size}"))
                }
                "config/value/write" -> server.sendRaw(
                    buildJsonObject {
                        put("id", message.id)
                        putJsonObject("error") { put("code", -32603); put("message", "write failed") }
                    }.toString(),
                )
            }
        }
        CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = dispatcher { BuiltInToolResult.text("unused") },
        ).use { client ->
            client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
            assertFailsWith<AppServerRpcException> { client.setPluginEnabled(ALPHA_PLUGIN_ID, false) }
            client.openSession(settings = AgentRuntimeSettings(workingDirectory = "/workspace"))
            assertEquals(advertised[0], advertised[1])
            assertTrue("alpha_edit" in advertised[1])
        }
    }

}
