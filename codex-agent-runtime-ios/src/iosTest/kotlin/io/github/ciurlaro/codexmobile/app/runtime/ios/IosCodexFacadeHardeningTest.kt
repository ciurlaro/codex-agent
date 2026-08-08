package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.AgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class IosCodexFacadeHardeningTest {
    @Test
    fun observerRegistrationCompletesBeforeReturn() = runBlocking {
        val fixture = BroadcastFixture()
        try {
            val received = CompletableDeferred<AgentEvent>()
            val observation = fixture.broadcast.observeEvents { received.complete(it) }
            fixture.upstream.emit(AgentEvent.Authenticated)
            assertEquals(AgentEvent.Authenticated, received.awaitTest())
            observation.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun resubscriptionReceivesBacklogAccumulatedAfterZeroObservers() = runBlocking {
        val fixture = BroadcastFixture()
        try {
            val first = CompletableDeferred<AgentEvent>()
            val firstObservation = fixture.broadcast.observeEvents { first.complete(it) }
            fixture.upstream.emit(AgentEvent.Authenticated)
            first.awaitTest()
            firstObservation.close()

            val queued = authenticationEvent(1)
            fixture.upstream.emit(queued)
            val second = CompletableDeferred<AgentEvent>()
            val secondObservation = fixture.broadcast.observeEvents { second.complete(it) }
            assertEquals(queued, second.awaitTest())
            secondObservation.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun slowObserverDoesNotBlockFastObserverAndOnlySlowSubscriptionOverflows() = runBlocking {
        val fixture = BroadcastFixture()
        try {
            val slowEntered = CompletableDeferred<Unit>()
            val releaseSlow = CompletableDeferred<Unit>()
            val fast = Channel<AgentEvent>(Channel.UNLIMITED)
            val slow = Channel<AgentEvent>(Channel.UNLIMITED)
            val fastObservation = fixture.broadcast.observeEvents { fast.trySend(it) }
            val slowObservation = fixture.broadcast.observeEvents { event ->
                slow.trySend(event)
                if (event == authenticationEvent(-1)) {
                    slowEntered.complete(Unit)
                    runBlocking { releaseSlow.await() }
                }
            }

            fixture.upstream.emit(authenticationEvent(-1))
            slowEntered.awaitTest()
            assertEquals(authenticationEvent(-1), fast.receiveTest())
            assertEquals(authenticationEvent(-1), slow.receiveTest())
            repeat(65) { index ->
                val event = authenticationEvent(index)
                fixture.upstream.emit(event)
                assertEquals(event, fast.receiveTest())
            }
            releaseSlow.complete(Unit)
            repeat(64) { index -> assertEquals(authenticationEvent(index), slow.receiveTest()) }
            val overflow = assertIs<AgentEvent.Failure>(slow.receiveTest())
            assertEquals("ios_observer_overflow", overflow.code)

            fixture.upstream.emit(AgentEvent.Authenticated)
            assertEquals(AgentEvent.Authenticated, fast.receiveTest())
            fastObservation.close()
            slowObservation.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun backlogOverflowIsReportedExplicitly() = runBlocking {
        val fixture = BroadcastFixture()
        try {
            repeat(65) { fixture.upstream.emit(authenticationEvent(it)) }
            val received = CompletableDeferred<AgentEvent>()
            val observation = fixture.broadcast.observeEvents { received.complete(it) }
            val overflow = assertIs<AgentEvent.Failure>(received.awaitTest())
            assertEquals("ios_event_backlog_overflow", overflow.code)
            observation.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun fullBacklogAlwaysPrecedesConcurrentLiveEvent() = runBlocking {
        val fixture = BroadcastFixture()
        try {
            val backlog = List(64, ::authenticationEvent)
            backlog.forEach { fixture.upstream.emit(it) }
            val firstBacklogEventEntered = CompletableDeferred<Unit>()
            val releaseBacklog = CompletableDeferred<Unit>()
            val received = Channel<AgentEvent>(Channel.UNLIMITED)
            val observation = fixture.broadcast.observeEvents { event ->
                if (event == backlog.first()) {
                    firstBacklogEventEntered.complete(Unit)
                    runBlocking { releaseBacklog.await() }
                }
                received.trySend(event)
            }

            firstBacklogEventEntered.awaitTest()
            val live = AgentEvent.Authenticated
            fixture.upstream.emit(live)
            releaseBacklog.complete(Unit)

            backlog.forEach { assertEquals(it, received.receiveTest()) }
            assertEquals(live, received.receiveTest())
            observation.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun cancelingOneObserverDoesNotAffectAnother() = runBlocking {
        val fixture = BroadcastFixture()
        try {
            val canceled = Channel<AgentEvent>(Channel.UNLIMITED)
            val active = Channel<AgentEvent>(Channel.UNLIMITED)
            val canceledObservation = fixture.broadcast.observeEvents { canceled.trySend(it) }
            val activeObservation = fixture.broadcast.observeEvents { active.trySend(it) }
            fixture.upstream.emit(AgentEvent.Authenticated)
            canceled.receiveTest()
            active.receiveTest()
            canceledObservation.close()
            fixture.upstream.emit(authenticationEvent(2))
            assertEquals(authenticationEvent(2), active.receiveTest())
            assertTrue(canceled.tryReceive().isFailure)
            activeObservation.close()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun facadeClosurePublishesClosedStateAndClosesMailboxes() = runBlocking {
        val fixture = BroadcastFixture()
        val states = Channel<IosCodexAuthenticationState>(Channel.UNLIMITED)
        val observation = fixture.broadcast.observeAuthenticationState { states.trySend(it) }
        assertEquals(IosCodexAuthenticationStatus.SIGNED_OUT, states.receiveTest().status)
        fixture.broadcast.markClosed(7, "facade closed")
        val closed = states.receiveTest()
        assertEquals(IosCodexAuthenticationStatus.CLOSED, closed.status)
        assertEquals(7, closed.generation)
        assertEquals("facade closed", closed.terminalReason)
        assertEquals(IosCodexAuthenticationStatus.CLOSED, fixture.broadcast.authenticationState.status)
        fixture.broadcast.joinObservers()
        observation.close()
        fixture.scope.cancel()
    }

    @Test
    fun closeFromEventCallbackNeverJoinsItsOwnHierarchy() = runBlocking {
        val hierarchy = SupervisorJob()
        val scope = CoroutineScope(hierarchy + Dispatchers.Default)
        val upstream = MutableSharedFlow<Unit>()
        val returned = CompletableDeferred<Unit>()
        val controller = controller(cancelHierarchy = hierarchy::cancel, joinHierarchy = hierarchy::join)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            upstream.collect {
                controller.close()
                returned.complete(Unit)
            }
        }
        upstream.emit(Unit)
        returned.awaitTest()
        controller.closeAndJoin()
    }

    @Test
    fun closeFromOperationCompletionNeverJoinsItsOwnHierarchy() = runBlocking {
        val hierarchy = SupervisorJob()
        val scope = CoroutineScope(hierarchy + Dispatchers.Default)
        val returned = CompletableDeferred<Unit>()
        val controller = controller(cancelHierarchy = hierarchy::cancel, joinHierarchy = hierarchy::join)
        scope.launch {
            controller.close()
            returned.complete(Unit)
        }
        returned.awaitTest()
        controller.closeAndJoin()
    }

    @Test
    fun shutdownOrderRejectsPublishesCancelsClosesAndThenJoins() = runBlocking {
        val order = mutableListOf<String>()
        val controller = IosFacadeCloseController(
            rejectNewOperations = { order += "reject" },
            publishClosed = { order += "publish" },
            cancelHierarchy = { order += "cancel" },
            closeClient = { order += "client" },
            joinHierarchy = { order += "join" },
        )
        controller.closeAndJoin()
        assertEquals(listOf("reject", "publish", "cancel", "client", "join"), order)
    }

    @Test
    fun nonCooperativeObserverProducesBoundedJoinTimeout() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val controller = controller(joinHierarchy = { never.await() }, timeoutMillis = 50)
        assertFailsWith<TimeoutCancellationException> { controller.closeAndJoin() }
        Unit
    }

    @Test
    fun clientCloseFailureIsPropagated() = runBlocking {
        val controller = controller(closeClient = { error("native close failed") })
        val failure = assertFailsWith<IllegalStateException> { controller.closeAndJoin() }
        assertEquals("native close failed", failure.message)
    }

    @Test
    fun repeatedConcurrentCloseRunsCleanupOnce() = runBlocking {
        var rejected = 0
        var published = 0
        var canceled = 0
        var clientClosed = 0
        val controller = IosFacadeCloseController(
            rejectNewOperations = { rejected++ },
            publishClosed = { published++ },
            cancelHierarchy = { canceled++ },
            closeClient = { clientClosed++ },
            joinHierarchy = {},
        )
        List(32) { launch(Dispatchers.Default) { controller.close() } }.joinAll()
        controller.closeAndJoin()
        controller.close()
        assertEquals(1, rejected)
        assertEquals(1, published)
        assertEquals(1, canceled)
        assertEquals(1, clientClosed)
    }

    @Test
    fun operationCloseDetachesWithoutCancellation() {
        val job = SupervisorJob()
        var cancellations = 0
        val operation = IosCodexOperation(job, generation = 11) { cancellations++ }
        operation.close()
        assertFalse(job.isCancelled)
        assertEquals(0, cancellations)
        assertEquals(11L, operation.generation)
        job.cancel()
    }

    @Test
    fun directOperationCancellationCancelsJobAndMatchingHandlerOnce() {
        val job = SupervisorJob()
        var cancellations = 0
        val operation = IosCodexOperation(job, generation = 12) { cancellations++ }
        operation.cancel()
        operation.cancel()
        operation.close()
        assertTrue(job.isCancelled)
        assertEquals(1, cancellations)
    }

    private fun controller(
        cancelHierarchy: () -> Unit = {},
        joinHierarchy: suspend () -> Unit = {},
        closeClient: () -> Unit = {},
        timeoutMillis: Long = 5_000,
    ) = IosFacadeCloseController(
        rejectNewOperations = {},
        publishClosed = {},
        cancelHierarchy = cancelHierarchy,
        closeClient = closeClient,
        joinHierarchy = joinHierarchy,
        timeoutMillis = timeoutMillis,
    )

    private fun authenticationEvent(index: Int) =
        AgentEvent.AuthenticationRequired("https://auth.openai.com/event/$index")

    private suspend fun <T> CompletableDeferred<T>.awaitTest(): T =
        withTimeout(TEST_TIMEOUT_MILLIS) { await() }

    private suspend fun <T> Channel<T>.receiveTest(): T =
        withTimeout(TEST_TIMEOUT_MILLIS) { receive() }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}

private class BroadcastFixture : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val upstream = MutableSharedFlow<AgentEvent>()
    val broadcast = IosCodexEventBroadcast(upstream, scope)

    override fun close() {
        broadcast.markClosed(1, "test fixture closed")
        scope.cancel()
    }
}
