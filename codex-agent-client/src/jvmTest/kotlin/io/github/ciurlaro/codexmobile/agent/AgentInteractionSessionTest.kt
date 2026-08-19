package io.github.ciurlaro.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class InteractionControllerTest {
    @Test
    fun cancelledResolutionCanBeRetried(): Unit = runBlocking {
        val response = CompletableDeferred<JsonObject>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/resume" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-1") }
                })
                null -> if (message.id == 105L) {
                    response.complete(message.objectValue.getValue("result").jsonObject)
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val interactions = InteractionController(client, this)
        var clientLockHeld = false
        try {
            client.openConversation(ConversationId("thread-1"))
            process.request(105, "item/commandExecution/requestApproval", approvalRequest())
            withTimeout(1_000) { interactions.state.first { it.pending.any { pending -> pending.requestId == "105" } } }

            client.stateLock.lock()
            clientLockHeld = true
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
                interactions.resolveApproval("105", AgentApprovalDecision.ACCEPT)
            }
            withTimeout(1_000) { interactions.state.first { "105" in it.resolvingRequestIds } }
            cancelled.cancel()
            assertFailsWith<CancellationException> { cancelled.await() }
            assertTrue("105" !in interactions.state.value.resolvingRequestIds)
            assertTrue(interactions.state.value.pending.any { it.requestId == "105" })

            client.stateLock.unlock()
            clientLockHeld = false
            interactions.resolveApproval("105", AgentApprovalDecision.ACCEPT)
            assertEquals("accept", response.await().getValue("decision").jsonPrimitive.content)
        } finally {
            if (clientLockHeld) client.stateLock.unlock()
            interactions.close()
            client.close()
        }
    }

    @Test
    fun snapshotsCallerOwnedElicitationCollectionsBeforeResolving(): Unit = runBlocking {
        val wireResponse = CompletableDeferred<JsonObject>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/resume" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-1") }
                })
                null -> if (message.id == 104L) {
                    wireResponse.complete(message.objectValue.getValue("result").jsonObject)
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val interactions = InteractionController(client, this)
        var stateLockHeld = false
        try {
            client.openConversation(ConversationId("thread-1"))
            process.request(104, "mcpServer/elicitation/request", formElicitation())
            withTimeout(1_000) {
                interactions.state.first { state -> state.pending.any { it.requestId == "104" } }
            }

            val selected = mutableListOf("one")
            val content = mutableMapOf<String, AgentFormValue>(
                "choices" to AgentFormValue.TextList(selected),
            )
            client.stateLock.lock()
            stateLockHeld = true
            val resolution = async(start = CoroutineStart.UNDISPATCHED) {
                interactions.resolveElicitation(
                    "104",
                    AgentElicitationResponse(AgentElicitationAction.ACCEPT, content),
                )
            }
            selected += "two"
            content["unknown"] = AgentFormValue.Text("changed")
            client.stateLock.unlock()
            stateLockHeld = false

            resolution.await()
            val encoded = wireResponse.await().getValue("content").jsonObject
            assertEquals(listOf("one"), encoded.getValue("choices").jsonArray.map { it.jsonPrimitive.content })
            assertEquals(setOf("choices"), encoded.keys)
        } finally {
            if (stateLockHeld) client.stateLock.unlock()
            interactions.close()
            client.close()
        }
    }

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
        val interactions = InteractionController(
            client,
            this,
            CodexAuthorizationBrowser { url ->
                openedUrl = url
                openedCount += 1
                CodexAuthorizationPresentation { closedCount += 1 }
            },
        )
        try {
            client.openConversation(ConversationId("thread-1"))
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
            client.eventsChannel.send(AgentEvent.TurnCompleted(ConversationId("thread-1")))
            withTimeout(1_000) { interactions.state.first { it.pending.isEmpty() } }
            assertEquals(3, closedCount)
        } finally {
            interactions.close()
            client.close()
        }
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

private fun formElicitation() = buildJsonObject {
    put("serverName", "example")
    put("threadId", "thread-1")
    put("elicitationId", "elicitation-104")
    put("message", "Choose")
    put("turnId", "turn-1")
    put("mode", "form")
    putJsonObject("requestedSchema") {
        put("type", "object")
        putJsonArray("required") { add(JsonPrimitive("choices")) }
        putJsonObject("properties") {
            putJsonObject("choices") {
                put("type", "array")
                putJsonObject("items") {
                    putJsonArray("enum") {
                        add(JsonPrimitive("one"))
                        add(JsonPrimitive("two"))
                    }
                }
            }
        }
    }
}
