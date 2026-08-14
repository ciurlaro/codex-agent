package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

internal data class NodeLaunchSpec(
    val command: String,
    val arguments: Array<String>,
    val workingDirectory: String,
    val detached: Boolean,
    val target: String,
)

internal interface NodeOwnedProcess {
    val stdout: Flow<ByteArray>
    val exitCode: CompletableDeferred<Int>
    suspend fun write(line: String)
    fun close()
}

internal fun interface NodeProcessLauncher {
    suspend fun launch(spec: NodeLaunchSpec): NodeOwnedProcess
}

internal object DefaultNodeProcessLauncher : NodeProcessLauncher {
    override suspend fun launch(spec: NodeLaunchSpec): NodeOwnedProcess {
        val childProcess: dynamic = js("require('node:child_process')")
        val options: dynamic = js("({})")
        options.cwd = spec.workingDirectory
        options.detached = spec.detached
        options.shell = false
        options.env = js("process.env")
        options.stdio = arrayOf("pipe", "pipe", "pipe")
        val child = childProcess.spawn(spec.command, spec.arguments, options)
        return suspendCancellableCoroutine { continuation ->
            var settled = false
            child.once("spawn", {
                if (!settled) {
                    settled = true
                    continuation.resume(NodeChildProcess(child, spec.detached))
                }
            })
            child.once("error", { error: dynamic ->
                if (!settled) {
                    settled = true
                    continuation.resumeWithException(
                        IllegalStateException(error?.message?.toString() ?: "Node process failed to start"),
                    )
                }
            })
            continuation.invokeOnCancellation {
                runCatching { signalNodeChild(child, spec.detached, "SIGKILL") }
            }
        }
    }
}

private class NodeChildProcess(
    private val child: dynamic,
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
        scope.launch {
            try {
                for (bytes in stdoutInput) {
                    stdoutChannel.send(bytes)
                    if (!closed) child.stdout.resume()
                }
            } catch (error: Throwable) {
                stdoutChannel.close(error)
            } finally {
                stdoutChannel.close()
            }
        }
        child.stdout.on("data", { value: dynamic ->
            child.stdout.pause()
            if (stdoutInput.trySend(dynamicToByteArray(value)).isFailure) {
                stdoutInput.close(IllegalStateException("stdout backpressure queue overflow"))
            }
        })
        child.stdout.once("end", { stdoutInput.close() })
        child.stdout.once("error", { error: dynamic ->
            stdoutInput.close(IllegalStateException(error?.message?.toString() ?: "stdout failed"))
        })
        child.stderr.on("data", { _: dynamic -> Unit })
        child.once("close", { code: dynamic ->
            stdoutInput.close()
            if (detached && !closed) runCatching { signalNodeChild(child, true, "SIGKILL") }
            if (!exitCode.isCompleted) exitCode.complete((code as? Number)?.toInt() ?: -1)
        })
        child.once("error", { error: dynamic ->
            if (!exitCode.isCompleted) {
                exitCode.completeExceptionally(
                    IllegalStateException(error?.message?.toString() ?: "Node process failed"),
                )
            }
        })
    }

    override suspend fun write(line: String) = writes.withLock {
        check(!closed && child.stdin.destroyed != true) { "Codex App Server stdin is closed" }
        suspendCancellableCoroutine { continuation ->
            child.stdin.write(line, "utf8", { error: dynamic ->
                if (error == null) continuation.resume(Unit)
                else continuation.resumeWithException(IllegalStateException(error.message.toString()))
            })
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { child.stdin.end() }
        val timers: dynamic = js("require('node:timers')")
        beginOwnedTermination(
            detached = detached,
            exitCode = exitCode,
            signal = { name -> runCatching { signalNodeChild(child, detached, name) } },
            scheduleKill = { action -> timers.setTimeout(action, 2_500) as Any },
            clearKill = { timer -> timers.clearTimeout(timer) },
        )
        stdoutInput.close()
        stdoutChannel.close()
        scope.cancel()
    }
}

internal fun beginOwnedTermination(
    detached: Boolean,
    exitCode: CompletableDeferred<Int>,
    signal: (String) -> Unit,
    scheduleKill: (() -> Unit) -> Any,
    clearKill: (Any) -> Unit,
) {
    var forced = false
    fun forceKill() {
        if (!forced) {
            forced = true
            signal("SIGKILL")
        }
    }
    signal("SIGTERM")
    val timer = scheduleKill(::forceKill)
    exitCode.invokeOnCompletion {
        if (detached) forceKill()
        clearKill(timer)
    }
}

private fun signalNodeChild(child: dynamic, detached: Boolean, name: String) {
    if (detached) {
        val process: dynamic = js("process")
        process.kill(-(child.pid as Number).toInt(), name)
    } else {
        child.kill(name)
    }
}

internal fun currentNodePlatform(): String = (js("process.platform") as String)
internal fun currentNodeArchitecture(): String = (js("process.arch") as String)
internal fun nodeEnvironment(name: String): String? = (js("process.env")[name] as? String)
internal fun nodeArguments(): List<String> = (js("process.argv") as Array<String>).toList()
internal fun nodeExit(code: Int): Unit = js("process.exit")(code)

internal fun nodeTemporaryDirectory(prefix: String): String {
    val fs: dynamic = js("require('node:fs')")
    val os: dynamic = js("require('node:os')")
    val path: dynamic = js("require('node:path')")
    return fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), prefix))) as String
}

internal fun nodeWriteFile(path: String, value: String) {
    val fs: dynamic = js("require('node:fs')")
    fs.writeFileSync(path, value)
    if (currentNodePlatform() != "win32") fs.chmodSync(path, 0x1ED)
}

internal fun nodeRemoveDirectory(path: String) {
    val fs: dynamic = js("require('node:fs')")
    fs.rmSync(path, js("({ recursive: true, force: true })"))
}

private fun dynamicToByteArray(value: dynamic): ByteArray {
    val size = (value.length as Number).toInt()
    return ByteArray(size) { index -> (value[index] as Number).toByte() }
}
