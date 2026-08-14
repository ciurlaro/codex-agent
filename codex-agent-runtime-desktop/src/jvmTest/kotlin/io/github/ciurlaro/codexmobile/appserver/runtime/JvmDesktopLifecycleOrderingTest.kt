package io.github.ciurlaro.codexmobile.appserver.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath

class JvmDesktopLifecycleOrderingTest {
    @Test
    fun `exit waits for stdout to drain`(): Unit = runBlocking {
        val process = BlockingStdoutProcess()
        val runtime = DesktopCodexRuntimeFactory(
            DesktopCodexRuntimeConfiguration(
                "unused".toPath(), "unused".toPath(), "0".repeat(64), "unused".toPath(),
            ),
            startProcess = { process },
        ).create()
        val firstEvent = async { runtime.events.first() }
        try {
            runtime.start()
            assertTrue(process.stdoutStarted.await(5, TimeUnit.SECONDS))
            delay(100)
            assertFalse(firstEvent.isCompleted, "Process exit overtook unread stdout")
            process.releaseStdout.countDown()
            assertIs<CodexRuntimeEvent.Received>(withTimeout(5_000) { firstEvent.await() })
        } finally {
            runtime.close()
        }
    }
}

private class BlockingStdoutProcess : DesktopProcess {
    val stdoutStarted = CountDownLatch(1)
    val releaseStdout = CountDownLatch(1)
    private val firstRead = AtomicBoolean(true)

    override fun readStdout(buffer: ByteArray): Int {
        if (!firstRead.compareAndSet(true, false)) return 0
        stdoutStarted.countDown()
        check(releaseStdout.await(5, TimeUnit.SECONDS)) { "Timed out waiting to release stdout" }
        val line = "{}\n".encodeToByteArray()
        line.copyInto(buffer)
        return line.size
    }

    override fun readStderr(buffer: ByteArray) = 0
    override fun write(bytes: ByteArray) = Unit
    override fun waitForExit(): Int = 0
    override fun close() { releaseStdout.countDown() }
}
