package io.github.ciurlaro.codexmobile.appserver.runtime

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ClientInfo
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeCapabilities
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeParams
import io.github.ciurlaro.codexmobile.agent.CodexPathWorkspaceSelection
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
        val platform = DesktopCodexPlatformSupport(bundle, data)
        val selected = assertIs<CodexWorkspaceResolution.Available>(
            platform.workspaces.select(CodexPathWorkspaceSelection(workspace)),
        )
        val prepared = platform.prepare(selected.workspace)
        val connection = AppServerConnection(
            runtimeFactory = prepared.runtimeFactory,
            initializeParams = InitializeParams(
                clientInfo = ClientInfo("codex_agent_runtime_desktop_test", "0.2.0", "Desktop Runtime Test"),
                capabilities = InitializeCapabilities(
                    experimentalApi = true,
                    mcpServerOpenaiFormElicitation = false,
                ),
            ),
            requestTimeoutMillis = 30_000,
        )
        try {
            val response = connection.ensureStarted()
            assertTrue(response.platformFamily.isNotBlank())
            assertTrue(response.platformOs.isNotBlank())
        } finally {
            connection.shutdown()
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
