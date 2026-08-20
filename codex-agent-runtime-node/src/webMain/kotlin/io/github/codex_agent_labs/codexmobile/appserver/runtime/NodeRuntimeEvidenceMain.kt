package io.github.codex_agent_labs.codexmobile.appserver.runtime

import io.github.codex_agent_labs.codexmobile.agent.CodexClientInfo
import io.github.codex_agent_labs.codexmobile.agent.CodexHost
import io.github.codex_agent_labs.codexmobile.agent.CodexHostState
import io.github.codex_agent_labs.codexmobile.agent.CodexPathWorkspaceSelection
import io.github.codex_agent_labs.codexmobile.agent.runtime.NodeCodexPlatform
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

internal const val NODE_EVIDENCE_TEST_CLASS =
    "io.github.codex_agent_labs.codexmobile.appserver.runtime.NodeCodexRuntimeTest"
internal val nodeEvidenceTestMethods = listOf(
    "closeDuringStartClosesNewProcessExactlyOnce",
    "initializesAndShutsDownOfficialAppServerWhenProvided",
    "rejectsRelativeExecutableBeforeStarting",
    "rejectsWrongTargetChecksum",
)

internal suspend fun runNodeEvidenceMethod(method: String) {
    when (method) {
        "closeDuringStartClosesNewProcessExactlyOnce" -> closeDuringStartProof()
        "initializesAndShutsDownOfficialAppServerWhenProvided" -> officialAppServerProof()
        "rejectsRelativeExecutableBeforeStarting" -> relativeExecutableProof()
        "rejectsWrongTargetChecksum" -> wrongChecksumProof()
        else -> error("Unknown Node evidence test: $method")
    }
}

private suspend fun closeDuringStartProof() {
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val process = FakeNodeProcess()
    val runtime = NodeCodexRuntime(
        NodeCodexRuntimeConfiguration(
            "unused".toPath(), "unused".toPath(), "unused".toPath(), "0".repeat(64),
        ),
        prepare = { NodeLaunchSpec("unused", emptyArray(), "unused", false, "linuxX64") },
        launcher = NodeProcessLauncher {
            started.complete(Unit)
            release.await()
            process
        },
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val start = scope.async { runCatching { runtime.start() }.exceptionOrNull() }
    started.await()
    runtime.close()
    release.complete(Unit)
    check(start.await() is IllegalStateException) { "Close during start did not fail start" }
    check(process.closeCount == 1) { "Close during start did not close the process exactly once" }
}

private suspend fun relativeExecutableProof() {
    val runtime = NodeCodexRuntimeFactory(
        NodeCodexRuntimeConfiguration(
            "codex-app-server".toPath(),
            ".".toPath(),
            "codex-process-supervisor".toPath(),
            "0".repeat(64),
        ),
    ).create()
    try {
        check(runCatching { runtime.start() }.exceptionOrNull() is IllegalStateException)
    } finally {
        runtime.close()
    }
}

private suspend fun wrongChecksumProof() {
    val directory = nodeTemporaryDirectory("codex-agent-node-wrong-hash-")
    val distribution = nodeCodexDistribution(currentNodeTarget())
    val executable = nodeJoinPath(directory, distribution.executableName)
    val supervisor = nodeJoinPath(directory, distribution.supervisorExecutableName)
    nodeWriteFile(executable, "not an app server")
    nodeWriteFile(supervisor, "not a process supervisor")
    val runtime = NodeCodexRuntimeFactory(
        NodeCodexRuntimeConfiguration(
            executable.toPath(), directory.toPath(), supervisor.toPath(), nodeHost.sha256(supervisor),
        ),
    ).create()
    try {
        val error = runCatching { runtime.start() }.exceptionOrNull()
        check(error is IllegalStateException && "checksum" in error.message.orEmpty().lowercase())
    } finally {
        runtime.close()
        nodeRemoveDirectory(directory)
    }
}

private suspend fun officialAppServerProof() {
    val bundle = nodeEnvironment("CODEX_AGENT_RUNTIME_BUNDLE_DIRECTORY") ?: return
    val data = nodeEnvironment("CODEX_AGENT_RUNTIME_DATA_DIRECTORY")
        ?: error("Node evidence requires a runtime data directory")
    val workspace = nodeEnvironment("CODEX_AGENT_WORKSPACE")
        ?: error("Node evidence requires a workspace")
    val platform = NodeCodexPlatform(bundle.toPath(), data.toPath())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val host = CodexHost(
        platform,
        scope,
        CodexClientInfo("node_runtime_evidence", "Node Runtime Evidence", "0.2.0"),
    )
    try {
        host.selectWorkspace(CodexPathWorkspaceSelection(workspace))
        check(host.state.value is CodexHostState.Ready) {
            "Node host did not become ready: ${host.state.value}"
        }
    } finally {
        host.close()
        scope.cancel()
    }
}

private class FakeNodeProcess : NodeOwnedProcess {
    private val output = Channel<ByteArray>(1)
    override val stdout: Flow<ByteArray> = output.receiveAsFlow()
    override val exitCode = CompletableDeferred<Int>()
    var closeCount = 0
    override suspend fun write(line: String) = Unit
    override fun close() {
        closeCount++
        output.close()
        exitCode.complete(0)
    }
}

internal fun main() {
    val argument = runCatching { singleNodeEvidenceArgument(nodeArguments().drop(2)) }.getOrElse {
        nodeConsoleError(it.message.orEmpty())
        nodeExit(2)
        return
    }
    if (argument == "--list-tests") {
        println("$NODE_EVIDENCE_TEST_CLASS.")
        nodeEvidenceTestMethods.forEach { println("  $it") }
        return
    }
    val prefix = "--run-test=$NODE_EVIDENCE_TEST_CLASS."
    if (!argument.startsWith(prefix)) {
        nodeConsoleError("Unknown Node evidence argument")
        nodeExit(2)
        return
    }
    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        runCatching { runNodeEvidenceMethod(argument.removePrefix(prefix)) }
            .onSuccess { nodeExit(0) }
            .onFailure {
                nodeConsoleError(it.stackTraceToString())
                nodeExit(1)
            }
    }
}

internal fun singleNodeEvidenceArgument(arguments: List<String>): String {
    check(arguments.size == 1) { "Expected exactly one Node evidence argument" }
    return arguments.single()
}
