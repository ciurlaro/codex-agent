@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.js.JsAny
import kotlin.js.toJsArray
import kotlin.js.toJsString
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine

internal actual val nodeHost: NodeHost = WasmNodeHost

private object WasmNodeHost : NodeHost {
    override val platform: String get() = wasmProcessPlatform()
    override val architecture: String get() = wasmProcessArchitecture()
    override fun isAbsolute(path: String): Boolean = wasmPathIsAbsolute(path)
    override fun realPath(path: String): String = wasmFsRealpathSync(path)
    override fun resolvePath(path: String): String = wasmPathResolve(path)
    override fun baseName(path: String): String = wasmPathBaseName(path)
    override fun directoryName(path: String): String = wasmPathDirectoryName(path)
    override fun joinPath(parent: String, child: String): String = wasmPathJoin(parent, child)
    override fun isFile(path: String): Boolean = wasmFsStatSync(path).isFile()
    override fun isDirectory(path: String): Boolean = wasmFsStatSync(path).isDirectory()
    override fun isSymbolicLink(path: String): Boolean = wasmFsLstatSync(path).isSymbolicLink()
    override fun exists(path: String): Boolean = wasmFsExistsSync(path)
    override fun fileSize(path: String): Long = wasmFsSize(path).toLong()
    override fun readBytes(path: String): ByteArray = wasmToByteArray(wasmFsReadFileSync(path))
    override fun writeBytes(path: String, bytes: ByteArray): Unit = wasmFsWriteBytes(path, bytes.toWasmBuffer())
    override fun inflateRaw(bytes: ByteArray, maxOutputLength: Int): ByteArray =
        wasmToByteArray(wasmInflateRaw(bytes.toWasmBuffer(), maxOutputLength))
    override fun createDirectories(path: String): Unit = wasmFsMkdirRecursive(path)
    override fun list(path: String): List<String> = List(wasmFsListSize(path)) { wasmFsListEntry(path, it) }
    override fun move(source: String, destination: String): Unit = wasmFsRenameSync(source, destination)
    override fun removePath(path: String): Unit = wasmFsRmSync(path, wasmRemoveOptions())
    override fun requireExecutable(path: String) {
        if (platform != "win32") wasmFsAccessSync(path, 1)
    }
    override fun makeExecutable(path: String) {
        if (platform != "win32") wasmFsChmodSync(path, 0x1ED)
    }
    override fun openUrl(url: String): Unit = wasmOpenUrl(url, platform)
    override fun sha256(path: String): String = wasmCreateHash("sha256")
        .update(wasmFsReadFileSync(path)).digest("hex")
    override fun environment(name: String): String? = wasmProcessEnvironment(name)
    override fun arguments(): List<String> = List(wasmProcessArgumentCount(), ::wasmProcessArgument)
    override fun exit(code: Int): Unit = wasmProcessExit(code)
    override fun error(message: String): Unit = wasmConsoleError(message)
    override fun temporaryDirectory(prefix: String): String = wasmFsRealpathSync(
        wasmFsMkdtempSync(wasmPathJoin(wasmOsTemporaryDirectory(), prefix)),
    )
    override fun writeExecutableFile(path: String, value: String) {
        wasmFsWriteFileSync(path, value)
        makeExecutable(path)
    }
    override fun removeDirectory(path: String): Unit = wasmFsRmSync(path, wasmRemoveOptions())

    override suspend fun launch(spec: NodeLaunchSpec): NodeOwnedProcess {
        val child = wasmSpawn(
            spec.command,
            spec.arguments.map { it.toJsString() }.toJsArray(),
            wasmSpawnOptions(spec.workingDirectory, spec.detached),
        )
        return suspendCancellableCoroutine { continuation ->
            var settled = false
            wasmOnce(child, "spawn") {
                if (!settled) {
                    settled = true
                    continuation.resume(WasmNodeChildProcess(child, spec.detached))
                }
            }
            wasmOnce(child, "error") { error ->
                if (!settled) {
                    settled = true
                    continuation.resumeWithException(
                        IllegalStateException(wasmErrorMessage(error, "Node process failed to start")),
                    )
                }
            }
            continuation.invokeOnCancellation {
                runCatching { signalWasmNodeChild(child, spec.detached, "SIGKILL") }
            }
        }
    }
}

private class WasmNodeChildProcess(
    private val child: JsAny,
    private val detached: Boolean,
) : NodeOwnedProcess {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stdoutInput = Channel<ByteArray>(1)
    private val stdoutChannel = Channel<ByteArray>(1)
    private val writes = Mutex()
    private var closed = false
    override val stdout: Flow<ByteArray> = stdoutChannel.receiveAsFlow()
    override val exitCode = CompletableDeferred<Int>()

    init {
        val stdout = wasmChildStdout(child)
        scope.launch {
            try {
                for (bytes in stdoutInput) {
                    stdoutChannel.send(bytes)
                    if (!closed) wasmStreamResume(stdout)
                }
            } catch (error: Throwable) {
                stdoutChannel.close(error)
            } finally {
                stdoutChannel.close()
            }
        }
        wasmOn(stdout, "data") { value ->
            wasmStreamPause(stdout)
            if (value == null || stdoutInput.trySend(wasmToByteArray(value)).isFailure) {
                stdoutInput.close(IllegalStateException("stdout backpressure queue overflow"))
            }
        }
        wasmOnce(stdout, "end") { stdoutInput.close() }
        wasmOnce(stdout, "error") { error ->
            stdoutInput.close(IllegalStateException(wasmErrorMessage(error, "stdout failed")))
        }
        wasmOn(wasmChildStderr(child), "data") { Unit }
        wasmOnce(child, "close") { code ->
            stdoutInput.close()
            if (detached && !closed) runCatching { signalWasmNodeChild(child, true, "SIGKILL") }
            if (!exitCode.isCompleted) exitCode.complete(wasmCloseCode(code))
        }
        wasmOnce(child, "error") { error ->
            if (!exitCode.isCompleted) {
                exitCode.completeExceptionally(
                    IllegalStateException(wasmErrorMessage(error, "Node process failed")),
                )
            }
        }
    }

    override suspend fun write(line: String) = writes.withLock {
        val stdin = wasmChildStdin(child)
        check(!closed && !wasmStreamDestroyed(stdin)) { "Codex App Server stdin is closed" }
        suspendCancellableCoroutine { continuation ->
            wasmStreamWrite(stdin, line) { error ->
                if (error == null) continuation.resume(Unit)
                else continuation.resumeWithException(
                    IllegalStateException(wasmErrorMessage(error, "stdin failed")),
                )
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { wasmStreamEnd(wasmChildStdin(child)) }
        beginOwnedTermination(
            detached = detached,
            exitCode = exitCode,
            signal = { name -> runCatching { signalWasmNodeChild(child, detached, name) } },
            scheduleKill = { action -> wasmSetTimeout(action, 2_500) },
            clearKill = ::wasmClearTimeout,
        )
        stdoutInput.close()
        stdoutChannel.close()
        scope.cancel()
    }
}

private fun signalWasmNodeChild(child: JsAny, detached: Boolean, name: String) {
    if (detached) wasmProcessKillGroup(wasmChildPid(child), name) else wasmChildKill(child, name)
}

private fun wasmToByteArray(value: JsAny): ByteArray =
    ByteArray(wasmBufferLength(value)) { index -> wasmBufferByte(value, index).toByte() }

private fun ByteArray.toWasmBuffer(): JsAny = wasmBufferAllocate(size).also { buffer ->
    forEachIndexed { index, byte -> wasmBufferSet(buffer, index, byte.toInt() and 0xff) }
}
