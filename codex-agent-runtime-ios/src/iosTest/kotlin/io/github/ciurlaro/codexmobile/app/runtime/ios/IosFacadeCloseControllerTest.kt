package io.github.ciurlaro.codexmobile.app.runtime.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class IosFacadeCloseControllerTest {
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


    private suspend fun <T> CompletableDeferred<T>.awaitTest(): T =
        withTimeout(TEST_TIMEOUT_MILLIS) { await() }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
