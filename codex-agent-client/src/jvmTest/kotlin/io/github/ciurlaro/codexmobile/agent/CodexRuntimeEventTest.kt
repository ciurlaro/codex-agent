package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.agent.*
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class CodexRuntimeEventTest {
    @Test
    fun interruptAfterProviderCompletionIsHarmless(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> server.respond(
                    message.id,
                    buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                )
                "turn/interrupt" -> {
                    server.sendRaw(
                        buildJsonObject {
                            put("id", message.id)
                            putJsonObject("error") {
                                put("code", -32600)
                                put("message", "no active turn to interrupt")
                            }
                        }.toString(),
                    )
                    server.notify(
                        "turn/completed",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            putJsonObject("turn") {
                                put("id", "turn-1")
                                put("status", "completed")
                            }
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val completed = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.TurnCompleted>().first() }
            }
            client.sendTurn(ConversationId("thread-1"), AgentTurnRequest("hello"))
            client.cancelTurn(ConversationId("thread-1"))
            assertEquals(ConversationId("thread-1"), completed.await().conversationId)
        } finally {
            client.close()
        }
    }

    @Test
    fun slowEventConsumersFailExplicitlyInsteadOfBlockingTheRuntimeReader(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> {
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                    )
                    repeat(2_000) {
                        server.notify(
                            "item/agentMessage/delta",
                            buildJsonObject {
                                put("threadId", "thread-1")
                                put("turnId", "turn-1")
                                put("itemId", "item-1")
                                put("delta", "x")
                            },
                        )
                    }
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val overflow = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) {
                    client.events
                        .onEach { delay(5) }
                        .filterIsInstance<AgentEvent.Failure>()
                        .first()
                }
            }
            client.sendTurn(ConversationId("thread-1"), AgentTurnRequest("hello"))
            assertEquals("event_observer_overflow", overflow.await().code)
            assertTrue(process.isAlive)
        } finally {
            client.close()
        }
    }

    @Test
    fun translatesAuthenticationSessionStreamCompletionAndFailureEvents(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.respond(
                    message.id,
                    buildJsonObject {
                        put("account", JsonNull)
                        put("requiresOpenaiAuth", true)
                    },
                )
                "account/login/start" -> {
                    val params = message.objectValue["params"]!!.jsonObject
                    assertEquals(setOf("type", "useHostedLoginSuccessPage", "appBrand"), params.keys)
                    assertEquals("chatgpt", params["type"]!!.jsonPrimitive.content)
                    assertEquals("true", params["useHostedLoginSuccessPage"]!!.jsonPrimitive.content)
                    assertEquals("codex", params["appBrand"]!!.jsonPrimitive.content)
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("type", "chatgpt")
                            put("loginId", "login-1")
                            put("authUrl", "https://auth.openai.com/oauth/authorize?state=test")
                        },
                    )
                }
                "thread/start" -> server.respond(
                    message.id,
                    buildJsonObject { putJsonObject("thread") { put("id", "thread-1") } },
                )
                "turn/start" -> {
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                    )
                    server.notify(
                        "item/started",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            put("turnId", "turn-1")
                            putJsonObject("item") {
                                put("id", "item-1")
                                put("type", "agentMessage")
                                put("phase", "commentary")
                                put("text", "")
                            }
                        },
                    )
                    server.notify(
                        "item/agentMessage/delta",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            put("turnId", "turn-1")
                            put("itemId", "item-1")
                            put("delta", "Hello")
                        },
                    )
                    server.notify(
                        "turn/completed",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            putJsonObject("turn") {
                                put("id", "turn-1")
                                put("status", "completed")
                            }
                        },
                    )
                    server.notify(
                        "error",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            put("turnId", "turn-2")
                            put("willRetry", false)
                            putJsonObject("error") { put("message", "offline") }
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val events = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5_000) { client.events.take(5).toList() }
            }

            client.authenticate()
            process.notify(
                "account/login/completed",
                buildJsonObject {
                    put("loginId", "login-1")
                    put("success", true)
                    put("error", JsonNull)
                },
            )
            val session = client.openConversation()
            client.sendTurn(session, AgentTurnRequest("hello"))
            val received = events.await()
            val required = assertIs<AgentEvent.AuthenticationRequired>(received[0])
            assertEquals("https://auth.openai.com/oauth/authorize?state=test", required.signInUrl)
            assertIs<AgentEvent.Authenticated>(received[1])
            assertEquals(AgentEvent.ConversationOpened(ConversationId("thread-1"), model = "test"), received[2])
            assertEquals(
                AgentEvent.TextDelta(ConversationId("thread-1"), "Hello", "item-1", isCommentary = true),
                received[3],
            )
            assertEquals(AgentEvent.TurnCompleted(ConversationId("thread-1")), received[4])
        } finally {
            client.close()
        }
    }

}
