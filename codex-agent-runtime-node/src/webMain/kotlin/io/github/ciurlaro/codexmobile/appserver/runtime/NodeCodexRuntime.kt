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
    val processSupervisorExecutable: Path,
    val processSupervisorSha256: String,
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
    fun canonical(value: Path, kind: String): String {
        val raw = value.toString()
        check(nodeHost.isAbsolute(raw)) { "$kind must be absolute" }
        val resolved = nodeHost.realPath(raw)
        check(nodeHost.resolvePath(raw) == resolved) { "$kind must not be a symbolic link" }
        return resolved
    }

    val target = currentNodeTarget()
    val distribution = nodeCodexDistribution(target)
    val executable = canonical(configuration.appServerExecutable, "App Server executable")
    val supervisor = canonical(configuration.processSupervisorExecutable, "Process supervisor executable")
    val workingDirectory = canonical(configuration.workingDirectory, "Working directory")
    check(nodeHost.isFile(executable)) { "App Server executable is not a regular file" }
    check(nodeHost.isFile(supervisor)) { "Process supervisor executable is not a regular file" }
    check(nodeHost.isDirectory(workingDirectory)) { "Working directory is not a directory" }
    check(nodeHost.baseName(executable) == distribution.executableName) {
        "App Server executable name mismatch"
    }
    check(nodeHost.baseName(supervisor) == distribution.supervisorExecutableName) {
        "Process supervisor executable name mismatch"
    }
    nodeHost.requireExecutable(executable)
    nodeHost.requireExecutable(supervisor)
    check(nodeHost.sha256(executable) == distribution.binarySha256) { "App Server checksum mismatch" }
    check(configuration.processSupervisorSha256.matches(Regex("[0-9a-f]{64}"))) {
        "Process supervisor SHA-256 must be 64 lowercase hexadecimal characters"
    }
    check(nodeHost.sha256(supervisor) == configuration.processSupervisorSha256) {
        "Process supervisor checksum mismatch"
    }
    return NodeLaunchSpec(
        command = supervisor,
        arguments = arrayOf(executable),
        workingDirectory = workingDirectory,
        detached = false,
        target = target,
    )
}
