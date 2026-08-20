package io.github.codex_agent_labs.codexmobile.agent

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class CodexConversationTest {
    @Test
    fun callerCancellationLeavesTurnAndRefreshInRecoverableFailedState(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-cancelled") }
                })
                "turn/start", "thread/read" -> Unit
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 5_000)
        val conversation = testConversation(client, this)
        try {
            conversation.open()
            val sending = async { conversation.send(AgentTurnRequest("hello")) }
            withTimeout(1_000) {
                conversation.state.first { it.status == AgentConversationStatus.STARTING_TURN }
            }
            sending.cancelAndJoin()
            assertEquals(AgentConversationStatus.FAILED, conversation.state.value.status)
            assertEquals("turn_start_failed", conversation.state.value.failure?.code)

            val refreshing = async { conversation.reload() }
            withTimeout(1_000) {
                conversation.state.first { it.status == AgentConversationStatus.RELOADING }
            }
            refreshing.cancelAndJoin()
            assertEquals(AgentConversationStatus.FAILED, conversation.state.value.status)
            assertEquals("reload_failed", conversation.state.value.failure?.code)
        } finally {
            conversation.close()
            client.close()
        }
    }

    @Test
    fun cancellationWaitsForTheTurnIdWhenProgressArrivesBeforeTheStartResponse(): Unit = runBlocking {
        val releaseStart = CountDownLatch(1)
        val interruptReceived = CountDownLatch(1)
        val interruptCount = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-early-progress") }
                })
                "turn/start" -> {
                    server.notify(
                        "item/agentMessage/delta",
                        buildJsonObject {
                            put("threadId", "thread-early-progress")
                            put("turnId", "turn-early-progress")
                            put("itemId", "item-1")
                            put("delta", "working")
                        },
                    )
                    check(releaseStart.await(1, TimeUnit.SECONDS))
                    server.respond(message.id, buildJsonObject {
                        putJsonObject("turn") { put("id", "turn-early-progress") }
                    })
                }
                "turn/interrupt" -> {
                    interruptCount.incrementAndGet()
                    interruptReceived.countDown()
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val conversation = testConversation(client, this)
        try {
            conversation.open()
            val sending = async { conversation.send(AgentTurnRequest("hello")) }
            withTimeout(1_000) {
                conversation.state.first { it.status == AgentConversationStatus.RUNNING_TURN }
            }

            conversation.cancelTurn()
            assertEquals(AgentConversationStatus.CANCELLING_TURN, conversation.state.value.status)
            assertEquals(0, interruptCount.get())

            releaseStart.countDown()
            sending.await()
            assertTrue(interruptReceived.await(1, TimeUnit.SECONDS))
            conversation.cancelTurn()
            assertEquals(1, interruptCount.get())
        } finally {
            releaseStart.countDown()
            conversation.close()
            client.close()
        }
    }

    @Test
    fun pendingCancellationDoesNotInterruptAfterTurnStartupFails(): Unit = runBlocking {
        val startRequest = CompletableDeferred<Long>()
        val interruptCount = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-failed-start") }
                })
                "turn/start" -> startRequest.complete(checkNotNull(message.id))
                "turn/interrupt" -> {
                    interruptCount.incrementAndGet()
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val conversation = testConversation(client, this)
        try {
            conversation.open()
            val sending = async(start = CoroutineStart.UNDISPATCHED) {
                assertFailsWith<CodexOperationException> {
                    conversation.send(AgentTurnRequest("hello"))
                }
            }
            val requestId = withTimeout(1_000) { startRequest.await() }

            conversation.cancelTurn()
            process.sendRaw(buildJsonObject {
                put("id", requestId)
                putJsonObject("error") {
                    put("code", -32000)
                    put("message", "startup failed")
                }
            }.toString())

            sending.await()
            assertEquals(AgentConversationStatus.FAILED, conversation.state.value.status)
            assertEquals(0, interruptCount.get())
        } finally {
            conversation.close()
            client.close()
        }
    }

    @Test
    fun liveTurnTextAndHookActivityAreStrictlyBounded(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-bounds") }
                })
                "turn/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("turn") { put("id", "turn-bounds") }
                })
                "turn/interrupt" -> server.respond(message.id, buildJsonObject {})
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val conversation = testConversation(client, this)
        try {
            val conversationId = conversation.open()
            conversation.send(AgentTurnRequest("hello"))
            conversation.process(AgentEvent.TextDelta(conversationId, "x".repeat(256 * 1024 + 1)))
            repeat(25) { index ->
                conversation.process(
                    AgentEvent.HookActivityChanged(
                        conversationId,
                        AgentHookActivity(
                            id = "hook-$index",
                            eventName = "preToolUse",
                            handlerType = "command",
                            status = AgentHookRunStatus.RUNNING,
                        ),
                    ),
                )
            }

            val progress = conversation.state.value.turnProgress
            assertEquals(256 * 1024, progress.text.length)
            assertTrue(progress.isTruncated)
            assertEquals(20, progress.hookActivities.size)
            assertEquals("hook-5", progress.hookActivities.first().id)
            assertEquals("hook-24", progress.hookActivities.last().id)
        } finally {
            conversation.close()
            client.close()
        }
    }

    @Test
    fun reducesOnlyItsLiveTurnAndReconcilesOnceWithoutLosingAFailedDraft(): Unit = runBlocking {
        val reads = AtomicInteger()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-1") }
                })
                "turn/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("turn") { put("id", "turn-${reads.get() + 1}") }
                })
                "thread/read" -> if (reads.incrementAndGet() == 2) {
                    server.sendRaw(buildJsonObject {
                        put("id", message.id)
                        putJsonObject("error") {
                            put("code", -32000)
                            put("message", "offline")
                        }
                    }.toString())
                } else {
                    server.respond(message.id, canonicalConversation())
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        val conversation = testConversation(client, this)
        try {
            val conversationId = conversation.open()
            assertEquals(ConversationId("thread-1"), conversationId)
            assertEquals(AgentConversationStatus.READY, conversation.state.value.status)
            withTimeout(1_000) { conversation.state.first { it.model == "test" } }

            conversation.send(AgentTurnRequest("hello"))
            conversation.process(AgentEvent.TextDelta(ConversationId("other"), "ignore"))
            conversation.process(AgentEvent.TextDelta(conversationId, "answer"))
            conversation.process(AgentEvent.TextDelta(conversationId, "note", isCommentary = true))
            conversation.process(AgentEvent.ReasoningSummaryDelta(conversationId, "thinking", "reason-1", 0))
            conversation.process(AgentEvent.PlanDelta(conversationId, "plan", "plan-1"))
            conversation.process(
                AgentEvent.PlanUpdated(
                    conversationId,
                    AgentPlanProgress(steps = listOf(AgentPlanStep("ship", AgentPlanStepStatus.IN_PROGRESS))),
                ),
            )
            conversation.process(AgentEvent.ShellOutputDelta(conversationId, "output"))
            conversation.process(AgentEvent.ShellCommandCompleted(conversationId, 0))
            conversation.process(AgentEvent.WorkActivityChanged(conversationId, AgentWorkActivity.WRITING_FILES))
            conversation.process(
                AgentEvent.HookActivityChanged(
                    conversationId,
                    AgentHookActivity(
                        id = "hook-1",
                        eventName = "preToolUse",
                        handlerType = "command",
                        status = AgentHookRunStatus.RUNNING,
                    ),
                ),
            )

            val draft = conversation.state.value.turnProgress
            assertEquals("answer", draft.text)
            assertEquals("note", draft.commentary)
            assertEquals("thinking", draft.reasoning)
            assertEquals("plan", draft.plan)
            assertEquals("ship", draft.planProgress?.steps?.single()?.text)
            assertEquals("output", draft.shellOutput)
            assertEquals(0, draft.shellExitCode)
            assertEquals(AgentWorkActivity.WRITING_FILES, draft.workActivity)
            assertEquals("hook-1", draft.hookActivities.single().id)

            process.notify("turn/completed", completedTurn("turn-1"))
            withTimeout(1_000) {
                conversation.state.first { reads.get() == 1 && it.status == AgentConversationStatus.READY }
            }
            assertEquals("Canonical answer", conversation.state.value.conversation?.messages?.last()?.text)
            assertEquals(AgentTurnProgress(), conversation.state.value.turnProgress)
            conversation.process(AgentEvent.TurnCompleted(conversationId))
            assertEquals(1, reads.get())

            conversation.send(AgentTurnRequest("again"))
            conversation.process(AgentEvent.TextDelta(conversationId, "keep me"))
            process.notify("turn/completed", completedTurn("turn-2"))
            withTimeout(1_000) {
                conversation.state.first { reads.get() == 2 && it.status == AgentConversationStatus.FAILED }
            }
            assertEquals(2, reads.get())
            assertEquals(AgentConversationStatus.FAILED, conversation.state.value.status)
            assertEquals("keep me", conversation.state.value.turnProgress.text)
            assertTrue(assertNotNull(conversation.state.value.failure).isRecoverable)

            conversation.reload()
            assertEquals(3, reads.get())
            assertEquals(AgentConversationStatus.READY, conversation.state.value.status)
            assertEquals(AgentTurnProgress(), conversation.state.value.turnProgress)
        } finally {
            conversation.close()
            client.close()
        }
    }
}

