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
import kotlinx.serialization.json.putJsonObject

class AuthenticationControllerTest {
    @Test
    fun throwingPresentationCleanupDoesNotStopLaterAuthenticationEvents() = runBlocking {
        val runtime = FakeCodexRuntime { _, _ -> Unit }
        val client = CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000)
        var opened = 0
        val session = AuthenticationController(
            client,
            this,
            CodexAuthorizationBrowser {
                opened += 1
                if (opened == 1) {
                    CodexAuthorizationPresentation { error("close failed") }
                } else {
                    CodexAuthorizationPresentation.None
                }
            },
        )
        try {
            client.eventsChannel.send(AgentEvent.AuthenticationRequired("https://auth.openai.com/first"))
            withTimeout(1_000) { session.state.first { it.pendingSignInUrl?.value?.endsWith("/first") == true } }
            client.eventsChannel.send(AgentEvent.Authenticated)
            withTimeout(1_000) { session.state.first { it.status == AgentAuthenticationStatus.AUTHENTICATED } }

            client.eventsChannel.send(AgentEvent.AuthenticationRequired("https://auth.openai.com/second"))
            withTimeout(1_000) { session.state.first { it.pendingSignInUrl?.value?.endsWith("/second") == true } }
            assertEquals(2, opened)
        } finally {
            session.close()
            client.close()
        }
    }

    @Test
    fun authenticationFailureDoesNotFailConversationOrClearInteractions(): Unit = runBlocking {
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-1") }
                })
            }
        }
        val client = CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000)
        val authentication = AuthenticationController(client, this)
        val interactions = InteractionController(client, this)
        val conversation = CodexConversation(
            client,
            this,
            "/workspace",
            CodexRuntimeFeature.entries.toSet(),
            CodexConversation::closeOwned,
        )
        try {
            val conversationId = conversation.open()
            client.eventsChannel.send(
                AgentEvent.ApprovalRequested(
                    conversationId,
                    "approval-1",
                    "Review",
                    "Details",
                ),
            )
            withTimeout(1_000) { interactions.state.first { it.pending.size == 1 } }

            client.applyLoginCompletion(LoginCompletion("login-1", false, "Access denied"))
            withTimeout(1_000) { authentication.state.first { it.failure?.message == "Access denied" } }

            assertEquals(AgentConversationStatus.READY, conversation.state.value.status)
            assertEquals(1, interactions.state.value.pending.size)
        } finally {
            conversation.close()
            interactions.close()
            authentication.close()
            client.close()
        }
    }

    @Test
    fun authenticatingAgainWhileAuthenticatedKeepsTheStableState(): Unit = runBlocking {
        val accountReads = AtomicInteger()
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> {
                    accountReads.incrementAndGet()
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
        val client = CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000)
        val controller = AuthenticationController(client, this)
        try {
            controller.authenticate()
            withTimeout(1_000) {
                controller.state.first { it.status == AgentAuthenticationStatus.AUTHENTICATED }
            }

            controller.authenticate()

            assertEquals(AgentAuthenticationStatus.AUTHENTICATED, controller.state.value.status)
            assertEquals(1, accountReads.get())
        } finally {
            controller.close()
            client.close()
        }
    }

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
        val session = AuthenticationController(
            client = client,
            scope = this,
            authorizationBrowser = CodexAuthorizationBrowser { url ->
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
        val session = AuthenticationController(client, this)
        try {
            session.authenticate()
            withTimeout(1_000) { session.state.first { it.pendingSignInUrl != null } }
            session.cancel()
            assertEquals(AgentAuthenticationStatus.SIGNED_OUT, session.state.value.status)

            session.authenticate()
            withTimeout(1_000) {
                session.state.first {
                    it.status == AgentAuthenticationStatus.AUTHENTICATING &&
                        it.pendingSignInUrl?.value?.contains("login-2") == true
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
