package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okio.Path

public data class NodeCodexRuntimeConfiguration(
    val appServerExecutable: Path,
    val workingDirectory: Path,
    val windowsSupervisorExecutable: Path? = null,
)

public class NodeCodexRuntimeFactory(
    private val configuration: NodeCodexRuntimeConfiguration,
) : CodexRuntimeFactory {
    override fun create(): CodexRuntime = NodeCodexRuntime(configuration)
}

internal class NodeCodexRuntime(
    private val configuration: NodeCodexRuntimeConfiguration,
    private val prepare: (NodeCodexRuntimeConfiguration) -> NodeLaunchSpec = ::validateNodeLaunch,
    private val launcher: NodeProcessLauncher = DefaultNodeProcessLauncher,
) : CodexRuntime {
    private enum class State { NEW, STARTING, RUNNING, EXITED, CLOSED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventChannel = Channel<CodexRuntimeEvent>(64)
    private var state = State.NEW
    private var process: NodeOwnedProcess? = null
    private var stdoutJob: Job? = null
    override val events: Flow<CodexRuntimeEvent> = eventChannel.receiveAsFlow()

    override suspend fun start() {
        check(state == State.NEW) { "Node Codex runtime has already been started or closed" }
        state = State.STARTING
        try {
            val owned = launcher.launch(prepare(configuration))
            if (state == State.CLOSED) {
                owned.close()
                error("Node Codex runtime was closed while starting")
            }
            process = owned
            state = State.RUNNING
            val framer = JsonLineFramer()
            stdoutJob = scope.launch {
                try {
                    owned.stdout.collect { bytes ->
                        framer.accept(bytes) { eventChannel.send(CodexRuntimeEvent.Received(CodexJsonLine(it))) }
                    }
                    framer.finish { eventChannel.send(CodexRuntimeEvent.Received(CodexJsonLine(it))) }
                    if (state != State.CLOSED) eventChannel.send(CodexRuntimeEvent.EndOfFile)
                } catch (error: Throwable) {
                    if (state != State.CLOSED) {
                        eventChannel.send(CodexRuntimeEvent.IoFailure(error.message ?: "stdout failed"))
                    }
                }
            }
            scope.launch {
                val code = runCatching { owned.exitCode.await() }.getOrElse {
                    if (state != State.CLOSED) {
                        eventChannel.send(CodexRuntimeEvent.IoFailure(it.message ?: "process failed"))
                    }
                    -1
                }
                stdoutJob?.join()
                if (state != State.CLOSED) {
                    state = State.EXITED
                    try {
                        eventChannel.send(CodexRuntimeEvent.Exited(code))
                    } finally {
                        state = State.CLOSED
                        eventChannel.close()
                        scope.cancel()
                    }
                }
            }
        } catch (error: Throwable) {
            if (state != State.CLOSED) {
                state = State.CLOSED
                eventChannel.send(CodexRuntimeEvent.StartFailure(error.message ?: "Node runtime failed to start"))
                eventChannel.close()
            }
            throw error
        }
    }

    override suspend fun send(line: CodexJsonLine) {
        check(state == State.RUNNING) { "Node Codex runtime is not running" }
        try {
            process!!.write(line.value + "\n")
        } catch (error: Throwable) {
            if (state == State.RUNNING) {
                eventChannel.trySend(CodexRuntimeEvent.IoFailure(error.message ?: "stdin failed"))
            }
            throw error
        }
    }

    override fun close() {
        if (state == State.CLOSED) return
        state = State.CLOSED
        process?.close()
        scope.cancel()
        eventChannel.close()
    }
}

internal fun validateNodeLaunch(configuration: NodeCodexRuntimeConfiguration): NodeLaunchSpec {
    val fs: dynamic = js("require('node:fs')")
    val path: dynamic = js("require('node:path')")
    val crypto: dynamic = js("require('node:crypto')")
    fun canonical(value: Path, kind: String): String {
        val raw = value.toString()
        check(path.isAbsolute(raw) as Boolean) { "$kind must be absolute" }
        val resolved = fs.realpathSync(raw) as String
        check(path.resolve(raw) == resolved) { "$kind must not be a symbolic link" }
        return resolved
    }
    fun sha256(file: String): String = crypto.createHash("sha256")
        .update(fs.readFileSync(file)).digest("hex") as String

    val platform = currentNodePlatform()
    val architecture = currentNodeArchitecture()
    val target = when (platform to architecture) {
        "darwin" to "arm64" -> "macosArm64"
        "darwin" to "x64" -> "macosX64"
        "linux" to "arm64" -> "linuxArm64"
        "linux" to "x64" -> "linuxX64"
        "win32" to "x64" -> "mingwX64"
        else -> error("Unsupported Node runtime target: $platform/$architecture")
    }
    val distribution = nodeCodexDistributions.getValue(target)
    val executable = canonical(configuration.appServerExecutable, "App Server executable")
    val workingDirectory = canonical(configuration.workingDirectory, "Working directory")
    check(fs.statSync(executable).isFile() as Boolean) { "App Server executable is not a regular file" }
    check(fs.statSync(workingDirectory).isDirectory() as Boolean) { "Working directory is not a directory" }
    check(path.basename(executable) == distribution.executableName) { "App Server executable name mismatch" }
    if (platform != "win32") fs.accessSync(executable, fs.constants.X_OK)
    check(sha256(executable) == distribution.binarySha256) { "App Server checksum mismatch" }

    val supervisor = configuration.windowsSupervisorExecutable
    if (platform == "win32") {
        check(supervisor != null) { "Windows Node runtime requires the verified supervisor" }
        val supervisorPath = canonical(supervisor, "Windows supervisor")
        val expectedSha = windowsNodeSupervisorSha256
            ?: error("Windows supervisor identity is not bound into this runtime")
        check(path.basename(supervisorPath) == windowsNodeSupervisorFileName) {
            "Windows supervisor filename mismatch"
        }
        check(fs.statSync(supervisorPath).isFile() as Boolean && sha256(supervisorPath) == expectedSha) {
            "Windows supervisor checksum mismatch"
        }
        return NodeLaunchSpec(
            command = supervisorPath,
            arguments = arrayOf(executable),
            workingDirectory = workingDirectory,
            detached = false,
            target = target,
        )
    }
    check(supervisor == null) { "A Windows supervisor was supplied on a non-Windows target" }
    return NodeLaunchSpec(executable, emptyArray(), workingDirectory, detached = true, target = target)
}
