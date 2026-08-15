package io.github.ciurlaro.codexmobile.agent

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AgentAuthenticationSessionTest {
    @Test
    fun browserAuthenticationOpensAndClosesTheValidatedPresentation() = runBlocking {
        lateinit var runtime: FakeCodexRuntime
        var opened: CodexAuthorizationUrl? = null
        var presentationClosed = false
        runtime = authenticationRuntime { loginId ->
            buildJsonObject {
                put("type", "chatgpt")
                put("loginId", loginId)
                put("authUrl", "https://auth.openai.com/oauth?state=$loginId")
            }
        }
        val client = CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000)
        val session = AgentAuthenticationSession(
            client = client,
            scope = this,
            browser = CodexAuthorizationBrowser { url ->
                opened = url
                CodexAuthorizationPresentation { presentationClosed = true }
            },
        )
        try {
            session.authenticate()
            withTimeout(1_000) { session.state.first { it.pendingSignInUrl != null } }
            assertEquals("https://auth.openai.com/oauth?state=login-1", opened?.value)

            runtime.notify(
                "account/login/completed",
                buildJsonObject {
                    put("loginId", "login-1")
                    put("success", true)
                },
            )
            withTimeout(1_000) {
                session.state.first { it.status == AgentAuthenticationStatus.AUTHENTICATED }
            }
            assertTrue(presentationClosed)
        } finally {
            session.close()
            client.close()
        }
    }

    @Test
    fun cancelThenRetryUsesANewGenerationWithoutAStaleFailure() = runBlocking {
        val attempts = AtomicInteger()
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.respond(message.id, signedOutAccount())
                "account/login/start" -> {
                    val loginId = "login-${attempts.incrementAndGet()}"
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("type", "chatgpt")
                            put("loginId", loginId)
                            put("authUrl", "https://auth.openai.com/oauth?state=$loginId")
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
        val client = CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000)
        val session = AgentAuthenticationSession(client, this)
        try {
            session.authenticate()
            withTimeout(1_000) { session.state.first { it.pendingSignInUrl != null } }
            val firstGeneration = session.state.value.generation
            session.cancel()
            assertEquals(AgentAuthenticationStatus.SIGNED_OUT, session.state.value.status)

            session.retry()
            withTimeout(1_000) {
                session.state.first {
                    it.status == AgentAuthenticationStatus.AUTHENTICATING &&
                        it.generation > firstGeneration && it.pendingSignInUrl != null
                }
            }
            assertEquals(2, attempts.get())
        } finally {
            session.close()
            client.close()
        }
    }
}

private fun authenticationRuntime(response: (String) -> kotlinx.serialization.json.JsonObject): FakeCodexRuntime {
    val attempts = AtomicInteger()
    return FakeCodexRuntime { message, server ->
        when (message.method) {
            "initialize" -> server.respond(message.id, buildJsonObject {})
            "account/read" -> server.respond(message.id, signedOutAccount())
            "account/login/start" -> server.respond(message.id, response("login-${attempts.incrementAndGet()}"))
        }
    }
}

private fun signedOutAccount() = buildJsonObject {
    put("account", JsonNull)
    put("requiresOpenaiAuth", true)
}
