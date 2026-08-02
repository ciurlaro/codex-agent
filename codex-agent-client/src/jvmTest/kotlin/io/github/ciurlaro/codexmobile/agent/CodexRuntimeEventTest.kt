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
            client.sendTurn(SessionId("thread-1"), AgentTurnRequest("hello"))
            client.cancelTurn(SessionId("thread-1"))
            assertEquals(SessionId("thread-1"), completed.await().sessionId)
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
            client.sendTurn(SessionId("thread-1"), AgentTurnRequest("hello"))
            withTimeout(2_000) {
                while (process.isAlive) kotlinx.coroutines.yield()
            }
            val failure = withTimeout(5_000) {
                client.events.filterIsInstance<AgentEvent.Failure>().first()
            }
            assertTrue(failure.message.contains("event buffer exceeded"))
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
            suspend fun nextEvent(): AgentEvent = withTimeout(5_000) { client.events.first() }

            client.authenticate()
            val required = assertIs<AgentEvent.AuthenticationRequired>(nextEvent())
            assertEquals("https://auth.openai.com/oauth/authorize?state=test", required.signInUrl)

            process.notify(
                "account/login/completed",
                buildJsonObject {
                    put("loginId", "login-1")
                    put("success", true)
                    put("error", JsonNull)
                },
            )
            assertIs<AgentEvent.Authenticated>(nextEvent())

            val session = client.openSession()
            assertEquals(AgentEvent.SessionOpened(SessionId("thread-1"), model = "test"), nextEvent())

            client.sendTurn(session, AgentTurnRequest("hello"))
            assertEquals(
                AgentEvent.TextDelta(SessionId("thread-1"), "Hello", "item-1", isCommentary = true),
                nextEvent(),
            )
            assertEquals(AgentEvent.TurnCompleted(SessionId("thread-1")), nextEvent())
            assertIs<AgentEvent.Failure>(nextEvent())
        } finally {
            client.close()
        }
    }

}
