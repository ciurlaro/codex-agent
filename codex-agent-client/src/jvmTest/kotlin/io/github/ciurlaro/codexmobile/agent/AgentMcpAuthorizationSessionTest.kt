package io.github.ciurlaro.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class AgentMcpAuthorizationSessionTest {
    @Test
    fun correlatesOneAttemptAndKeepsBrowserDismissalHonest(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "mcpServer/oauth/login" -> server.respond(
                    message.id,
                    buildJsonObject {
                        put(
                            "authorizationUrl",
                            "https://accounts.example.com/oauth/${message.params.requiredString("name")}",
                        )
                    },
                )
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        var opened = 0
        var closed = 0
        val authorization = AgentMcpAuthorizationSession(
            client,
            this,
            CodexAuthorizationBrowser {
                opened += 1
                CodexAuthorizationPresentation { closed += 1 }
            },
        )
        try {
            yield()
            val url = authorization.start("drive", SessionId("thread-1"))
            assertEquals("https://accounts.example.com/oauth/drive", url.value)
            assertEquals(AgentMcpAuthorizationStatus.AWAITING_COMPLETION, authorization.state.value.status)
            assertTrue(authorization.state.value.browserPresented)
            assertFailsWith<IllegalStateException> { authorization.start("calendar") }

            process.notify("mcpServer/oauthLogin/completed", completion("calendar", success = true))
            yield()
            assertEquals(AgentMcpAuthorizationStatus.AWAITING_COMPLETION, authorization.state.value.status)

            authorization.dismissBrowser()
            assertFalse(authorization.state.value.browserPresented)
            assertEquals(AgentMcpAuthorizationStatus.AWAITING_COMPLETION, authorization.state.value.status)
            assertEquals(1, closed)

            process.notify("mcpServer/oauthLogin/completed", completion("drive", success = true))
            withTimeout(1_000) {
                authorization.state.first { it.status == AgentMcpAuthorizationStatus.AUTHENTICATED }
            }
            assertEquals(1, closed)

            authorization.start("calendar")
            assertEquals(2, opened)
            process.notify(
                "mcpServer/oauthLogin/completed",
                completion("calendar", success = false, error = "denied"),
            )
            withTimeout(1_000) {
                authorization.state.first { it.status == AgentMcpAuthorizationStatus.FAILED }
            }
            assertEquals("denied", authorization.state.value.error)
            assertEquals(2, closed)
        } finally {
            authorization.close()
            client.close()
        }
        assertEquals(AgentMcpAuthorizationStatus.CLOSED, authorization.state.value.status)
    }
}

private fun completion(name: String, success: Boolean, error: String? = null) = buildJsonObject {
    put("name", name)
    put("success", success)
    error?.let { put("error", it) }
}
