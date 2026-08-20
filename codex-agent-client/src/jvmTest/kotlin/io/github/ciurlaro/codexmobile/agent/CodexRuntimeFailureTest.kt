package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.agent.*
import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnectionState
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

class CodexRuntimeFailureTest {
    @Test
    fun aStaleTerminalCannotOverwriteTheMatchingTerminalDuringTheNextStart(): Unit = runBlocking {
        val turnIds = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> {
                    val turnId = "turn-${turnIds.incrementAndGet()}"
                    if (turnId == "turn-2") {
                        server.notify("turn/completed", completedTurn("turn-2"))
                        server.notify("turn/completed", completedTurn("stale-turn"))
                    }
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", turnId) } },
                    )
                    if (turnId == "turn-1") server.notify("turn/completed", completedTurn(turnId))
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val conversationId = ConversationId("thread-1")
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.TurnCompleted>().first() }
            }
            client.sendTurn(conversationId, AgentTurnRequest("first"))
            first.await()

            val second = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.TurnCompleted>().first() }
            }
            client.sendTurn(conversationId, AgentTurnRequest("second"))
            assertEquals(conversationId, second.await().conversationId)
            assertFailsWith<IllegalStateException> { client.cancelTurn(conversationId) }
        } finally {
            client.close()
        }
    }

    @Test
    fun aDeadProcessCanRestartAndRecheckAuthentication(): Unit = runBlocking {
        val processes = mutableListOf<FakeCodexRuntime>()
        val client = CodexAgentClient(
            {
                FakeCodexRuntime { message, server ->
                    when (message.method) {
                        "initialize" -> server.respond(message.id, buildJsonObject {})
                        "account/read" -> server.respond(
                            message.id,
                            buildJsonObject {
                                putJsonObject("account") { put("type", "chatgpt") }
                                put("requiresOpenaiAuth", true)
                            },
                        )
                    }
                }.also(processes::add)
            },
            requestTimeoutMillis = 1_000,
        )
        try {
            val events = async { withTimeout(5_000) { client.events.take(3).toList() } }
            client.authenticate()
            processes.single().exit(9)
            client.authenticate()

            val received = events.await()
            assertIs<AgentEvent.Authenticated>(received[0])
            assertIs<AgentEvent.Failure>(received[1])
            assertIs<AgentEvent.Authenticated>(received[2])
            assertEquals(2, processes.size)
        } finally {
            client.close()
        }
    }

    @Test
    fun terminalFailureClosesRuntimeWhileEventDeliveryIsBackpressured(): Unit = runBlocking {
        val notificationsSent = CountDownLatch(1)
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "skills/list" -> {
                    server.respond(message.id, buildJsonObject { put("data", buildJsonArray {}) })
                    repeat(64) { server.notify("skills/changed", buildJsonObject {}) }
                    notificationsSent.countDown()
                }
            }
        }
        val failureArmed = AtomicBoolean()
        val failureHandled = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        lateinit var client: CodexAgentClient
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = worker.dispatch(context) {
                block.run()
                if (
                    failureArmed.get() &&
                    client.knownSkillPaths.isEmpty() &&
                    client.connection.state.value is AppServerConnectionState.Failed
                ) failureHandled.countDown()
            }
        }
        client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000, coroutineDispatcher = dispatcher)
        try {
            client.listSkills("/workspace")
            assertTrue(notificationsSent.await(1, TimeUnit.SECONDS))
            client.stateLock.withLock { client.knownSkillPaths += "/failure-sentinel" }
            failureArmed.set(true)
            process.sendRaw("{")
            assertTrue(failureHandled.await(1, TimeUnit.SECONDS))
            assertTrue(process.allClientStreamsClosed())

            val events = withTimeout(1_000) { client.events.take(2).toList() }
            assertEquals("event_backlog_overflow", assertIs<AgentEvent.Failure>(events[0]).code)
            assertEquals("protocol_failure", assertIs<AgentEvent.Failure>(events[1]).code)
        } finally {
            client.close()
            worker.close()
        }
    }

    @Test
    fun rejectsMalformedUnknownAndOrphanMessagesWithoutDeadlock(): Unit = runBlocking {
        val requestRejected = CountDownLatch(1)
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> {
                    server.sendRaw("{\"id\":999,\"result\":{}}")
                    server.notify("future/method", buildJsonObject { put("extra", true) })
                    server.sendRaw("{\"id\":800,\"method\":\"future/request\",\"params\":{}}")
                    server.respond(
                        message.id,
                        buildJsonObject {
                            putJsonObject("account") { put("type", "chatgpt") }
                            put("requiresOpenaiAuth", true)
                        },
                    )
                }
                null -> if (
                    message.id == 800L &&
                    message.objectValue["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content == "-32601"
                ) {
                    requestRejected.countDown()
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            withTimeout(1_000) { client.authenticate() }
            assertTrue(requestRejected.await(1, TimeUnit.SECONDS))
        } finally {
            client.close()
        }

        val malformed = FakeCodexRuntime { message, server ->
            if (message.method == "initialize") server.sendRaw("not-json")
        }
        val malformedClient = CodexAgentClient({ malformed }, requestTimeoutMillis = 1_000)
        try {
            assertFailsWith<Exception> { malformedClient.authenticate() }
        } finally {
            malformedClient.close()
        }
    }

    @Test
    fun boundsLargeMessagesSlowConsumersAndCancellationRaces(): Unit = runBlocking {
        assertFailsWith<IllegalStateException> {
            readUtf8JsonLines(ByteArrayInputStream("12345".toByteArray()), maxBytes = 4) {}
        }

        val interruptReceived = CountDownLatch(1)
        val releaseInterrupt = CountDownLatch(1)
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> server.respond(
                    message.id,
                    buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                )
                "turn/interrupt" -> {
                    interruptReceived.countDown()
                    check(releaseInterrupt.await(1, TimeUnit.SECONDS))
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val session = ConversationId("thread-1")
            client.sendTurn(session, AgentTurnRequest("hello"))
            coroutineScope {
                val first = async(start = CoroutineStart.UNDISPATCHED) { client.cancelTurn(session) }
                withTimeout(1_000) {
                    while (!interruptReceived.await(10, TimeUnit.MILLISECONDS)) {
                        if (first.isCompleted) first.await()
                        kotlinx.coroutines.yield()
                    }
                }
                val second = runCatching { client.cancelTurn(session) }
                releaseInterrupt.countDown()
                first.await()
                assertTrue(second.isFailure)
            }
        } finally {
            releaseInterrupt.countDown()
            client.close()
        }
    }

    @Test
    fun aTerminalNotificationRacingTheStartResponseLeavesTheClientUsable(): Unit = runBlocking {
        val turnIds = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> {
                    val turnId = "turn-${turnIds.incrementAndGet()}"
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", turnId) } },
                    )
                    server.notify(
                        "turn/completed",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            putJsonObject("turn") {
                                put("id", turnId)
                                put("status", "completed")
                            }
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val session = ConversationId("thread-1")
        try {
            repeat(20) {
                val completed = async {
                    withTimeout(1_000) {
                        client.events.filterIsInstance<AgentEvent.TurnCompleted>().first()
                    }
                }
                client.sendTurn(session, AgentTurnRequest("fast turn"))
                completed.await()
                assertFailsWith<IllegalStateException> { client.cancelTurn(session) }
            }
        } finally {
            client.close()
        }
    }

}

private fun completedTurn(turnId: String) = buildJsonObject {
    put("threadId", "thread-1")
    putJsonObject("turn") {
        put("id", turnId)
        put("status", "completed")
    }
}
