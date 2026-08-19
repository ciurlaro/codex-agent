import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val PINNED_NODE_VERSION = "24.18.0"
internal const val NODE_RUNTIME_JS_BACKEND = "js"
internal const val NODE_RUNTIME_WASM_BACKEND = "wasm"
internal const val NODE_RUNTIME_TEST_CLASS =
    "io.github.ciurlaro.codexmobile.appserver.runtime.NodeCodexRuntimeTest"
internal const val NODE_RUNTIME_RUNNER_ARCHIVE = "codex-agent-node-runtime-evidence-runner.zip"
internal const val NODE_RUNTIME_RUNNER_ENTRY = "codex-agent-codex-agent-runtime-node.js"
internal const val NODE_WASM_RUNTIME_RUNNER_ARCHIVE = "codex-agent-node-wasm-runtime-evidence-runner.zip"
internal const val NODE_WASM_RUNTIME_RUNNER_ENTRY = "codex-agent-codex-agent-runtime-node.mjs"
internal val nodeRuntimeBackends = linkedSetOf(NODE_RUNTIME_JS_BACKEND, NODE_RUNTIME_WASM_BACKEND)
internal val nodeWasmRuntimeRunnerEntries = setOf(
    NODE_WASM_RUNTIME_RUNNER_ENTRY,
    "codex-agent-codex-agent-runtime-node.uninstantiated.mjs",
    "codex-agent-codex-agent-runtime-node.wasm",
    "custom-formatters.js",
)
internal val nodeRuntimeTestMethods = sortedSetOf(
    "closeDuringStartClosesNewProcessExactlyOnce",
    "initializesAndShutsDownOfficialAppServerWhenProvided",
    "rejectsRelativeExecutableBeforeStarting",
    "rejectsWrongTargetChecksum",
)

fun nodeRuntimeEvidenceFileName(target: String, runtimeBackend: String = NODE_RUNTIME_JS_BACKEND): String {
    requireNodeRuntimeBackend(runtimeBackend)
    return if (runtimeBackend == NODE_RUNTIME_JS_BACKEND) {
        "node-runtime-$target.json"
    } else {
        "node-wasm-runtime-$target.json"
    }
}

fun nodeRuntimeTestReportFileName(target: String, runtimeBackend: String = NODE_RUNTIME_JS_BACKEND): String {
    requireNodeRuntimeBackend(runtimeBackend)
    val prefix = if (runtimeBackend == NODE_RUNTIME_JS_BACKEND) "nodeRuntime" else "nodeWasmRuntime"
    return "TEST-$prefix${target.replaceFirstChar(Char::uppercase)}Test.$NODE_RUNTIME_TEST_CLASS.xml"
}

internal fun nodeRuntimeEvidenceTestTask(target: String, runtimeBackend: String = NODE_RUNTIME_JS_BACKEND): String {
    requireNodeRuntimeBackend(runtimeBackend)
    if (target == "linuxArm64") return LINUX_ARM64_RUNTIME_EVIDENCE_TASK
    val prefix = if (runtimeBackend == NODE_RUNTIME_JS_BACKEND) "nodeRuntime" else "nodeWasmRuntime"
    return ":codex-agent-runtime-node:$prefix${target.replaceFirstChar(Char::uppercase)}Test"
}

internal fun requireNodeRuntimeBackend(runtimeBackend: String): String {
    check(runtimeBackend in nodeRuntimeBackends) { "Unsupported Node runtime backend: $runtimeBackend" }
    return runtimeBackend
}

internal fun nodeRuntimeRunnerArchiveName(runtimeBackend: String): String = when (requireNodeRuntimeBackend(runtimeBackend)) {
    NODE_RUNTIME_JS_BACKEND -> NODE_RUNTIME_RUNNER_ARCHIVE
    else -> NODE_WASM_RUNTIME_RUNNER_ARCHIVE
}

internal fun nodeRuntimeRunnerEntry(runtimeBackend: String): String = when (requireNodeRuntimeBackend(runtimeBackend)) {
    NODE_RUNTIME_JS_BACKEND -> NODE_RUNTIME_RUNNER_ENTRY
    else -> NODE_WASM_RUNTIME_RUNNER_ENTRY
}

internal typealias NodeClassifierProof = DesktopClassifierProof

internal data class NodeRuntimeEvidenceValues(
    val candidateCommit: String,
    val target: String,
    val runtimeBackend: String,
    val classifierProof: NodeClassifierProof,
    val compiledNodeTestRuntime: File,
)

internal fun inspectNodeClassifier(
    target: String,
    manifest: DesktopCodexManifest,
    archive: File,
): NodeClassifierProof = inspectDesktopClassifier(target, manifest, archive)

