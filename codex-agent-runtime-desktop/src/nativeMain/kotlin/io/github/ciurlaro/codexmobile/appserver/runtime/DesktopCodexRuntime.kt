@file:OptIn(
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package io.github.ciurlaro.codexmobile.appserver.runtime

import codex_desktop.codex_process
import codex_desktop.codex_process_close
import codex_desktop.codex_process_read
import codex_desktop.codex_process_release
import codex_desktop.codex_process_start
import codex_desktop.codex_process_terminate
import codex_desktop.codex_process_wait
import codex_desktop.codex_process_write
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
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
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.Volatile
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.buffer

data class DesktopCodexRuntimeConfiguration(
    val appServerExecutable: Path,
    val workingDirectory: Path,
)

class DesktopCodexRuntimeFactory private constructor(
    private val configuration: DesktopCodexRuntimeConfiguration,
    private val validateConfiguration: (DesktopCodexRuntimeConfiguration) -> Unit,
    private val startProcess: suspend (DesktopCodexRuntimeConfiguration) -> DesktopProcess,
) : CodexRuntimeFactory {
    constructor(configuration: DesktopCodexRuntimeConfiguration) : this(
        configuration,
        ::validateDesktopConfiguration,
        { NativeProcess.start(it) },
    )

    internal constructor(
        configuration: DesktopCodexRuntimeConfiguration,
        startProcess: suspend (DesktopCodexRuntimeConfiguration) -> DesktopProcess,
    ) : this(configuration, {}, startProcess)

    override fun create(): CodexRuntime = DesktopCodexRuntime(
        configuration,
        validateConfiguration,
        startProcess,
    )
}

private class DesktopCodexRuntime(
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
            withContext(Dispatchers.Default) { validateConfiguration(configuration) }
            val current = startProcess(configuration)
            if (!ownership.compareAndSet(ProcessOwnership.Starting, ProcessOwnership.Running(current))) {
                current.close()
                error("Codex runtime is closed")
            }
            watch(current)
        } catch (error: Exception) {
            eventChannel.trySend(CodexRuntimeEvent.StartFailure(error.visibleMessage()))
            closeResources()
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
        scope.launch {
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
                if (owns(current)) {
                    eventChannel.send(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
                }
            }
        }
        scope.launch {
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (current.readStderr(buffer) > 0) Unit
        }
        scope.launch {
            val code = current.waitForExit() ?: return@launch
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

    private fun closeResources() {
        while (true) {
            val current = ownership.load()
            if (current !is ProcessOwnership.Running) return
            if (ownership.compareAndSet(current, ProcessOwnership.Unavailable)) {
                current.process.close()
                return
            }
        }
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

internal interface DesktopProcess {
    fun readStdout(buffer: ByteArray): Int
    fun readStderr(buffer: ByteArray): Int
    fun write(bytes: ByteArray)
    fun waitForExit(): Int?
    fun close()
}

private data class NativeProcess(
    val stdinWrite: Long,
    val stdoutRead: Long,
    val stderrRead: Long,
    val process: Long,
    val job: Long,
) : DesktopProcess {
    override fun readStdout(buffer: ByteArray): Int = read(stdoutRead, buffer)

    override fun readStderr(buffer: ByteArray): Int = read(stderrRead, buffer)

    override fun write(bytes: ByteArray) {
        val result = bytes.usePinned { pinned ->
            codex_process_write(stdinWrite, pinned.addressOf(0), bytes.size.toULong())
        }
        check(result == 0) { "Codex app-server stdin write failed" }
    }

    override fun waitForExit(): Int? = memScoped {
        val exitCode = alloc<IntVar>()
        if (codex_process_wait(process, exitCode.ptr) == 0) exitCode.value else null
    }

    override fun close() {
        codex_process_close(stdinWrite)
        codex_process_terminate(process, job)
        codex_process_close(stdoutRead)
        codex_process_close(stderrRead)
        codex_process_release(process, job)
    }

    private fun read(handle: Long, buffer: ByteArray): Int = buffer.usePinned { pinned ->
        codex_process_read(handle, pinned.addressOf(0), buffer.size.toULong()).toInt()
    }

    companion object {
        fun start(configuration: DesktopCodexRuntimeConfiguration): NativeProcess = memScoped {
            val output = alloc<codex_process>()
            val error = allocArray<ByteVar>(ERROR_CAPACITY)
            error[0] = 0
            val result = codex_process_start(
                configuration.appServerExecutable.toString(),
                configuration.workingDirectory.toString(),
                output.ptr,
                error,
                ERROR_CAPACITY.toULong(),
            )
            check(result == 0) { error.toKString().ifBlank { "Unable to start Codex app server" } }
            NativeProcess(
                stdinWrite = output.stdin_write,
                stdoutRead = output.stdout_read,
                stderrRead = output.stderr_read,
                process = output.process,
                job = output.job,
            )
        }

        private const val ERROR_CAPACITY = 512
    }
}

private fun validateDesktopConfiguration(configuration: DesktopCodexRuntimeConfiguration) {
    val executable = configuration.appServerExecutable
    val workingDirectory = configuration.workingDirectory
    check(executable.isAbsolute) { "Codex app-server path must be absolute" }
    check(workingDirectory.isAbsolute) { "Desktop working-directory path must be absolute" }
    check(executable.isRegularFile()) { "Codex app server does not exist" }
    check(FileSystem.SYSTEM.metadataOrNull(workingDirectory)?.isDirectory == true) {
        "Desktop working directory does not exist"
    }
    val distribution = currentDesktopCodexDistribution()
    check(executable.sha256() == distribution.binarySha256) {
        "Codex app-server checksum does not match ${distribution.target}"
    }
}

private fun Path.isRegularFile(): Boolean = FileSystem.SYSTEM.metadataOrNull(this)?.isRegularFile == true

private fun Path.sha256(): String {
    val hashingSource = HashingSource.sha256(FileSystem.SYSTEM.source(this))
    val buffered = hashingSource.buffer()
    try {
        buffered.readAll(blackholeSink())
    } finally {
        buffered.close()
    }
    return hashingSource.hash.hex()
}
