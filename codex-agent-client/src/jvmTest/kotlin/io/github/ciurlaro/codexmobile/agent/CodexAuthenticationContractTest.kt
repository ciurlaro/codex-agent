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

class CodexAuthenticationContractTest {
    @Test
    fun deviceCodeAuthenticationUsesTheExistingAppServerRoute(): Unit = runBlocking {
        var loginType: String? = null
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
                    loginType = message.objectValue["params"]!!.jsonObject["type"]!!.jsonPrimitive.content
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("type", "chatgptDeviceCode")
                            put("loginId", "device-login")
                            put("userCode", "ABCD-EFGH")
                            put("verificationUrl", "https://auth.openai.com/device")
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val required = async {
                withTimeout(1_000) {
                    client.events.filterIsInstance<AgentEvent.DeviceCodeAuthenticationRequired>().first()
                }
            }
            client.authenticate(CodexAuthenticationMethod.ChatGptDeviceCode)

            assertEquals("chatgptDeviceCode", loginType)
            assertEquals("ABCD-EFGH", required.await().userCode)
        } finally {
            client.close()
        }
    }

    @Test
    fun apiKeyAuthenticationIsImmediateAndRedacted(): Unit = runBlocking {
        var observedKey: String? = null
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
                    assertEquals("apiKey", params["type"]!!.jsonPrimitive.content)
                    observedKey = params["apiKey"]!!.jsonPrimitive.content
                    server.respond(message.id, buildJsonObject { put("type", "apiKey") })
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val method = CodexAuthenticationMethod.ApiKey("sk-test-secret")
        try {
            val authenticated = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.Authenticated>().first() }
            }
            client.authenticate(method)

            authenticated.await()
            assertEquals("sk-test-secret", observedKey)
            assertFalse(method.toString().contains("sk-test-secret"))
        } finally {
            client.close()
        }
    }

    @Test
    fun failedAuthenticationCanBeRetriedWithoutConflictingLoginState(): Unit = runBlocking {
        val loginAttempts = AtomicInteger()
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
                    val attempt = loginAttempts.incrementAndGet()
                    val loginId = "login-$attempt"
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("type", "chatgpt")
                            put("loginId", loginId)
                            put("authUrl", "https://auth.openai.com/oauth/authorize?state=$loginId")
                        },
                    )
                    server.notify(
                        "account/login/completed",
                        buildJsonObject {
                            put("loginId", loginId)
                            put("success", false)
                            put("error", "expired")
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            repeat(2) {
                val failed = async {
                    withTimeout(1_000) {
                        client.events.filterIsInstance<AgentEvent.AuthenticationFailed>().first()
                    }
                }
                client.authenticate()
                assertEquals("expired", failed.await().message)
            }
            assertEquals(2, loginAttempts.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun cancelledAuthenticationSuppressesTheExpectedFailureAndCanBeRetried(): Unit = runBlocking {
        val loginAttempts = AtomicInteger()
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
                    val loginId = "login-${loginAttempts.incrementAndGet()}"
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("type", "chatgpt")
                            put("loginId", loginId)
                            put("authUrl", "https://auth.openai.com/oauth/authorize?state=$loginId")
                        },
                    )
                }
                "account/login/cancel" -> {
                    val loginId = message.objectValue["params"]!!.jsonObject["loginId"]!!.jsonPrimitive.content
                    server.notify(
                        "account/login/completed",
                        buildJsonObject {
                            put("loginId", loginId)
                            put("success", false)
                            put("error", "cancelled")
                        },
                    )
                    server.respond(message.id, buildJsonObject { put("status", "canceled") })
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val events = mutableListOf<AgentEvent>()
            val collector = async { client.events.take(2).toList(events) }
            client.authenticate()
            client.cancelAuthentication()
            client.authenticate()
            withTimeout(1_000) { collector.await() }

            assertEquals(2, loginAttempts.get())
            assertTrue(events.all { it is AgentEvent.AuthenticationRequired })
        } finally {
            client.close()
        }
    }

    @Test
    fun authenticationTimeoutIsBoundedAndRetryable(): Unit = runBlocking {
        val reads = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> if (reads.incrementAndGet() > 1) {
                    server.respond(
                        message.id,
                        buildJsonObject {
                            putJsonObject("account") { put("type", "chatgpt") }
                            put("requiresOpenaiAuth", true)
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 50)
        try {
            assertFailsWith<Exception> { client.authenticate() }
            val authenticated = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.Authenticated>().first() }
            }
            client.authenticate()
            authenticated.await()
            assertEquals(2, reads.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun rejectsBlankPromptsAndPreservesUnicodeAndMultilinePrompts(): Unit = runBlocking {
        val neverStarted = CodexAgentClient({ error("must not launch") })
        assertFailsWith<IllegalArgumentException> {
            neverStarted.sendTurn(ConversationId("thread"), AgentTurnRequest("  \n"))
        }
        assertFailsWith<IllegalArgumentException> {
            neverStarted.sendTurn(
                ConversationId("thread"),
                AgentTurnRequest("x".repeat(100_001)),
            )
        }
        neverStarted.close()

        var observedPrompt: String? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "turn/start" -> {
                    observedPrompt = message.objectValue["params"]!!.jsonObject["input"]!!
                        .jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", "turn") } },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val prompt = "Grüezi 👋\n第二行"
            client.sendTurn(ConversationId("thread"), AgentTurnRequest(prompt))
            assertEquals(prompt, observedPrompt)
        } finally {
            client.close()
        }
    }
}

internal class ChunkedInputStream(
    bytes: ByteArray,
    private val chunkSize: Int,
) : ByteArrayInputStream(bytes) {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, minOf(length, chunkSize))
}
