import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory

internal const val NODE_EVIDENCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
internal val NODE_EVIDENCE_ARM_ENV = mapOf("RUNNER_OS" to "Linux", "RUNNER_ARCH" to "ARM64")

internal class NodeRuntimeEvidenceFixture(val root: File) {
    private val appServer = "official app server".encodeToByteArray()
    private val embeddedSupervisor = "embedded process supervisor".encodeToByteArray()
    val manifest = writeTestDesktopDistributionManifest(
        root.resolve("codex-app-server-distributions.json"),
        appServer.inputStream().releaseDigest(),
    )
    val compiled = root.resolve(NODE_RUNTIME_RUNNER_ARCHIVE).apply {
        nodeEvidenceWriteZip(linkedMapOf(
            NODE_RUNTIME_RUNNER_ENTRY to "compiled Node entry".encodeToByteArray(),
            "kotlin-kotlin-stdlib.js" to "compiled Kotlin dependency".encodeToByteArray(),
        ))
    }
    val compiledWasm = root.resolve(NODE_WASM_RUNTIME_RUNNER_ARCHIVE).apply {
        nodeEvidenceWriteZip(nodeWasmRuntimeRunnerEntries.associateWith { "compiled $it".encodeToByteArray() })
    }
    val classifiers = desktopRuntimeEvidenceTargets.mapValues { (target, spec) ->
        root.resolve("codex-agent-runtime-desktop-0.2.0-${spec.classifier}.zip").apply {
            nodeEvidenceWriteZip(linkedMapOf(
                (if (target == "mingwX64") "codex-app-server.exe" else "codex-app-server") to appServer,
                (if (target == "mingwX64") "codex-process-supervisor.exe" else "codex-process-supervisor") to
                    embeddedSupervisor,
                "openai-codex-LICENSE.txt" to "license".encodeToByteArray(),
                "openai-codex-NOTICE.txt" to "notice".encodeToByteArray(),
            ))
        }
    }

    fun runnerArchive(runtimeBackend: String) =
        if (runtimeBackend == NODE_RUNTIME_JS_BACKEND) compiled else compiledWasm

    fun evidence(target: String, runtimeBackend: String = NODE_RUNTIME_JS_BACKEND) =
        root.resolve(nodeRuntimeEvidenceFileName(target, runtimeBackend))

    fun report(target: String, runtimeBackend: String = NODE_RUNTIME_JS_BACKEND) =
        root.resolve(nodeRuntimeTestReportFileName(target, runtimeBackend))

    fun record(
        target: String,
        runtimeBackend: String = NODE_RUNTIME_JS_BACKEND,
        runner: (List<String>, Map<String, String>) -> NodeEvidenceProcessResult = { command, _ ->
            successfulNodeEvidenceResult(command)
        },
    ) {
        val expected = desktopRuntimeEvidenceTargets.getValue(target)
        executeNodeRuntimeEvidence(
            NODE_EVIDENCE_COMMIT,
            target,
            runtimeBackend,
            expected.runnerOs,
            expected.runnerArch,
            "node",
            manifest,
            classifiers.getValue(target),
            runnerArchive(runtimeBackend),
            evidence(target, runtimeBackend),
            report(target, runtimeBackend),
            runner,
        )
    }

    fun recordAll(runtimeBackend: String = NODE_RUNTIME_JS_BACKEND) =
        desktopRuntimeEvidenceTargets.keys.forEach { record(it, runtimeBackend) }

    fun validate(
        runtimeBackend: String = NODE_RUNTIME_JS_BACKEND,
        evidenceFiles: List<File> = desktopRuntimeEvidenceTargets.keys.map { evidence(it, runtimeBackend) },
        classifierFiles: List<File> = classifiers.values.toList(),
        compiledFile: File = runnerArchive(runtimeBackend),
    ) = validateNodeRuntimeEvidence(
        evidenceFiles,
        NODE_EVIDENCE_COMMIT,
        runtimeBackend,
        manifest,
        classifierFiles,
        compiledFile,
    )
}

internal fun withNodeRuntimeEvidenceFixture(block: (NodeRuntimeEvidenceFixture) -> Unit) {
    val root = createTempDirectory("node-runtime-evidence").toFile()
    try { block(NodeRuntimeEvidenceFixture(root)) } finally { root.deleteRecursively() }
}

internal fun exactNodeEvidenceListing() = buildString {
    append(NODE_RUNTIME_TEST_CLASS).append(".\n")
    nodeRuntimeTestMethods.forEach { append("  ").append(it).append('\n') }
}

internal fun successfulNodeEvidenceResult(command: List<String>) = when (command.last()) {
    "--version" -> NodeEvidenceProcessResult(0, "v$PINNED_NODE_VERSION\n")
    "--list-tests" -> NodeEvidenceProcessResult(0, exactNodeEvidenceListing())
    else -> NodeEvidenceProcessResult(0, "")
}

internal fun File.nodeEvidenceZipEntries(): LinkedHashMap<String, ByteArray> = ZipFile(this).use { zip ->
    linkedMapOf<String, ByteArray>().apply {
        zip.entries().asSequence().forEach { entry ->
            put(entry.name, zip.getInputStream(entry).use { it.readBytes() })
        }
    }
}

internal fun File.nodeEvidenceWriteZip(entries: Map<String, ByteArray>) =
    ZipOutputStream(outputStream()).use { zip ->
        entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name).apply {
                setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
            })
            zip.write(bytes)
            zip.closeEntry()
        }
    }
