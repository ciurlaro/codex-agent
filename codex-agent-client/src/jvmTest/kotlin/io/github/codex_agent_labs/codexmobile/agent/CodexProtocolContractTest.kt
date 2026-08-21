package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.agent.AgentEvent
import io.github.codex_agent_labs.codexmobile.agent.AgentTurnRequest
import io.github.codex_agent_labs.codexmobile.agent.ConversationId
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class CodexProtocolContractTest {
    @Test
    fun framesPartialBatchedCRLFAndUTF8Input() {
        val unicode = "Grüezi 👋"
        val bytes = "{\"n\":1}\r\n{\"text\":\"$unicode\"}\n".toByteArray(StandardCharsets.UTF_8)
        val lines = mutableListOf<String>()

        readUtf8JsonLines(ChunkedInputStream(bytes, 2), onLine = lines::add)

        assertEquals(listOf("{\"n\":1}", "{\"text\":\"$unicode\"}"), lines)
    }

    @Test
    fun fuzzedFramesStayBoundedAndMalformedUTF8FailsClosed() {
        val random = Random(6)
        val elapsed = measureTimeMillis {
            repeat(2_048) {
                val input = ByteArray(random.nextInt(0, 4_096)).also(random::nextBytes)
                try {
                    readUtf8JsonLines(ByteArrayInputStream(input), maxBytes = 4_096) { line ->
                        assertTrue(line.toByteArray(StandardCharsets.UTF_8).size <= 4_096)
                    }
                } catch (_: Exception) {
                    // Random malformed frames are expected; assertion errors must still fail the test.
                }
            }
            repeat(64) {
                assertFailsWith<IllegalStateException> {
                    readUtf8JsonLines(ByteArrayInputStream(ByteArray(4_097) { 'x'.code.toByte() }), 4_096) {}
                }
            }
            listOf(
                byteArrayOf(0xC3.toByte(), 0x28),
                byteArrayOf(0xA0.toByte(), 0xA1.toByte()),
                byteArrayOf(0xE2.toByte(), 0x28, 0xA1.toByte()),
                byteArrayOf(0xF0.toByte(), 0x28, 0x8C.toByte(), 0xBC.toByte()),
            ).forEach { bytes ->
                assertFailsWith<Exception> { readUtf8JsonLines(ByteArrayInputStream(bytes)) {} }
            }
        }
        assertTrue(elapsed < 5_000, "fuzz elapsed ${elapsed}ms")
    }

    @Test
    fun correlatesResponsesWhilePreservingNotificationOrder(): Unit = runBlocking {
        val launches = AtomicInteger()
        var accountId: Long? = null
        var threadId: Long? = null
        var threadParams: JsonObject? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> accountId = message.id
                "thread/start" -> {
                    threadId = message.id
                    threadParams = message.objectValue["params"]!!.jsonObject
                }
            }
            if (accountId != null && threadId != null) {
                server.notify("unknown/notification", buildJsonObject {})
                server.respond(
                    threadId,
                    buildJsonObject { putJsonObject("thread") { put("id", "thread-1") } },
                )
                server.respond(
                    accountId,
                    buildJsonObject {
                        putJsonObject("account") { put("type", "chatgpt") }
                        put("requiresOpenaiAuth", true)
                    },
                )
                accountId = null
                threadId = null
            }
        }
        val client = CodexAgentClient(
            { launches.incrementAndGet(); process },
            requestTimeoutMillis = 1_000,
        )
        try {
            coroutineScope {
                val auth = async { client.authenticate() }
                val session = async { client.openConversation() }
                auth.await()
                assertEquals(ConversationId("thread-1"), session.await())
            }
            assertEquals(1, launches.get())
            val params = checkNotNull(threadParams)
            assertEquals("on-request", params["approvalPolicy"]!!.jsonPrimitive.content)
            assertEquals("auto_review", params["approvalsReviewer"]!!.jsonPrimitive.content)
            assertEquals("danger-full-access", params["sandbox"]!!.jsonPrimitive.content)
            val instructions = params["developerInstructions"]!!.jsonPrimitive.content
            assertFalse("raw argv" in instructions)
            assertTrue("advertised typed contracts" in instructions)
            val config = params["config"]!!.jsonObject
            assertEquals("live", config["web_search"]!!.jsonPrimitive.content)
            assertEquals(
                true,
                config["tools"]!!.jsonObject["experimental_request_user_input"]!!
                    .jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean(),
            )
            val features = config["features"]!!.jsonObject
            listOf(
                "code_mode",
                "multi_agent",
                "image_generation",
                "goals",
                "skill_mcp_dependency_install",
                "workspace_dependencies",
            ).forEach { feature ->
                assertEquals(false, features[feature]!!.jsonPrimitive.content.toBoolean())
            }
            listOf("apps", "enable_mcp_apps", "plugins", "hooks").forEach { feature ->
                assertEquals(true, features[feature]!!.jsonPrimitive.content.toBoolean())
            }
            assertEquals(true, features["shell_tool"]!!.jsonPrimitive.content.toBoolean())
            val shellEnvironment = config["shell_environment_policy"]!!.jsonObject
            assertEquals("all", shellEnvironment["inherit"]!!.jsonPrimitive.content)
            assertTrue(
                "HTTPS_PROXY" in shellEnvironment["exclude"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun signOutUsesTheAccountEndpointAndClearsInMemoryAuthentication(): Unit = runBlocking {
        val accountReads = AtomicInteger()
        var logoutParams: JsonObject? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> {
                    accountReads.incrementAndGet()
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("account") { put("type", "chatgpt") } },
                    )
                }
                "account/logout" -> {
                    logoutParams = message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            client.authenticate()
            client.signOut()
            client.authenticate()

            assertEquals(2, accountReads.get())
            assertTrue(checkNotNull(logoutParams).isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun drainsStderrAndClosesTheProcessAndAllStreams(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> {
                    server.sendStderr("stderr is not JSON and must never enter stdout")
                    server.respond(message.id, buildJsonObject {})
                }
                "account/read" -> server.respond(
                    message.id,
                    buildJsonObject {
                        putJsonObject("account") { put("type", "chatgpt") }
                        put("requiresOpenaiAuth", true)
                    },
                )
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)

        client.authenticate()
        client.close()

        assertFalse(process.isAlive)
        assertTrue(process.allClientStreamsClosed())
    }

    @Test
    fun turnsProcessStartExitAndEOFIntoOneTypedFailure(): Unit = runBlocking {
        val startClient = CodexAgentClient(
            { error("cannot execute bundled runtime") },
            requestTimeoutMillis = 1_000,
        )
        try {
            val failure = async {
                withTimeout(1_000) { startClient.events.filterIsInstance<AgentEvent.Failure>().first() }
            }
            assertFailsWith<IllegalStateException> { startClient.authenticate() }
            assertEquals("process_start", failure.await().code)
        } finally {
            startClient.close()
        }

        val exited = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.exit(23)
            }
        }
        val exitClient = CodexAgentClient({ exited }, requestTimeoutMillis = 1_000)
        try {
            val failure = async {
                withTimeout(1_000) { exitClient.events.filterIsInstance<AgentEvent.Failure>().first() }
            }
            assertFailsWith<IllegalStateException> { exitClient.authenticate() }
            assertEquals("process_exit", failure.await().code)
        } finally {
            exitClient.close()
        }

        val eof = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.closeStdout()
            }
        }
        val eofClient = CodexAgentClient({ eof }, requestTimeoutMillis = 1_000)
        try {
            val failure = async {
                withTimeout(1_000) { eofClient.events.filterIsInstance<AgentEvent.Failure>().first() }
            }
            assertFailsWith<IllegalStateException> { eofClient.authenticate() }
            assertEquals("unexpected_eof", failure.await().code)
        } finally {
            eofClient.close()
        }
    }

}