internal fun buildNodeRuntimeEvidence(values: NodeRuntimeEvidenceValues): JsonObject {
    requireNodeRuntimeBackend(values.runtimeBackend)
    val target = desktopRuntimeEvidenceTargets.getValue(values.target)
    val classifier = values.classifierProof
    val compiled = values.compiledNodeTestRuntime
    check(classifier.target == values.target && classifier.classifier == target.classifier) {
        "Node evidence classifier does not match ${values.target}"
    }
    inspectNodeRuntimeRunnerArchive(compiled, values.runtimeBackend)
    return buildJsonObject {
        put("schemaVersion", JsonPrimitive(2))
        put("candidateCommit", JsonPrimitive(values.candidateCommit))
        put("target", JsonPrimitive(values.target))
        put("runtimeBackend", JsonPrimitive(values.runtimeBackend))
        put("classifier", JsonPrimitive(target.classifier))
        put("runnerOs", JsonPrimitive(target.runnerOs))
        put("runnerArch", JsonPrimitive(target.runnerArch))
        put("nodeVersion", JsonPrimitive(PINNED_NODE_VERSION))
        put("testTask", JsonPrimitive(nodeRuntimeEvidenceTestTask(values.target, values.runtimeBackend)))
        put("testClass", JsonPrimitive(NODE_RUNTIME_TEST_CLASS))
        put("testMethods", buildJsonArray { nodeRuntimeTestMethods.forEach { add(JsonPrimitive(it)) } })
        put("tests", JsonPrimitive(nodeRuntimeTestMethods.size))
        put("skipped", JsonPrimitive(0))
        put("failures", JsonPrimitive(0))
        put("errors", JsonPrimitive(0))
        put("classifierArchiveFileName", JsonPrimitive(classifier.archiveFile.name))
        put("classifierArchiveBytes", JsonPrimitive(classifier.archiveBytes))
        put("classifierArchiveSha256", JsonPrimitive(classifier.archiveSha256))
        put("appServerBinarySha256", JsonPrimitive(classifier.binarySha256))
        put("processSupervisorSha256", JsonPrimitive(classifier.supervisorSha256))
        put("compiledNodeTestRuntimeFileName", JsonPrimitive(compiled.name))
        put("compiledNodeTestRuntimeBytes", JsonPrimitive(compiled.length()))
        put("compiledNodeTestRuntimeSha256", JsonPrimitive(compiled.releaseDigest()))
        put("result", JsonPrimitive("passed"))
    }
}

