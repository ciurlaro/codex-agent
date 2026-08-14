package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.promise
import okio.Path.Companion.toPath

@OptIn(DelicateCoroutinesApi::class)
class NodeCodexRuntimeTest {
    @Test
    fun closeDuringStartClosesNewProcessExactlyOnce() = GlobalScope.promise {
        runNodeEvidenceMethod("closeDuringStartClosesNewProcessExactlyOnce")
    }

    @Test
    fun initializesAndShutsDownOfficialAppServerWhenProvided() = GlobalScope.promise {
        runNodeEvidenceMethod("initializesAndShutsDownOfficialAppServerWhenProvided")
    }

    @Test
    fun rejectsRelativeExecutableBeforeStarting() = GlobalScope.promise {
        runNodeEvidenceMethod("rejectsRelativeExecutableBeforeStarting")
    }

    @Test
    fun rejectsWrongTargetChecksum() = GlobalScope.promise {
        runNodeEvidenceMethod("rejectsWrongTargetChecksum")
    }

    @Test
    fun drainsStdoutInOrderBeforeExit() = GlobalScope.promise {
        val node = js("process.execPath") as String
        val expected = (0 until 200).joinToString(separator = "|") + "|"
        val process = DefaultNodeProcessLauncher.launch(
            NodeLaunchSpec(
                command = node,
                arguments = arrayOf("-e", "for(let i=0;i<200;i++)process.stdout.write(i+'|')"),
                workingDirectory = js("process.cwd()") as String,
                detached = false,
                target = "linuxX64",
            ),
        )

        val actual = process.stdout.onEach { delay(1) }.toList()
            .fold(ByteArray(0)) { result, bytes -> result + bytes }
            .decodeToString()

        assertEquals(expected, actual)
        assertEquals(0, process.exitCode.await())
    }

    @Test
    fun naturalExitRejectsFurtherWrites() = GlobalScope.promise {
        val process = ControlledNodeProcess()
        val runtime = NodeCodexRuntime(
            NodeCodexRuntimeConfiguration("unused".toPath(), "unused".toPath()),
            prepare = { NodeLaunchSpec("unused", emptyArray(), "unused", false, "linuxX64") },
            launcher = NodeProcessLauncher { process },
        )
        val exited = async { runtime.events.first { it is CodexRuntimeEvent.Exited } }
        runtime.start()
        process.exit(0)
        exited.await()

        val error = runCatching { runtime.send(CodexJsonLine("{}")) }.exceptionOrNull()
        assertIs<IllegalStateException>(error)
        runtime.close()
    }

    @Test
    fun detachedLeaderExitStillForcesGroupKill() = GlobalScope.promise {
        val exitCode = CompletableDeferred<Int>()
        val signals = mutableListOf<String>()
        var scheduled: (() -> Unit)? = null
        beginOwnedTermination(
            detached = true,
            exitCode = exitCode,
            signal = signals::add,
            scheduleKill = { action -> scheduled = action; Unit },
            clearKill = {},
        )

        exitCode.complete(0)
        scheduled?.invoke()

        assertEquals(listOf("SIGTERM", "SIGKILL"), signals)
    }

    @Test
    fun evidenceRunnerRequiresExactlyOneArgument() {
        assertIs<IllegalStateException>(runCatching { singleNodeEvidenceArgument(emptyList()) }.exceptionOrNull())
        assertIs<IllegalStateException>(
            runCatching { singleNodeEvidenceArgument(listOf("a", "b")) }.exceptionOrNull(),
        )
        assertEquals("a", singleNodeEvidenceArgument(listOf("a")))
    }
}

private class ControlledNodeProcess : NodeOwnedProcess {
    private val output = Channel<ByteArray>(1)
    override val stdout: Flow<ByteArray> = output.receiveAsFlow()
    override val exitCode = CompletableDeferred<Int>()
    override suspend fun write(line: String): Unit = error("write after exit")
    override fun close() = exit(0)
    fun exit(code: Int) {
        output.close()
        if (!exitCode.isCompleted) exitCode.complete(code)
    }
}
