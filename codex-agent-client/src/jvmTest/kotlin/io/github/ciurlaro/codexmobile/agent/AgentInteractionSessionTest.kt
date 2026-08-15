package io.github.ciurlaro.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class AgentInteractionSessionTest {
    @Test
    fun keepsRequestsForEveryObserverAndOwnsResolutionAndBrowserCleanup(): Unit = runBlocking {
        val approvalResponse = CompletableDeferred<JsonObject>()
        val elicitationResponse = CompletableDeferred<JsonObject>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/resume" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-1") }
                })
                null -> when (message.id) {
                    101L -> approvalResponse.complete(message.objectValue.getValue("result").jsonObject)
                    102L -> elicitationResponse.complete(message.objectValue.getValue("result").jsonObject)
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        var openedUrl: CodexAuthorizationUrl? = null
        var openedCount = 0
        var closedCount = 0
        val interactions = AgentInteractionSession(
            client,
            this,
            CodexAuthorizationBrowser { url ->
                openedUrl = url
                openedCount += 1
                CodexAuthorizationPresentation { closedCount += 1 }
            },
        )
        try {
            client.openSession(SessionId("thread-1"))
            yield()

            process.request(101, "item/commandExecution/requestApproval", approvalRequest())
            process.request(102, "mcpServer/elicitation/request", urlElicitation(102))

            val firstObserver = async {
                withTimeout(1_000) { interactions.state.first { it.pending.size == 2 } }
            }
            val secondObserver = async {
                withTimeout(1_000) { interactions.state.first { it.pending.size == 2 } }
            }
            val pending = firstObserver.await().pending
            assertEquals(pending, secondObserver.await().pending)
            assertIs<AgentPendingApproval>(pending[0])
            assertIs<AgentPendingElicitation>(pending[1])
            assertEquals(pending, interactions.state.value.pending)

            interactions.resolveApproval("101", AgentApprovalDecision.ACCEPT)
            assertEquals("accept", approvalResponse.await().getValue("decision").jsonPrimitive.content)
            assertFailsWith<IllegalStateException> {
                interactions.resolveApproval("101", AgentApprovalDecision.ACCEPT)
            }

            interactions.openUrl("102")
            interactions.openUrl("102")
            assertEquals("https://accounts.example.com/authorize", openedUrl?.value)
            assertEquals(2, openedCount)
            assertEquals(1, closedCount)

            interactions.resolveElicitation(
                "102",
                AgentElicitationResponse(AgentElicitationAction.ACCEPT),
            )
            assertEquals("accept", elicitationResponse.await().getValue("action").jsonPrimitive.content)
            assertEquals(2, closedCount)
            assertTrue(interactions.state.value.pending.isEmpty())

            process.request(103, "mcpServer/elicitation/request", urlElicitation(103))
            withTimeout(1_000) { interactions.state.first { it.pending.any { pending -> pending.requestId == "103" } } }
            interactions.openUrl("103")
            process.notify(
                "turn/completed",
                buildJsonObject {
                    put("threadId", "thread-1")
                    putJsonObject("turn") {
                        put("id", "turn-1")
                        put("status", "completed")
                    }
                },
            )
            withTimeout(1_000) { interactions.state.first { it.pending.isEmpty() } }
            assertEquals(3, closedCount)
        } finally {
            interactions.close()
            client.close()
        }
        assertTrue(interactions.state.value.closed)
    }
}

private fun approvalRequest() = buildJsonObject {
    put("itemId", "item-1")
    put("startedAtMs", 1)
    put("threadId", "thread-1")
    put("turnId", "turn-1")
    put("command", "git status")
    put("reason", "Inspect the workspace")
}

private fun urlElicitation(id: Int) = buildJsonObject {
    put("serverName", "example")
    put("threadId", "thread-1")
    put("elicitationId", "elicitation-$id")
    put("message", "Sign in")
    put("url", "https://accounts.example.com/authorize")
    put("turnId", "turn-1")
    put("mode", "url")
}
