package io.github.ciurlaro.codexmobile.appserver.runtime

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ClientInfo
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeCapabilities
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeParams
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath

internal const val NODE_EVIDENCE_TEST_CLASS =
    "io.github.ciurlaro.codexmobile.appserver.runtime.NodeCodexRuntimeTest"
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
    val executable = nodeEnvironment("CODEX_AGENT_APP_SERVER_EXECUTABLE") ?: return
    val supervisor = nodeEnvironment("CODEX_AGENT_PROCESS_SUPERVISOR_EXECUTABLE")
        ?: error("Node evidence requires the packaged process supervisor")
    val supervisorSha256 = nodeEnvironment("CODEX_AGENT_PROCESS_SUPERVISOR_SHA256")
        ?: error("Node evidence requires the packaged process-supervisor SHA-256")
    val connection = AppServerConnection(
        runtimeFactory = NodeCodexRuntimeFactory(
            NodeCodexRuntimeConfiguration(
                executable.toPath(),
                nodeDirectoryName(executable).toPath(),
                supervisor.toPath(),
                supervisorSha256,
            ),
        ),
        initializeParams = InitializeParams(
            clientInfo = ClientInfo("codex_agent_runtime_node_test", "0.2.0", "Node Runtime Test"),
            capabilities = InitializeCapabilities(
                experimentalApi = true,
                mcpServerOpenaiFormElicitation = false,
            ),
        ),
        requestTimeoutMillis = 30_000,
    )
    try {
        val response = connection.ensureStarted()
        check(response.platformFamily.isNotBlank() && response.platformOs.isNotBlank())
    } finally {
        connection.shutdown()
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

public fun main() {
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
