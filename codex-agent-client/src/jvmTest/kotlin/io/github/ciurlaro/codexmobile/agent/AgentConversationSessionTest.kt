package io.github.ciurlaro.codexmobile.agent

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class AgentConversationSessionTest {
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
        val interactions = AgentInteractionSession(client, this)
        val conversation = AgentConversationSession(client, interactions, this)
        try {
            val sessionId = conversation.open()
            assertEquals(SessionId("thread-1"), sessionId)
            assertEquals(AgentConversationStatus.IDLE, conversation.state.value.status)
            withTimeout(1_000) { conversation.state.first { it.model == "test" } }

            process.request(201, "item/commandExecution/requestApproval", conversationApprovalRequest())
            withTimeout(1_000) {
                conversation.state.first { it.pendingInteractions.singleOrNull()?.requestId == "201" }
            }
            interactions.resolveApproval("201", AgentApprovalDecision.DECLINE)
            withTimeout(1_000) { conversation.state.first { it.pendingInteractions.isEmpty() } }

            conversation.send(AgentTurnRequest("hello"))
            conversation.process(AgentEvent.TextDelta(SessionId("other"), "ignore"))
            conversation.process(AgentEvent.TextDelta(sessionId, "answer"))
            conversation.process(AgentEvent.TextDelta(sessionId, "note", isCommentary = true))
            conversation.process(AgentEvent.ReasoningSummaryDelta(sessionId, "thinking", "reason-1", 0))
            conversation.process(AgentEvent.PlanDelta(sessionId, "plan", "plan-1"))
            conversation.process(
                AgentEvent.PlanUpdated(
                    sessionId,
                    AgentPlanProgress(steps = listOf(AgentPlanStep("ship", AgentPlanStepStatus.IN_PROGRESS))),
                ),
            )
            conversation.process(AgentEvent.ShellOutputDelta(sessionId, "output"))
            conversation.process(AgentEvent.ShellCommandCompleted(sessionId, 0))
            conversation.process(AgentEvent.WorkActivityChanged(sessionId, AgentWorkActivity.WRITING_FILES))
            conversation.process(
                AgentEvent.HookActivityChanged(
                    sessionId,
                    AgentHookActivity(
                        id = "hook-1",
                        eventName = "preToolUse",
                        handlerType = "command",
                        status = AgentHookRunStatus.RUNNING,
                    ),
                ),
            )

            val draft = conversation.state.value.draft
            assertEquals("answer", draft.text)
            assertEquals("note", draft.commentary)
            assertEquals("thinking", draft.reasoning)
            assertEquals("plan", draft.plan)
            assertEquals("ship", draft.planProgress?.steps?.single()?.text)
            assertEquals("output", draft.shellOutput)
            assertEquals(0, draft.shellExitCode)
            assertEquals(AgentWorkActivity.WRITING_FILES, draft.workActivity)
            assertEquals("hook-1", draft.hookActivities.single().id)

            process.notify("turn/completed", completedTurn())
            withTimeout(1_000) {
                conversation.state.first { reads.get() == 1 && it.status == AgentConversationStatus.IDLE }
            }
            assertEquals("Canonical answer", conversation.state.value.conversation?.messages?.last()?.text)
            assertEquals(AgentConversationDraft(), conversation.state.value.draft)
            conversation.process(AgentEvent.TurnCompleted(sessionId))
            assertEquals(1, reads.get())

            conversation.send(AgentTurnRequest("again"))
            conversation.process(AgentEvent.TextDelta(sessionId, "keep me"))
            process.notify("turn/completed", completedTurn())
            withTimeout(1_000) {
                conversation.state.first { reads.get() == 2 && it.status == AgentConversationStatus.FAILED }
            }
            assertEquals(2, reads.get())
            assertEquals(AgentConversationStatus.FAILED, conversation.state.value.status)
            assertEquals("keep me", conversation.state.value.draft.text)
            assertTrue(assertNotNull(conversation.state.value.error).recoverable)

            conversation.refresh()
            assertEquals(3, reads.get())
            assertEquals(AgentConversationStatus.IDLE, conversation.state.value.status)
            assertEquals(AgentConversationDraft(), conversation.state.value.draft)
        } finally {
            conversation.close()
            interactions.close()
            client.close()
        }
        assertEquals(AgentConversationStatus.CLOSED, conversation.state.value.status)
    }
}

private fun completedTurn() = buildJsonObject {
    put("threadId", "thread-1")
    putJsonObject("turn") {
        put("id", "turn-1")
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
