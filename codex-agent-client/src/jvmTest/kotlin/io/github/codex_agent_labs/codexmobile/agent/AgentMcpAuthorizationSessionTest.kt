package io.github.codex_agent_labs.codexmobile.agent

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class IntegrationAuthorizationControllerTest {
    @Test
    fun accessibleConnectorAuthorizesWithoutOpeningTheBrowser(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "app/list" -> server.respond(message.id, connectorList(isAccessible = true))
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val browserOpens = AtomicInteger()
        val authorization = IntegrationAuthorizationController(
            client,
            this,
            CodexAuthorizationBrowser {
                browserOpens.incrementAndGet()
                CodexAuthorizationPresentation.None
            },
        )
        try {
            authorization.authorizeConnector("drive", null)

            assertEquals(AgentIntegrationAuthorizationStatus.AUTHORIZED, authorization.state.value.status)
            assertEquals("Drive", authorization.state.value.target?.displayName)
            assertEquals(0, browserOpens.get())
        } finally {
            authorization.close()
            client.close()
        }
    }

    @Test
    fun connectorWithoutAnAuthorizationUrlFailsClearly(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "app/list" -> server.respond(
                    message.id,
                    connectorList(isAccessible = false, includeInstallUrl = false),
                )
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val authorization = IntegrationAuthorizationController(
            client,
            this,
            CodexAuthorizationBrowser { CodexAuthorizationPresentation.None },
        )
        try {
            assertFailsWith<CodexOperationException> { authorization.authorizeConnector("drive", null) }

            assertEquals(AgentIntegrationAuthorizationStatus.FAILED, authorization.state.value.status)
            assertEquals("Connector authorization failed", authorization.state.value.failure?.message)
        } finally {
            authorization.close()
            client.close()
        }
    }

    @Test
    fun connectorRefreshCompletesAuthorizationAndDismissalOwnsBrowserCleanup(): Unit = runBlocking {
        val accessible = AtomicBoolean(false)
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "app/list" -> server.respond(message.id, connectorList(accessible.get()))
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val authorization = IntegrationAuthorizationController(
            client,
            this,
            CodexAuthorizationBrowser {
                opened.incrementAndGet()
                CodexAuthorizationPresentation { closed.incrementAndGet() }
            },
        )
        try {
            authorization.authorizeConnector("drive", ConversationId("thread-1"))
            assertEquals(
                AgentIntegrationAuthorizationStatus.AWAITING_COMPLETION,
                authorization.state.value.status,
            )
            assertEquals(1, opened.get())

            accessible.set(true)
            process.notify("app/list/updated", connectorList(isAccessible = true))
            withTimeout(1_000) {
                authorization.state.first { it.status == AgentIntegrationAuthorizationStatus.AUTHORIZED }
            }
            assertEquals(1, closed.get())
            authorization.dismiss()
            assertEquals(AgentIntegrationAuthorizationStatus.IDLE, authorization.state.value.status)

            accessible.set(false)
            authorization.authorizeConnector("drive", null)
            assertEquals(2, opened.get())
            authorization.dismiss()
            assertEquals(AgentIntegrationAuthorizationStatus.IDLE, authorization.state.value.status)
            assertEquals(2, closed.get())
        } finally {
            authorization.close()
            client.close()
        }
    }

    @Test
    fun connectorBrowserFailureReleasesTheAttemptAsFailed(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "app/list" -> server.respond(message.id, connectorList(isAccessible = false))
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val authorization = IntegrationAuthorizationController(
            client,
            this,
            CodexAuthorizationBrowser { error("browser failed") },
        )
        try {
            assertFailsWith<CodexOperationException> { authorization.authorizeConnector("drive", null) }
            assertEquals(AgentIntegrationAuthorizationStatus.FAILED, authorization.state.value.status)
            assertEquals("Could not open the authorization URL", authorization.state.value.failure?.message)
        } finally {
            authorization.close()
            client.close()
        }
    }

    @Test
    fun callerCancellationFailsAndReleasesTheActiveAuthorization(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "mcpServer/oauth/login" -> Unit
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 5_000)
        val authorization = IntegrationAuthorizationController(
            client,
            this,
            CodexAuthorizationBrowser { CodexAuthorizationPresentation.None },
        )
        try {
            val operation = async { authorization.authorizeMcpServer("drive") }
            withTimeout(1_000) {
                authorization.state.first { it.status == AgentIntegrationAuthorizationStatus.STARTING }
            }
            operation.cancelAndJoin()

            assertEquals(AgentIntegrationAuthorizationStatus.IDLE, authorization.state.value.status)
        } finally {
            authorization.close()
            client.close()
        }
    }

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
        var openedUrl: CodexAuthorizationUrl? = null
        val authorization = IntegrationAuthorizationController(
            client,
            this,
            CodexAuthorizationBrowser { url ->
                openedUrl = url
                opened += 1
                CodexAuthorizationPresentation { closed += 1 }
            },
        )
        try {
            authorization.authorizeMcpServer("drive", ConversationId("thread-1"))
            assertEquals("https://accounts.example.com/oauth/drive", openedUrl?.value)
            assertEquals(
                AgentIntegrationAuthorizationStatus.AWAITING_COMPLETION,
                authorization.state.value.status,
            )
            assertEquals(AgentIntegrationKind.MCP_SERVER, authorization.state.value.target?.kind)
            assertFailsWith<IllegalStateException> { authorization.authorizeMcpServer("calendar") }

            process.notify("mcpServer/oauthLogin/completed", completion("calendar", success = true))
            yield()
            assertEquals(
                AgentIntegrationAuthorizationStatus.AWAITING_COMPLETION,
                authorization.state.value.status,
            )

            process.notify("mcpServer/oauthLogin/completed", completion("drive", success = true))
            withTimeout(1_000) {
                authorization.state.first { it.status == AgentIntegrationAuthorizationStatus.AUTHORIZED }
            }
            assertEquals(1, closed)
            authorization.dismiss()
            assertEquals(AgentIntegrationAuthorizationStatus.IDLE, authorization.state.value.status)

            authorization.authorizeMcpServer("calendar")
            assertEquals(2, opened)
            process.notify(
                "mcpServer/oauthLogin/completed",
                completion("calendar", success = false, error = "denied"),
            )
            withTimeout(1_000) {
                authorization.state.first { it.status == AgentIntegrationAuthorizationStatus.FAILED }
            }
            assertEquals("denied", authorization.state.value.failure?.message)
            assertEquals(2, closed)
        } finally {
            authorization.close()
            client.close()
        }
    }
}

private fun completion(name: String, success: Boolean, error: String? = null) = buildJsonObject {
    put("name", name)
    put("success", success)
    error?.let { put("error", it) }
}

private fun connectorList(
    isAccessible: Boolean,
    includeInstallUrl: Boolean = true,
) = buildJsonObject {
    putJsonArray("data") {
        add(buildJsonObject {
            put("id", "drive")
            put("name", "Drive")
            put("description", "Files")
            if (includeInstallUrl) put("installUrl", "https://accounts.example.com/oauth")
            put("isAccessible", isAccessible)
            put("isEnabled", true)
        })
    }
}
