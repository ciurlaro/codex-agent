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

private const val ARM_TARGET = "linuxArm64"
private const val ARM_METADATA = "execution.json"
private const val ARM_TEST = "linuxArm64-test.kexe"
private const val ARM_MANIFEST = "codex-app-server-distributions.json"
private const val ARM_CLASSIFIER = "app-server-linux-arm64.zip"
private val ARM_INPUTS = linkedSetOf(
    ARM_TEST, ARM_MANIFEST, ARM_CLASSIFIER, JVM_RUNTIME_RUNNER_ARCHIVE,
    NODE_RUNTIME_RUNNER_ARCHIVE, NODE_WASM_RUNTIME_RUNNER_ARCHIVE,
)
private val ARM_ZIP_EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0)

internal fun stageLinuxArm64RuntimeEvidenceBundle(
    candidateCommit: String,
    testExecutable: File,
    classifierInput: File,
    distributionManifest: File,
    jvmRunner: File,
    nodeRunner: File,
    wasmRunner: File,
    output: File,
) {
    requireArmCommit(candidateCommit)
    check(testExecutable.isFile && testExecutable.length() > 0) { "Linux ARM64 test executable is missing" }
    val classifier = resolveLinuxArm64Classifier(classifierInput)
    inspectDesktopClassifier(ARM_TARGET, readDesktopCodexManifest(distributionManifest), classifier)
    inspectJvmRuntimeRunnerArchive(jvmRunner)
    inspectNodeRuntimeRunnerArchive(nodeRunner, NODE_RUNTIME_JS_BACKEND)
    inspectNodeRuntimeRunnerArchive(wasmRunner, NODE_RUNTIME_WASM_BACKEND)
    val files = linkedMapOf(
        ARM_TEST to testExecutable,
        ARM_MANIFEST to distributionManifest,
        ARM_CLASSIFIER to classifier,
        JVM_RUNTIME_RUNNER_ARCHIVE to jvmRunner,
        NODE_RUNTIME_RUNNER_ARCHIVE to nodeRunner,
        NODE_WASM_RUNTIME_RUNNER_ARCHIVE to wasmRunner,
    )
    val metadata = buildJsonObject {
        put("schemaVersion", JsonPrimitive(1))
        put("candidateCommit", JsonPrimitive(candidateCommit))
        put("target", JsonPrimitive(ARM_TARGET))
        put("members", buildJsonObject {
            files.forEach { (path, file) -> put(path, buildJsonObject {
                put("path", JsonPrimitive(path)); put("bytes", JsonPrimitive(file.length()))
                put("sha256", JsonPrimitive(file.releaseDigest()))
            }) }
        })
    }
    val metadataBytes = (releaseJson.encodeToString(JsonObject.serializer(), metadata) + "\n").encodeToByteArray()
    output.parentFile.mkdirs()
    val temporary = Files.createTempFile(output.parentFile.toPath(), ".${output.name}-", ".tmp")
    try {
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { zip ->
            zip.setLevel(1)
            zip.addArmInput(ARM_METADATA, ByteArrayInputStream(metadataBytes))
            files.forEach { (path, file) -> zip.addArmInput(path, file.inputStream()) }
        }
        try { Files.move(temporary, output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, output.toPath(), REPLACE_EXISTING)
        }
    } finally { Files.deleteIfExists(temporary) }
}

internal fun executeLinuxArm64RuntimeEvidenceBundle(
    candidateCommit: String,
    bundle: File,
    javaExecutable: String,
    nodeExecutable: String,
    desktopEvidence: File,
    desktopReport: File,
    jvmEvidence: File,
    nodeEvidence: File,
    nodeReport: File,
    wasmEvidence: File,
    wasmReport: File,
    environment: Map<String, String> = System.getenv(),
    desktopRunner: (List<String>, Map<String, String>) -> DesktopEvidenceProcessResult = ::runDesktopEvidenceProcess,
    jvmRunner: (List<String>, Map<String, String>) -> JvmEvidenceProcessResult = ::runJvmEvidenceProcess,
    nodeRunner: (List<String>, Map<String, String>) -> NodeEvidenceProcessResult = ::runNodeEvidenceProcess,
) {
    requireArmCommit(candidateCommit)
    check(environment["RUNNER_OS"] == "Linux" && environment["RUNNER_ARCH"] == "ARM64") {
        "Linux ARM64 runtime evidence requires RUNNER_OS=Linux and RUNNER_ARCH=ARM64"
    }
    val temporary = Files.createTempDirectory("codex-agent-linux-arm64-runtime-evidence").toFile()
    try {
        val inputs = extractArmBundle(bundle, temporary, candidateCommit)
        val manifest = inputs.getValue(ARM_MANIFEST)
        val classifierArchive = inputs.getValue(ARM_CLASSIFIER)
        val classifier = inspectDesktopClassifier(ARM_TARGET, readDesktopCodexManifest(manifest), classifierArchive)
        val jvm = inputs.getValue(JVM_RUNTIME_RUNNER_ARCHIVE).also(::inspectJvmRuntimeRunnerArchive)
        val js = inputs.getValue(NODE_RUNTIME_RUNNER_ARCHIVE).also {
            inspectNodeRuntimeRunnerArchive(it, NODE_RUNTIME_JS_BACKEND)
        }
        val wasm = inputs.getValue(NODE_WASM_RUNTIME_RUNNER_ARCHIVE).also {
            inspectNodeRuntimeRunnerArchive(it, NODE_RUNTIME_WASM_BACKEND)
        }
        val executables = extractDesktopRuntimeExecutables(classifier, classifierArchive, temporary.resolve("runtime"))
        executeLinuxArm64DesktopEvidenceInputs(
            candidateCommit, inputs.getValue(ARM_TEST), classifierArchive, executables,
            classifier.binarySha256, classifier.supervisorSha256, desktopEvidence, desktopReport,
            environment, desktopRunner,
        )
        executeJvmRuntimeEvidence(
            candidateCommit, ARM_TARGET, "Linux", "ARM64", javaExecutable, manifest,
            classifierArchive, jvm, jvmEvidence, executables, jvmRunner,
        )
        executeNodeRuntimeEvidence(
            candidateCommit, ARM_TARGET, NODE_RUNTIME_JS_BACKEND, "Linux", "ARM64", nodeExecutable,
            manifest, classifierArchive, js, nodeEvidence, nodeReport, executables, nodeRunner,
        )
        executeNodeRuntimeEvidence(
            candidateCommit, ARM_TARGET, NODE_RUNTIME_WASM_BACKEND, "Linux", "ARM64", nodeExecutable,
            manifest, classifierArchive, wasm, wasmEvidence, wasmReport, executables, nodeRunner,
        )
    } finally { temporary.deleteRecursively() }
}

