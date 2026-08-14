@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.concurrent.atomics.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class DesktopCodexRuntime(
    private val configuration: DesktopCodexRuntimeConfiguration,
    private val validateConfiguration: (DesktopCodexRuntimeConfiguration) -> Unit,
    private val startProcess: suspend (DesktopCodexRuntimeConfiguration) -> DesktopProcess,
) : CodexRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventChannel = Channel<CodexRuntimeEvent>(EVENT_BUFFER_SIZE)
    private val sendMutex = Mutex()
    private val ownership = AtomicReference<ProcessOwnership>(ProcessOwnership.NotStarted)

    override val events: Flow<CodexRuntimeEvent> = eventChannel.receiveAsFlow()

    override suspend fun start() {
        check(ownership.compareAndSet(ProcessOwnership.NotStarted, ProcessOwnership.Starting)) {
            if (ownership.load() === ProcessOwnership.Closed) "Codex runtime is closed"
            else "Codex runtime was already started"
        }
        try {
            val current = withContext(Dispatchers.Default) {
                validateConfiguration(configuration)
                startProcess(configuration)
            }
            if (!ownership.compareAndSet(ProcessOwnership.Starting, ProcessOwnership.Running(current))) {
                current.close()
                error("Codex runtime is closed")
            }
            watch(current)
        } catch (error: Exception) {
            ownership.compareAndSet(ProcessOwnership.Starting, ProcessOwnership.Unavailable)
            eventChannel.trySend(CodexRuntimeEvent.StartFailure(error.visibleMessage()))
            throw error
        }
    }

    override suspend fun send(line: CodexJsonLine) = sendMutex.withLock {
        val current = (ownership.load() as? ProcessOwnership.Running)?.process
        check(current != null) { "Codex App Server is not running" }
        try {
            withContext(Dispatchers.Default) { current.write((line.value + '\n').encodeToByteArray()) }
        } catch (error: Exception) {
            eventChannel.trySend(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
            throw error
        }
    }

    private fun watch(current: DesktopProcess) {
        val stdoutJob = scope.launch {
            try {
                val framer = JsonLineFramer()
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    val count = current.readStdout(buffer)
                    if (count == 0) break
                    check(count > 0) { "Codex app-server stdout read failed" }
                    framer.accept(buffer, count) { line ->
                        eventChannel.send(CodexRuntimeEvent.Received(CodexJsonLine(line)))
                    }
                }
                framer.finish { line ->
                    eventChannel.send(CodexRuntimeEvent.Received(CodexJsonLine(line)))
                }
                if (owns(current)) eventChannel.send(CodexRuntimeEvent.EndOfFile)
            } catch (error: Exception) {
                if (owns(current)) eventChannel.send(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
            }
        }
        scope.launch {
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (current.readStderr(buffer) > 0) Unit
        }
        scope.launch {
            val code = current.waitForExit() ?: return@launch
            stdoutJob.join()
            if (owns(current)) eventChannel.send(CodexRuntimeEvent.Exited(code))
        }
    }

    override fun close() {
        val previous = ownership.exchange(ProcessOwnership.Closed)
        if (previous === ProcessOwnership.Closed) return
        (previous as? ProcessOwnership.Running)?.process?.close()
        scope.cancel()
        eventChannel.close()
    }

    private fun owns(process: DesktopProcess): Boolean =
        (ownership.load() as? ProcessOwnership.Running)?.process === process

    private fun Throwable.visibleMessage(): String =
        message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"

    private companion object {
        const val STREAM_BUFFER_SIZE = 8 * 1024
        const val EVENT_BUFFER_SIZE = 64
    }
}

private sealed interface ProcessOwnership {
    data object NotStarted : ProcessOwnership
    data object Starting : ProcessOwnership
    data object Unavailable : ProcessOwnership
    data object Closed : ProcessOwnership
    class Running(val process: DesktopProcess) : ProcessOwnership
}
