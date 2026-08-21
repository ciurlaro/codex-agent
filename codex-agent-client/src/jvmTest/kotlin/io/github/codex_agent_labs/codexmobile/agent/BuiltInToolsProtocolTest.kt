package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.appserver.client.AppServerRpcException
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.DynamicToolSpecFunctionDynamicToolSpec
import io.github.codex_agent_labs.codexmobile.agent.AgentApprovalDecision
import io.github.codex_agent_labs.codexmobile.agent.AgentApprovalPreset
import io.github.codex_agent_labs.codexmobile.agent.AgentEvent
import io.github.codex_agent_labs.codexmobile.agent.AgentConversationSettings
import io.github.codex_agent_labs.codexmobile.agent.AgentTurnRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
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
        val dispatcher = object : CodexToolProvider {
            override fun definitions() = listOf(definition)
            override suspend fun execute(
                call: BuiltInToolCall,
                context: CodexToolExecutionContext,
            ) = BuiltInToolResult.text("local")
        }
        CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            toolProvider = dispatcher,
        ).use { client ->
            val conversationId = client.openConversation(null, AgentConversationSettings(), "/workspace")
            client.sendTurn(conversationId, AgentTurnRequest("read"), "/workspace")

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
            toolProvider = dispatcher {
                calls.incrementAndGet()
                BuiltInToolResult.text("result")
            },
        )
        try {
            val conversationId = client.openConversation(null, AgentConversationSettings(), "/workspace")
            val names = threadStart!!["dynamicTools"]!!.jsonArray.map {
                it.jsonObject["name"]!!.jsonPrimitive.content
            }
            assertEquals(listOf("alpha_read", "alpha_view", "alpha_edit"), names)

            client.sendTurn(conversationId, AgentTurnRequest("read"), "/workspace")
            assertTrue(firstResponse.await(1, TimeUnit.SECONDS))
            assertEquals(1, calls.get())

            assertFailsWith<IllegalStateException> {
                client.setPluginEnabled(ALPHA_PLUGIN_ID, false)
            }
            val completed = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.TurnCompleted>().first() }
            }
            process.notify(
                "turn/completed",
                buildJsonObject {
                    put("threadId", conversationId.value)
                    putJsonObject("turn") {
                        put("id", "turn-1")
                        put("status", "completed")
                    }
                },
            )
            completed.await()
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
            toolProvider = dispatcher { BuiltInToolResult.text("unused") },
        ).use { client ->
            client.openConversation(null, AgentConversationSettings(), "/workspace")
            assertFailsWith<AppServerRpcException> { client.setPluginEnabled(ALPHA_PLUGIN_ID, false) }
            client.openConversation(null, AgentConversationSettings(), "/workspace")
            assertEquals(advertised[0], advertised[1])
            assertTrue("alpha_edit" in advertised[1])
        }
    }

    @Test
    fun extensionMutationsAreRejectedWhileATurnStartsRunsAndCancels(): Unit = runBlocking {
        val startRequest = CompletableDeferred<Long>()
        val interruptRequest = CompletableDeferred<Long>()
        val configWrites = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, thread("thread-mutation-gate"))
                "turn/start" -> startRequest.complete(checkNotNull(message.id))
                "turn/interrupt" -> interruptRequest.complete(checkNotNull(message.id))
                "config/value/write" -> {
                    configWrites.incrementAndGet()
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }
        CodexAgentClient({ process }, requestTimeoutMillis = 1_000).use { client ->
            val conversationId = client.openConversation(
                null,
                AgentConversationSettings(),
                "/workspace",
            )
            val sending = async(start = CoroutineStart.UNDISPATCHED) {
                client.sendTurn(conversationId, AgentTurnRequest("hello"), "/workspace")
            }
            val turnStartId = withTimeout(1_000) { startRequest.await() }

            assertFailsWith<IllegalStateException> {
                client.setPluginEnabled("remote-plugin", false)
            }

            process.respond(turnStartId, turn("turn-mutation-gate"))
            sending.await()
            assertFailsWith<IllegalStateException> {
                client.setPluginEnabled("remote-plugin", false)
            }

            val cancelling = async(start = CoroutineStart.UNDISPATCHED) {
                client.cancelTurn(conversationId)
            }
            val interruptId = withTimeout(1_000) { interruptRequest.await() }
            assertFailsWith<IllegalStateException> {
                client.setPluginEnabled("remote-plugin", false)
            }

            process.respond(interruptId, buildJsonObject {})
            cancelling.await()
            assertEquals(0, configWrites.get())
        }
    }

}