internal fun validateNodeRuntimeEvidence(
    evidenceFiles: List<File>,
    expectedCommit: String,
    runtimeBackend: String,
    distributionManifest: File,
    classifierArchives: List<File>,
    compiledNodeTestRuntime: File,
): List<String> = buildList {
    runCatching { requireNodeRuntimeBackend(runtimeBackend) }
        .exceptionOrNull()?.let { add(it.message ?: "runtime backend is invalid"); return@buildList }
    if (!expectedCommit.matches(Regex("[0-9a-f]{40}"))) add("candidate commit is not immutable")
    val manifest = runCatching { readDesktopCodexManifest(distributionManifest) }
        .getOrElse { add("distribution manifest: ${it.message}"); return@buildList }
    if (manifest.distributions.map(DesktopCodexDistributionSpec::target).toSet() !=
        desktopRuntimeEvidenceTargets.keys) add("distribution target set mismatch")
    if (!compiledNodeTestRuntime.isFile || compiledNodeTestRuntime.length() == 0L) {
        add("compiled Node test/runtime artifact is missing")
    } else runCatching { inspectNodeRuntimeRunnerArchive(compiledNodeTestRuntime, runtimeBackend) }
        .exceptionOrNull()?.let { add("compiled Node test/runtime artifact: ${it.message}") }
    if (classifierArchives.size != desktopRuntimeEvidenceTargets.size) add("classifier archive set mismatch")

    val classifierProofs = desktopRuntimeEvidenceTargets.keys.mapNotNull { target ->
        val matches = classifierArchives.mapNotNull { archive ->
            runCatching { inspectNodeClassifier(target, manifest, archive) }.getOrNull()
        }
        if (matches.size != 1) {
            add("$target: expected exactly one matching classifier archive")
            null
        } else matches.single()
    }.associateBy(NodeClassifierProof::target)

    val byName = evidenceFiles.associateBy(File::getName)
    val expectedNames = desktopRuntimeEvidenceTargets.keys
        .map { nodeRuntimeEvidenceFileName(it, runtimeBackend) }.toSet()
    if (evidenceFiles.size != expectedNames.size || byName.keys != expectedNames) {
        add("evidence file set mismatch")
    }
    val compiledDigest = compiledNodeTestRuntime.takeIf(File::isFile)?.releaseDigest()

    desktopRuntimeEvidenceTargets.forEach { (target, expected) ->
        val file = byName[nodeRuntimeEvidenceFileName(target, runtimeBackend)] ?: return@forEach
        runCatching {
            val report = file.readReleaseObject()
            check(report.keys == NODE_RUNTIME_EVIDENCE_KEYS) { "schema fields mismatch" }
            check(report.releaseInt("schemaVersion") == 2) { "schema version mismatch" }
            check(report.releaseString("candidateCommit") == expectedCommit) { "commit mismatch" }
            check(report.releaseString("target") == target) { "target mismatch" }
            check(report.releaseString("runtimeBackend") == runtimeBackend) { "runtime backend mismatch" }
            check(report.releaseString("classifier") == expected.classifier) { "classifier mismatch" }
            check(report.releaseString("runnerOs") == expected.runnerOs) { "runner OS mismatch" }
            check(report.releaseString("runnerArch") == expected.runnerArch) { "runner architecture mismatch" }
            check(report.releaseString("nodeVersion") == PINNED_NODE_VERSION) { "Node version mismatch" }
            check(report.releaseString("testTask") == nodeRuntimeEvidenceTestTask(target, runtimeBackend)) {
                "test task mismatch"
            }
            check(report.releaseString("testClass") == NODE_RUNTIME_TEST_CLASS) { "test class mismatch" }
            check(report.releaseArray("testMethods").map { it.jsonPrimitive.content }.toSet() ==
                nodeRuntimeTestMethods) { "test methods mismatch" }
            check(report.releaseInt("tests") == nodeRuntimeTestMethods.size &&
                report.releaseInt("skipped") == 0 && report.releaseInt("failures") == 0 &&
                report.releaseInt("errors") == 0) { "test result mismatch" }
            check(report.releaseString("result") == "passed") { "result mismatch" }

            val classifier = classifierProofs.getValue(target)
            check(isSafeNodeEvidenceName(report.releaseString("classifierArchiveFileName"))) {
                "classifier archive filename is unsafe"
            }
            check(report.releaseLong("classifierArchiveBytes") == classifier.archiveBytes &&
                report.releaseString("classifierArchiveSha256") == classifier.archiveSha256) {
                "classifier archive bytes mismatch"
            }
            check(report.releaseString("appServerBinarySha256") == classifier.binarySha256) {
                "App Server binary hash mismatch"
            }
            check(report.releaseString("processSupervisorSha256") == classifier.supervisorSha256) {
                "process supervisor hash mismatch"
            }
            check(isSafeNodeEvidenceName(report.releaseString("compiledNodeTestRuntimeFileName"))) {
                "compiled artifact filename is unsafe"
            }
            check(report.releaseLong("compiledNodeTestRuntimeBytes") == compiledNodeTestRuntime.length() &&
                report.releaseString("compiledNodeTestRuntimeSha256") == compiledDigest) {
                "compiled Node test/runtime artifact mismatch"
            }
        }.exceptionOrNull()?.let { add("$target: ${it.message}") }
    }
}

private val NODE_RUNTIME_EVIDENCE_KEYS = setOf(
    "schemaVersion", "candidateCommit", "target", "runtimeBackend", "classifier", "runnerOs", "runnerArch",
    "nodeVersion", "testTask", "testClass", "testMethods", "tests", "skipped", "failures", "errors",
    "classifierArchiveFileName", "classifierArchiveBytes", "classifierArchiveSha256",
    "appServerBinarySha256", "processSupervisorSha256", "compiledNodeTestRuntimeFileName",
    "compiledNodeTestRuntimeBytes", "compiledNodeTestRuntimeSha256", "result",
)

private fun isSafeNodeEvidenceName(value: String): Boolean =
    value == File(value).name && '/' !in value && '\\' !in value

internal fun inspectNodeRuntimeRunnerArchive(archive: File, runtimeBackend: String): List<String> {
    check(archive.isFile && archive.length() > 0 && archive.name == nodeRuntimeRunnerArchiveName(runtimeBackend)) {
        "Compiled Node runtime archive is missing or misnamed"
    }
    return ZipFile(archive).use { zip ->
        val entries = zip.entries().asSequence().toList()
        val names = entries.map(ZipEntry::getName)
        check(entries.isNotEmpty() && entries.none(ZipEntry::isDirectory) && names.toSet().size == names.size) {
            "Compiled Node runtime archive has invalid members"
        }
        check(names.all { it == File(it).name && '/' !in it && '\\' !in it }) {
            "Compiled Node runtime archive members must be root files"
        }
        if (runtimeBackend == NODE_RUNTIME_JS_BACKEND) {
            check(names.all { it.endsWith(".js") } && NODE_RUNTIME_RUNNER_ENTRY in names) {
                "Compiled Node runtime JavaScript entry is missing or invalid"
            }
        } else {
            check(names.toSet() == nodeWasmRuntimeRunnerEntries && names.size == nodeWasmRuntimeRunnerEntries.size) {
                "Compiled Node Wasm runtime archive member set is invalid"
            }
        }
        entries.forEach { entry ->
            check(zip.getInputStream(entry).use { it.read() } != -1) {
                "Compiled Node runtime member is empty: ${entry.name}"
            }
        }
        names.sorted()
    }
}