private fun extractArmBundle(bundle: File, destination: File, commit: String): Map<String, File> = ZipFile(bundle).use { zip ->
    val entries = zip.entries().asSequence().toList()
    val expected = ARM_INPUTS + ARM_METADATA
    check(entries.none(ZipEntry::isDirectory) && entries.size == expected.size &&
        entries.map(ZipEntry::getName).toSet() == expected && entries.all { it.name == File(it.name).name }) {
        "Linux ARM64 runtime evidence bundle member set is invalid"
    }
    val metadataEntry = zip.getEntry(ARM_METADATA)
    check(metadataEntry.size in 1..65_536) { "Linux ARM64 runtime evidence metadata size is invalid" }
    val metadata = zip.getInputStream(metadataEntry).use {
        releaseJson.parseToJsonElement(it.readBytes().decodeToString()) as JsonObject
    }
    check(metadata.keys == setOf("schemaVersion", "candidateCommit", "target", "members") &&
        metadata.releaseInt("schemaVersion") == 1 && metadata.releaseString("candidateCommit") == commit &&
        metadata.releaseString("target") == ARM_TARGET) { "Linux ARM64 runtime evidence identity is invalid" }
    val records = metadata.releaseObject("members")
    check(records.keys == ARM_INPUTS) { "Linux ARM64 runtime evidence metadata member set is invalid" }
    ARM_INPUTS.associateWith { path ->
        val record = records.releaseObject(path)
        check(record.keys == setOf("path", "bytes", "sha256") && record.releaseString("path") == path &&
            record.releaseLong("bytes") >= 0 && record.releaseString("sha256").matches(Regex("[0-9a-f]{64}"))) {
            "Linux ARM64 runtime evidence metadata is invalid: $path"
        }
        destination.resolve(path).also { output ->
            zip.getInputStream(zip.getEntry(path)).use { Files.copy(it, output.toPath(), REPLACE_EXISTING) }
            check(output.length() == record.releaseLong("bytes") &&
                output.releaseDigest() == record.releaseString("sha256")) {
                "Linux ARM64 runtime evidence member mismatch: $path"
            }
        }
    }
}

private fun ZipOutputStream.addArmInput(path: String, input: java.io.InputStream) {
    putNextEntry(ZipEntry(path).apply { setTimeLocal(ARM_ZIP_EPOCH) })
    input.use { it.copyTo(this) }
    closeEntry()
}

private fun requireArmCommit(value: String) = check(value.matches(Regex("[0-9a-f]{40}"))) {
    "Linux ARM64 candidate commit is not immutable"
}

fun main(arguments: Array<String>) {
    when (arguments.firstOrNull()) {
        "stage" -> {
            check(arguments.size == 9)
            stageLinuxArm64RuntimeEvidenceBundle(
                arguments[1], File(arguments[2]), File(arguments[3]), File(arguments[4]), File(arguments[5]),
                File(arguments[6]), File(arguments[7]), File(arguments[8]),
            )
        }
        "execute" -> {
            check(arguments.size == 12)
            executeLinuxArm64RuntimeEvidenceBundle(
                arguments[1], File(arguments[2]), arguments[3], arguments[4], File(arguments[5]),
                File(arguments[6]), File(arguments[7]), File(arguments[8]), File(arguments[9]),
                File(arguments[10]), File(arguments[11]),
            )
        }
        else -> error("Expected stage or execute")
    }
}
