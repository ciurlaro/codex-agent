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

internal actual val nodeHost: NodeHost = JsNodeHost

private object JsNodeHost : NodeHost {
    private val fs: dynamic = js("require('node:fs')")
    private val path: dynamic = js("require('node:path')")
    private val crypto: dynamic = js("require('node:crypto')")

    override val platform: String get() = js("process.platform") as String
    override val architecture: String get() = js("process.arch") as String
    override fun isAbsolute(path: String): Boolean = this.path.isAbsolute(path) as Boolean
    override fun realPath(path: String): String = fs.realpathSync(path) as String
    override fun resolvePath(path: String): String = this.path.resolve(path) as String
    override fun baseName(path: String): String = this.path.basename(path) as String
    override fun directoryName(path: String): String = this.path.dirname(path) as String
    override fun joinPath(parent: String, child: String): String = path.join(parent, child) as String
    override fun isFile(path: String): Boolean = fs.statSync(path).isFile() as Boolean
    override fun isDirectory(path: String): Boolean = fs.statSync(path).isDirectory() as Boolean
    override fun requireExecutable(path: String) {
        if (platform != "win32") fs.accessSync(path, fs.constants.X_OK)
    }
    override fun sha256(path: String): String = crypto.createHash("sha256")
        .update(fs.readFileSync(path)).digest("hex") as String
    override fun environment(name: String): String? = js("process.env")[name] as? String
    override fun arguments(): List<String> = (js("process.argv") as Array<String>).toList()
    override fun exit(code: Int): Unit = js("process.exit")(code)
    override fun error(message: String): Unit = console.error(message)
    override fun temporaryDirectory(prefix: String): String {
        val os: dynamic = js("require('node:os')")
        return fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), prefix))) as String
    }
    override fun writeExecutableFile(path: String, value: String) {
        fs.writeFileSync(path, value)
        if (platform != "win32") fs.chmodSync(path, 0x1ED)
    }
    override fun removeDirectory(path: String) {
        fs.rmSync(path, js("({ recursive: true, force: true })"))
    }

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
                    continuation.resume(JsNodeChildProcess(child, spec.detached))
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
                runCatching { signalJsNodeChild(child, spec.detached, "SIGKILL") }
            }
        }
    }
}

private class JsNodeChildProcess(
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
            if (detached && !closed) runCatching { signalJsNodeChild(child, true, "SIGKILL") }
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
            signal = { name -> runCatching { signalJsNodeChild(child, detached, name) } },
            scheduleKill = { action -> timers.setTimeout(action, 2_500) as Any },
            clearKill = { timer -> timers.clearTimeout(timer) },
        )
        stdoutInput.close()
        stdoutChannel.close()
        scope.cancel()
    }
}

private fun signalJsNodeChild(child: dynamic, detached: Boolean, name: String) {
    if (detached) {
        val process: dynamic = js("process")
        process.kill(-(child.pid as Number).toInt(), name)
    } else {
        child.kill(name)
    }
}

private fun dynamicToByteArray(value: dynamic): ByteArray {
    val size = (value.length as Number).toInt()
    return ByteArray(size) { index -> (value[index] as Number).toByte() }
}
