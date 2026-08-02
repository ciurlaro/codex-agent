package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.agent.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal class BuiltInToolsMutationTest : BuiltInToolsProtocolTestBase() {
    @Test
    fun typedMutationsRequireOneManualApprovalIncludingUnderAutoReview(): Unit = runBlocking {
        val dispatches = AtomicInteger()
        val secondBoundaryRejected = java.util.concurrent.atomic.AtomicBoolean()
        val response = CountDownLatch(1)
        val process = mutationRuntime(response)
        val client = CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = object : BuiltInToolDispatcher {
                override fun definitions() = TEST_DEFINITIONS

                override suspend fun execute(call: BuiltInToolCall) = BuiltInToolResult.text("unused", false)

                override suspend fun execute(
                    call: BuiltInToolCall,
                    beforeMutationDispatch: suspend () -> Unit,
                ): BuiltInToolResult {
                    beforeMutationDispatch()
                    dispatches.incrementAndGet()
                    secondBoundaryRejected.set(runCatching { beforeMutationDispatch() }.isFailure)
                    return BuiltInToolResult.text("sent")
                }
            },
        )
        try {
            val session = client.openSession(
                settings = AgentRuntimeSettings(AgentApprovalPreset.ASK_ME, workingDirectory = "/workspace"),
            )
            val approval = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.ApprovalRequested>().first() }
            }
            client.sendTurn(
                session,
                AgentTurnRequest(
                    "send",
                    approvalPreset = AgentApprovalPreset.ASK_ME,
                    workingDirectory = "/workspace",
                ),
            )
            val event = approval.await()
            assertEquals(0, dispatches.get())
            client.resolveApproval(event.requestId, AgentApprovalDecision.ACCEPT)
            assertTrue(response.await(1, TimeUnit.SECONDS))
            assertEquals(1, dispatches.get())
            assertTrue(secondBoundaryRejected.get())
            assertFailsWith<IllegalStateException> {
                client.resolveApproval(event.requestId, AgentApprovalDecision.ACCEPT)
            }
        } finally {
            client.close()
        }

        val autoDispatches = AtomicInteger()
        val autoResponse = CountDownLatch(1)
        val autoProcess = mutationRuntime(autoResponse)
        CodexAgentClient(
            runtimeFactory = { autoProcess },
            requestTimeoutMillis = 1_000,
            builtInToolDispatcher = dispatcher {
                autoDispatches.incrementAndGet()
                BuiltInToolResult.text("must not run")
            },
        ).use { autoClient ->
            val session = autoClient.openSession(
                settings = AgentRuntimeSettings(AgentApprovalPreset.AUTO_REVIEW, workingDirectory = "/workspace"),
            )
            val approval = async {
                withTimeout(1_000) { autoClient.events.filterIsInstance<AgentEvent.ApprovalRequested>().first() }
            }
            autoClient.sendTurn(
                session,
                AgentTurnRequest(
                    "send",
                    approvalPreset = AgentApprovalPreset.AUTO_REVIEW,
                    workingDirectory = "/workspace",
                ),
            )
            val event = approval.await()
            assertEquals(0, autoDispatches.get())
            autoClient.resolveApproval(event.requestId, AgentApprovalDecision.ACCEPT)
            assertTrue(autoResponse.await(1, TimeUnit.SECONDS))
            assertEquals(1, autoDispatches.get())
        }
    }

}
