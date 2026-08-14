package io.github.ciurlaro.codexmobile.appserver.runtime

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal actual suspend fun startDesktopProcess(
    configuration: DesktopCodexRuntimeConfiguration,
): DesktopProcess = JvmProcess.start(configuration)

private class JvmProcess(private val process: Process) : DesktopProcess {
    private val closed = AtomicBoolean()

    override fun readStdout(buffer: ByteArray): Int = process.inputStream.read(buffer).coerceAtLeast(0)

    override fun readStderr(buffer: ByteArray): Int = process.errorStream.read(buffer).coerceAtLeast(0)

    override fun write(bytes: ByteArray) {
        process.outputStream.write(bytes)
        process.outputStream.flush()
    }

    override fun waitForExit(): Int = process.waitFor()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { process.outputStream.close() }
        if (!process.waitFor(GRACEFUL_CLOSE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroy()
            if (!process.waitFor(SUPERVISOR_CLOSE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly().waitFor()
            }
        }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
    }

    companion object {
        fun start(configuration: DesktopCodexRuntimeConfiguration): JvmProcess = JvmProcess(
            ProcessBuilder(
                configuration.processSupervisorExecutable.toString(),
                configuration.appServerExecutable.toString(),
            ).directory(File(configuration.workingDirectory.toString())).start(),
        )

        private const val GRACEFUL_CLOSE_MILLIS = 2_000L
        private const val SUPERVISOR_CLOSE_MILLIS = 3_000L
    }
}
