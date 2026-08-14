@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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

internal actual suspend fun startDesktopProcess(
    configuration: DesktopCodexRuntimeConfiguration,
): DesktopProcess = NativeProcess.start(configuration)

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
                configuration.processSupervisorExecutable.toString(),
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
