package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.AgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
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

}

private fun authenticationEvent(index: Int) =
    AgentEvent.AuthenticationRequired("https://auth.openai.com/event/$index")

private suspend fun <T> CompletableDeferred<T>.awaitTest(): T =
    withTimeout(TEST_TIMEOUT_MILLIS) { await() }

private suspend fun <T> Channel<T>.receiveTest(): T =
    withTimeout(TEST_TIMEOUT_MILLIS) { receive() }

private const val TEST_TIMEOUT_MILLIS = 5_000L

private class BroadcastFixture : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val upstream = MutableSharedFlow<AgentEvent>()
    val broadcast = IosCodexEventBroadcast(upstream, scope)

    override fun close() {
        broadcast.markClosed(1, "test fixture closed")
        scope.cancel()
    }
}