private fun testConversation(client: CodexAgentClient, scope: CoroutineScope): CodexConversation =
    CodexConversation(
        client = client,
        scope = scope,
        workingDirectory = "/workspace",
        features = CodexRuntimeFeature.entries.toSet(),
        onClose = CodexConversation::closeOwned,
    )

private fun completedTurn(turnId: String) = buildJsonObject {
    put("threadId", "thread-1")
    putJsonObject("turn") {
        put("id", turnId)
        put("status", "completed")
    }
}

private fun conversationApprovalRequest() = buildJsonObject {
    put("itemId", "item-1")
    put("startedAtMs", 1)
    put("threadId", "thread-1")
    put("turnId", "turn-1")
    put("command", "git status")
}

private fun canonicalConversation() = buildJsonObject {
    put(
        "thread",
        thread(
            id = "thread-1",
            name = "Canonical",
            preview = "Canonical answer",
            updatedAt = 10,
            turns = buildJsonArray {
                add(buildJsonObject {
                    put("id", "turn-1")
                    put("status", "completed")
                    put("items", buildJsonArray {
                        add(plainUserMessage("user-1", "client-1", "hello"))
                        add(buildJsonObject {
                            put("id", "codex-1")
                            put("type", "agentMessage")
                            put("phase", "final_answer")
                            put("text", "Canonical answer")
                        })
                    })
                })
            },
        ),
    )
}
