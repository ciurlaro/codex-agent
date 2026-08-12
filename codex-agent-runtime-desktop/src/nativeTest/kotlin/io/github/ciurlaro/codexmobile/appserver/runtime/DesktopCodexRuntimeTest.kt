package io.github.ciurlaro.codexmobile.appserver.runtime

import codex_desktop.codex_getenv
import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ClientInfo
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeCapabilities
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeParams
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
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
            DesktopCodexRuntimeConfiguration("unused".toPath(), "unused".toPath()),
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
                workingDirectory = ".".toPath(),
            ),
        ).create()

        assertFailsWith<IllegalStateException> { runtime.start() }
        runtime.close()
    }

    @Test
    fun rejectsWrongTargetChecksum(): Unit = runBlocking {
        val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "codex-agent-desktop-wrong-hash"
        val executable = directory / "codex-app-server"
        FileSystem.SYSTEM.createDirectories(directory)
        FileSystem.SYSTEM.write(executable) { writeUtf8("not an app server") }
        val runtime = DesktopCodexRuntimeFactory(
            DesktopCodexRuntimeConfiguration(executable, directory),
        ).create()

        try {
            val error = assertFailsWith<IllegalStateException> { runtime.start() }
            assertContains(error.message.orEmpty(), "checksum")
        } finally {
            runtime.close()
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun initializesAndShutsDownOfficialAppServerWhenProvided(): Unit = runBlocking {
        val executable = codex_getenv("CODEX_AGENT_APP_SERVER_EXECUTABLE")
            ?.toKString()?.toPath() ?: return@runBlocking
        val connection = AppServerConnection(
            runtimeFactory = DesktopCodexRuntimeFactory(
                DesktopCodexRuntimeConfiguration(executable, executable.parent!!),
            ),
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

private class FakeDesktopProcess : DesktopProcess {
    var closeCount = 0

    override fun readStdout(buffer: ByteArray) = 0
    override fun readStderr(buffer: ByteArray) = 0
    override fun write(bytes: ByteArray) = Unit
    override fun waitForExit(): Int? = null
    override fun close() { closeCount++ }
}
