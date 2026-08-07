package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.AgentEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class IosCodexFacadeHardeningTest {
    @Test
    fun closeFromEventCallbackDoesNotJoinItself() = runBlocking {
        val hierarchy = SupervisorJob()
        val scope = CoroutineScope(hierarchy + Dispatchers.Default)
        val upstream = MutableSharedFlow<Unit>()
        val returned = CompletableDeferred<Unit>()
        var clientCloseCount = 0
        lateinit var controller: IosFacadeCloseController
        controller = IosFacadeCloseController(
            cancelHierarchy = hierarchy::cancel,
            joinHierarchy = { hierarchy.join() },
            closeClient = { clientCloseCount++ },
        )
        scope.launch {
            upstream.collect {
                controller.close()
                returned.complete(Unit)
            }
        }
        delay(SUBSCRIPTION_SETTLE_MILLIS)

        upstream.emit(Unit)
        withTimeout(TEST_TIMEOUT_MILLIS) { returned.await() }
        withTimeout(TEST_TIMEOUT_MILLIS) { controller.closeAndJoin() }

        assertEquals(1, clientCloseCount)
    }

    @Test
    fun closeFromOperationCompletionDoesNotJoinItself() = runBlocking {
        val hierarchy = SupervisorJob()
        val scope = CoroutineScope(hierarchy + Dispatchers.Default)
        val returned = CompletableDeferred<Unit>()
        var clientCloseCount = 0
        val controller = IosFacadeCloseController(
            cancelHierarchy = hierarchy::cancel,
            joinHierarchy = { hierarchy.join() },
            closeClient = { clientCloseCount++ },
        )

        scope.launch {
            controller.close()
            returned.complete(Unit)
        }
        withTimeout(TEST_TIMEOUT_MILLIS) { returned.await() }
        withTimeout(TEST_TIMEOUT_MILLIS) { controller.closeAndJoin() }

        assertEquals(1, clientCloseCount)
    }

    @Test
    fun externalCloseReturnsBeforeJoining() = runBlocking {
        val releaseJoin = CompletableDeferred<Unit>()
        val clientClosed = CompletableDeferred<Unit>()
        val controller = IosFacadeCloseController(
            cancelHierarchy = {},
            joinHierarchy = { releaseJoin.await() },
            closeClient = { clientClosed.complete(Unit) },
        )

        controller.close()
        assertFalse(clientClosed.isCompleted)
        releaseJoin.complete(Unit)
        withTimeout(TEST_TIMEOUT_MILLIS) { controller.closeAndJoin() }

        assertTrue(clientClosed.isCompleted)
    }

    @Test
    fun repeatedConcurrentCloseRunsCleanupOnce() = runBlocking {
        var cancellationCount = 0
        var clientCloseCount = 0
        val controller = IosFacadeCloseController(
            cancelHierarchy = { cancellationCount++ },
            joinHierarchy = {},
            closeClient = { clientCloseCount++ },
        )

        List(32) { launch(Dispatchers.Default) { controller.close() } }.joinAll()
        withTimeout(TEST_TIMEOUT_MILLIS) { controller.closeAndJoin() }
        controller.close()

        assertEquals(1, cancellationCount)
        assertEquals(1, clientCloseCount)
    }

    @Test
    fun eventsBeforeTheFirstObserverAreDeliveredInOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val upstream = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 128)
        val broadcast = IosCodexEventBroadcast(upstream, scope)
        repeat(70) { upstream.emit(authenticationEvent(it)) }
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (broadcast.authenticationState.pendingSignInUrl != authenticationUrl(69)) delay(1)
        }
        val received = mutableListOf<String>()

        val observation = broadcast.observeEvents { event ->
            received += (event as AgentEvent.AuthenticationRequired).signInUrl
        }
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (received.size < 64) delay(1)
        }

        assertEquals((6 until 70).map(::authenticationUrl), received)
        observation.close()
        scope.cancel()
    }

    @Test
    fun slowObserverDropsOnlyItsOldestPendingEvents() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val upstream = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
        val broadcast = IosCodexEventBroadcast(upstream, scope)
        val slowEntered = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val fast = mutableListOf<String>()
        val slow = mutableListOf<String>()
        val fastObservation = broadcast.observeEvents { event ->
            fast += (event as AgentEvent.AuthenticationRequired).signInUrl
        }
        val slowObservation = broadcast.observeEvents { event ->
            val url = (event as AgentEvent.AuthenticationRequired).signInUrl
            slow += url
            if (url == authenticationUrl(-1)) {
                slowEntered.complete(Unit)
                runBlocking { releaseSlow.await() }
            }
        }
        delay(SUBSCRIPTION_SETTLE_MILLIS)

        upstream.emit(authenticationEvent(-1))
        withTimeout(TEST_TIMEOUT_MILLIS) { slowEntered.await() }
        repeat(100) { upstream.emit(authenticationEvent(it)) }
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (fast.size < 101) delay(1)
        }
        releaseSlow.complete(Unit)
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (slow.size < 65) delay(1)
        }

        assertEquals(listOf(authenticationUrl(-1)) + (36 until 100).map(::authenticationUrl), slow)
        assertEquals(listOf(authenticationUrl(-1)) + (0 until 100).map(::authenticationUrl), fast)
        fastObservation.close()
        slowObservation.close()
        scope.cancel()
    }

    @Test
    fun cancelingOneObserverDoesNotAffectAnother() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val upstream = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 8)
        val broadcast = IosCodexEventBroadcast(upstream, scope)
        var canceledCount = 0
        var activeCount = 0
        val canceled = broadcast.observeEvents { canceledCount++ }
        val active = broadcast.observeEvents { activeCount++ }
        delay(SUBSCRIPTION_SETTLE_MILLIS)

        upstream.emit(AgentEvent.Authenticated)
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (activeCount < 1 || canceledCount < 1) delay(1)
        }
        canceled.close()
        upstream.emit(AgentEvent.Authenticated)
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (activeCount < 2) delay(1)
        }

        assertEquals(1, canceledCount)
        assertEquals(2, activeCount)
        active.close()
        scope.cancel()
    }

    private fun authenticationEvent(index: Int) =
        AgentEvent.AuthenticationRequired(authenticationUrl(index))

    private fun authenticationUrl(index: Int) = "https://auth.openai.com/event/$index"

    private companion object {
        const val SUBSCRIPTION_SETTLE_MILLIS = 50L
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
