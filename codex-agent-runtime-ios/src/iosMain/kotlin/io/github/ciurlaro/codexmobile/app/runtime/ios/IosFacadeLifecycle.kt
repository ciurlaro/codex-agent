package io.github.ciurlaro.codexmobile.app.runtime.ios

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSLock

internal class IosFacadeCloseController(
    private val rejectNewOperations: () -> Unit,
    private val publishClosed: () -> Unit,
    private val cancelHierarchy: () -> Unit,
    private val closeClient: () -> Unit,
    private val joinHierarchy: suspend () -> Unit,
    private val timeoutMillis: Long = CLOSE_TIMEOUT_MILLIS,
) {
    private val closeStarted = CompletableDeferred<Unit>()
    private val closeCompleted = CompletableDeferred<Result<Unit>>()
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun close() {
        if (!closeStarted.complete(Unit)) return
        rejectNewOperations()
        publishClosed()
        cancelHierarchy()
        val clientClose = shutdownScope.async { closeClient() }
        shutdownScope.launch {
            closeCompleted.complete(
                runCatching {
                    withTimeout(timeoutMillis) {
                        val clientFailure = runCatching { clientClose.await() }.exceptionOrNull()
                        val joinFailure = runCatching { joinHierarchy() }.exceptionOrNull()
                        (clientFailure ?: joinFailure)?.let { throw it }
                        Unit
                    }
                },
            )
        }
    }

    suspend fun closeAndJoin() {
        close()
        closeCompleted.await().getOrThrow()
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
    }
}

class IosCodexObservation internal constructor(
    private val closeHandler: () -> Unit,
) : AutoCloseable {
    private val lock = NSLock()
    private var closed = false

    internal constructor(job: Job) : this(job::cancel)

    override fun close() {
        val shouldClose = lock.locked {
            if (closed) false else true.also { closed = true }
        }
        if (shouldClose) closeHandler()
    }
}

class IosCodexOperation internal constructor(
    private val job: Job,
    val generation: Long = 0,
    private val cancellationHandler: () -> Unit = {},
) : AutoCloseable {
    private val lock = NSLock()
    private var closed = false

    fun cancel() {
        val shouldClose = lock.locked {
            if (closed) false else true.also { closed = true }
        }
        if (!shouldClose) return
        job.cancel()
        cancellationHandler()
    }

    override fun close() {
        lock.locked { closed = true }
    }
}

internal inline fun <T> NSLock.locked(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
