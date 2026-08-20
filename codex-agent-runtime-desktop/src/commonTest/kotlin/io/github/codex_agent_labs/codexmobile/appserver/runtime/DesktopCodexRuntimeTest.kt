package io.github.codex_agent_labs.codexmobile.appserver.runtime

import io.github.codex_agent_labs.codexmobile.agent.CodexClientInfo
import io.github.codex_agent_labs.codexmobile.agent.CodexHost
import io.github.codex_agent_labs.codexmobile.agent.CodexHostState
import io.github.codex_agent_labs.codexmobile.agent.CodexPathWorkspaceSelection
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceResolution
import io.github.codex_agent_labs.codexmobile.agent.runtime.DesktopCodexPlatform
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath

class DesktopCodexRuntimeTest {
    @Test
    fun closeDuringStartClosesNewProcessExactlyOnce(): Unit = runBlocking {
        val process = FakeDesktopProcess()
        val processStarted = CompletableDeferred<Unit>()
        val releaseStart = CompletableDeferred<Unit>()
        val runtime = DesktopCodexRuntimeFactory(
            DesktopCodexRuntimeConfiguration(
                "unused".toPath(), "unused".toPath(), "0".repeat(64), "unused".toPath(),
            ),
        ) {
            processStarted.complete(Unit)
            releaseStart.await()
            process
        }.create()
        val start = async { runCatching { runtime.start() }.exceptionOrNull() }

        processStarted.await()
        runtime.close()
        releaseStart.complete(Unit)

        assertIs<IllegalStateException>(start.await())
        assertEquals(1, process.closeCount)
    }

    @Test
    fun rejectsRelativeExecutableBeforeStarting(): Unit = runBlocking {
        val runtime = DesktopCodexRuntimeFactory(
            DesktopCodexRuntimeConfiguration(
                appServerExecutable = "codex-app-server".toPath(),
                processSupervisorExecutable = "codex-process-supervisor".toPath(),
                processSupervisorSha256 = "0".repeat(64),
                workingDirectory = ".".toPath(),
            ),
        ).create()

        assertFailsWith<IllegalStateException> { runtime.start() }
        runtime.close()
    }

    @Test
    fun rejectsWrongTargetChecksum(): Unit = runBlocking {
        val temporary = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "codex-agent-desktop-wrong-hash-${Random.nextLong().toString(16)}"
        FileSystem.SYSTEM.createDirectories(temporary)
        val directory = FileSystem.SYSTEM.canonicalize(temporary)
        val distribution = desktopCodexDistribution(currentDesktopTarget())
        val executable = directory / distribution.executableName
        val supervisor = directory / distribution.supervisorExecutableName
        FileSystem.SYSTEM.write(executable) { writeUtf8("not an app server") }
        FileSystem.SYSTEM.write(supervisor) { writeUtf8("not a supervisor") }
        val runtime = DesktopCodexRuntimeFactory(
            DesktopCodexRuntimeConfiguration(executable, supervisor, supervisor.sha256(), directory),
        ).create()

        try {
            val error = assertFailsWith<IllegalStateException> { runtime.start() }
            assertContains(error.message.orEmpty(), "checksum")
        } finally {
            runtime.close()
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
        }
    }

    @Test
    fun initializesAndShutsDownOfficialAppServerWhenProvided(): Unit = runBlocking {
        val bundle = desktopTestEnvironment("CODEX_AGENT_RUNTIME_BUNDLE_DIRECTORY")
            ?.toPath() ?: return@runBlocking
        val data = checkNotNull(desktopTestEnvironment("CODEX_AGENT_RUNTIME_DATA_DIRECTORY")).toPath()
        val workspace = checkNotNull(desktopTestEnvironment("CODEX_AGENT_WORKSPACE"))
        val platform = DesktopCodexPlatform(bundle, data)
        val selected = assertIs<CodexWorkspaceResolution.Available>(
            platform.workspaceStore.select(CodexPathWorkspaceSelection(workspace)),
        )
        val host = CodexHost(
            platform,
            this,
            CodexClientInfo("desktop_runtime_evidence", "Desktop Runtime Evidence", "0.2.0"),
        )
        try {
            host.start()
            val ready = assertIs<CodexHostState.Ready>(host.state.value)
            assertEquals(selected.workspace, ready.agent.workspace)
        } finally {
            host.close()
        }
    }
}

internal expect fun desktopTestEnvironment(name: String): String?

private class FakeDesktopProcess : DesktopProcess {
    var closeCount = 0

    override fun readStdout(buffer: ByteArray) = 0
    override fun readStderr(buffer: ByteArray) = 0
    override fun write(bytes: ByteArray) = Unit
    override fun waitForExit(): Int? = null
    override fun close() { closeCount++ }
}
