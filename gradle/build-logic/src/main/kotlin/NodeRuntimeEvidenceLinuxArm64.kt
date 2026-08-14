import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private const val NODE_ARM_TARGET = "linuxArm64"
private const val NODE_ARM_METADATA = "execution.json"
private const val NODE_ARM_JS_COMPILED = NODE_RUNTIME_RUNNER_ARCHIVE
private const val NODE_ARM_WASM_COMPILED = NODE_WASM_RUNTIME_RUNNER_ARCHIVE
private const val NODE_ARM_CLASSIFIER = "app-server-linux-arm64.zip"
private val NODE_ARM_MEMBERS = setOf(
    NODE_ARM_METADATA,
    NODE_ARM_JS_COMPILED,
    NODE_ARM_WASM_COMPILED,
    NODE_ARM_CLASSIFIER,
)
private val NODE_ARM_ZIP_EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0)

internal fun stageLinuxArm64NodeRuntimeEvidenceBundle(
    candidateCommit: String,
    compiledNodeTestRuntime: File,
    compiledNodeWasmTestRuntime: File,
    classifierInput: File,
    distributionManifest: File,
    output: File,
) {
    check(candidateCommit.matches(Regex("[0-9a-f]{40}"))) { "Node evidence commit is not immutable" }
    inspectNodeRuntimeRunnerArchive(compiledNodeTestRuntime, NODE_RUNTIME_JS_BACKEND)
    inspectNodeRuntimeRunnerArchive(compiledNodeWasmTestRuntime, NODE_RUNTIME_WASM_BACKEND)
    val classifier = resolveLinuxArm64Classifier(classifierInput)
    val manifest = readDesktopCodexManifest(distributionManifest)
    val proof = inspectNodeClassifier(NODE_ARM_TARGET, manifest, classifier)
    val metadata = buildJsonObject {
        put("schemaVersion", JsonPrimitive(2))
        put("candidateCommit", JsonPrimitive(candidateCommit))
        put("distributionManifestSha256", JsonPrimitive(distributionManifest.releaseDigest()))
        put("compiledNodeTestRuntime", compiledNodeTestRuntime.nodeArmMember(NODE_ARM_JS_COMPILED))
        put("compiledNodeWasmTestRuntime", compiledNodeWasmTestRuntime.nodeArmMember(NODE_ARM_WASM_COMPILED))
        put("classifierArchive", classifier.nodeArmMember(NODE_ARM_CLASSIFIER))
        put("appServerBinarySha256", JsonPrimitive(proof.binarySha256))
        put("processSupervisorSha256", JsonPrimitive(proof.supervisorSha256))
    }
    val metadataBytes = (releaseJson.encodeToString(JsonObject.serializer(), metadata) + "\n").encodeToByteArray()
    output.parentFile.mkdirs()
    val temporary = Files.createTempFile(output.parentFile.toPath(), ".${output.name}-", ".tmp")
    try {
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { zip ->
            zip.setLevel(9)
            zip.nodeArmAdd(NODE_ARM_METADATA, ByteArrayInputStream(metadataBytes))
            zip.nodeArmAdd(NODE_ARM_JS_COMPILED, compiledNodeTestRuntime.inputStream())
            zip.nodeArmAdd(NODE_ARM_WASM_COMPILED, compiledNodeWasmTestRuntime.inputStream())
            zip.nodeArmAdd(NODE_ARM_CLASSIFIER, classifier.inputStream())
        }
        try { Files.move(temporary, output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, output.toPath(), REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun executeLinuxArm64NodeRuntimeEvidenceBundle(
    candidateCommit: String,
    bundle: File,
    distributionManifest: File,
    nodeExecutable: String,
    jsEvidenceFile: File,
    jsTestReport: File,
    wasmEvidenceFile: File,
    wasmTestReport: File,
    environment: Map<String, String> = System.getenv(),
    runner: (List<String>, Map<String, String>) -> NodeEvidenceProcessResult,
) {
    check(environment["RUNNER_OS"] == "Linux" && environment["RUNNER_ARCH"] == "ARM64") {
        "Linux ARM64 Node evidence must run on RUNNER_OS=Linux and RUNNER_ARCH=ARM64"
    }
    val temporary = Files.createTempDirectory("codex-agent-linux-arm64-node-evidence").toFile()
    try {
        val metadata = extractNodeArmBundle(bundle, temporary)
        check(metadata.releaseInt("schemaVersion") == 2 &&
            metadata.releaseString("candidateCommit") == candidateCommit) {
            "Linux ARM64 Node evidence commit mismatch"
        }
        check(metadata.releaseString("distributionManifestSha256") == distributionManifest.releaseDigest()) {
            "Linux ARM64 Node distribution manifest mismatch"
        }
        val compiledJs = temporary.resolve(NODE_ARM_JS_COMPILED)
        val compiledWasm = temporary.resolve(NODE_ARM_WASM_COMPILED)
        val classifier = temporary.resolve(NODE_ARM_CLASSIFIER)
        verifyNodeArmMember(compiledJs, metadata.releaseObject("compiledNodeTestRuntime"))
        verifyNodeArmMember(compiledWasm, metadata.releaseObject("compiledNodeWasmTestRuntime"))
        verifyNodeArmMember(classifier, metadata.releaseObject("classifierArchive"))
        val proof = inspectNodeClassifier(
            NODE_ARM_TARGET,
            readDesktopCodexManifest(distributionManifest),
            classifier,
        )
        check(proof.binarySha256 == metadata.releaseString("appServerBinarySha256") &&
            proof.supervisorSha256 == metadata.releaseString("processSupervisorSha256")) {
            "Linux ARM64 Node classifier executable hash mismatch"
        }
        executeNodeRuntimeEvidence(
            candidateCommit, NODE_ARM_TARGET, NODE_RUNTIME_JS_BACKEND,
            environment.getValue("RUNNER_OS"), environment.getValue("RUNNER_ARCH"), nodeExecutable,
            distributionManifest, classifier, compiledJs, jsEvidenceFile, jsTestReport, runner = runner,
        )
        executeNodeRuntimeEvidence(
            candidateCommit, NODE_ARM_TARGET, NODE_RUNTIME_WASM_BACKEND,
            environment.getValue("RUNNER_OS"), environment.getValue("RUNNER_ARCH"), nodeExecutable,
            distributionManifest, classifier, compiledWasm, wasmEvidenceFile, wasmTestReport, runner = runner,
        )
    } finally {
        temporary.deleteRecursively()
    }
}

private fun extractNodeArmBundle(bundle: File, destination: File): JsonObject = ZipFile(bundle).use { zip ->
    val entries = zip.entries().asSequence().toList()
    check(entries.none(ZipEntry::isDirectory) && entries.map(ZipEntry::getName).toSet() == NODE_ARM_MEMBERS &&
        entries.size == NODE_ARM_MEMBERS.size && entries.all { it.name == File(it.name).name }) {
        "Linux ARM64 Node execution bundle member set is invalid"
    }
    val metadataEntry = zip.getEntry(NODE_ARM_METADATA)
    check(metadataEntry.size in 1..65_536) { "Linux ARM64 Node metadata size is invalid" }
    val metadata = zip.getInputStream(metadataEntry).use { input ->
        releaseJson.parseToJsonElement(input.readBytes().decodeToString()) as JsonObject
    }
    check(metadata.keys == setOf(
        "schemaVersion", "candidateCommit", "distributionManifestSha256", "compiledNodeTestRuntime",
        "compiledNodeWasmTestRuntime", "classifierArchive", "appServerBinarySha256",
        "processSupervisorSha256",
    )) { "Linux ARM64 Node metadata fields are invalid" }
    listOf(NODE_ARM_JS_COMPILED, NODE_ARM_WASM_COMPILED, NODE_ARM_CLASSIFIER).forEach { path ->
        val output = destination.resolve(path)
        zip.getInputStream(zip.getEntry(path)).use { input -> Files.copy(input, output.toPath(), REPLACE_EXISTING) }
    }
    metadata
}

private fun File.nodeArmMember(path: String) = buildJsonObject {
    put("path", JsonPrimitive(path))
    put("bytes", JsonPrimitive(length()))
    put("sha256", JsonPrimitive(releaseDigest()))
}

private fun verifyNodeArmMember(file: File, record: JsonObject) {
    check(record.keys == setOf("path", "bytes", "sha256") && record.releaseString("path") == file.name &&
        record.releaseLong("bytes") == file.length() && record.releaseString("sha256") == file.releaseDigest()) {
        "Linux ARM64 Node bundle member mismatch: ${file.name}"
    }
}

private fun ZipOutputStream.nodeArmAdd(path: String, input: java.io.InputStream) {
    putNextEntry(ZipEntry(path).apply { setTimeLocal(NODE_ARM_ZIP_EPOCH) })
    input.use { it.copyTo(this) }
    closeEntry()
}

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "stage" -> {
            check(args.size == 7) {
                "stage requires commit, JS runtime, Wasm runtime, classifier, manifest, and output"
            }
            stageLinuxArm64NodeRuntimeEvidenceBundle(
                args[1], File(args[2]), File(args[3]), File(args[4]), File(args[5]), File(args[6]),
            )
        }
        "execute" -> {
            check(args.size == 9) {
                "execute requires commit, bundle, manifest, Node, JS evidence/report, and Wasm evidence/report"
            }
            executeLinuxArm64NodeRuntimeEvidenceBundle(
                args[1], File(args[2]), File(args[3]), args[4], File(args[5]), File(args[6]),
                File(args[7]), File(args[8]), runner = ::runLinuxArmNodeEvidenceProcess,
            )
        }
        else -> error("Expected stage or execute")
    }
}

private fun runLinuxArmNodeEvidenceProcess(
    command: List<String>,
    environment: Map<String, String>,
): NodeEvidenceProcessResult {
    val log = Files.createTempFile("linux-arm64-node-evidence", ".log").toFile()
    return try {
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log)
            .apply { environment().putAll(environment) }.start()
        val completed = process.waitFor(5, java.util.concurrent.TimeUnit.MINUTES)
        if (!completed) process.destroyForcibly().waitFor()
        NodeEvidenceProcessResult(if (completed) process.exitValue() else -1, log.readText())
    } finally { log.delete() }
}
