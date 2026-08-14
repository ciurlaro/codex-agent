import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val PINNED_NODE_VERSION = "24.18.0"
internal const val NODE_RUNTIME_TEST_CLASS =
    "io.github.ciurlaro.codexmobile.appserver.runtime.NodeCodexRuntimeTest"
internal const val NODE_RUNTIME_RUNNER_ARCHIVE = "codex-agent-node-runtime-evidence-runner.zip"
internal const val NODE_RUNTIME_RUNNER_ENTRY = "codex-agent-codex-agent-runtime-node.js"
internal val nodeRuntimeTestMethods = sortedSetOf(
    "closeDuringStartClosesNewProcessExactlyOnce",
    "initializesAndShutsDownOfficialAppServerWhenProvided",
    "rejectsRelativeExecutableBeforeStarting",
    "rejectsWrongTargetChecksum",
)

fun nodeRuntimeEvidenceFileName(target: String) = "node-runtime-$target.json"

fun nodeRuntimeTestReportFileName(target: String) =
    "TEST-nodeRuntime${target.replaceFirstChar(Char::uppercase)}Test.$NODE_RUNTIME_TEST_CLASS.xml"

internal fun nodeRuntimeEvidenceTestTask(target: String) = if (target == "linuxArm64") {
    ":buildSrc:executeLinuxArm64NodeRuntimeEvidenceBundle"
} else {
    ":codex-agent-runtime-node:nodeRuntime${target.replaceFirstChar(Char::uppercase)}Test"
}

internal data class NodeClassifierProof(
    val target: String,
    val classifier: String,
    val archiveFile: File,
    val archiveSha256: String,
    val archiveBytes: Long,
    val executableName: String,
    val binarySha256: String,
)

internal data class NodeRuntimeEvidenceValues(
    val candidateCommit: String,
    val target: String,
    val classifierProof: NodeClassifierProof,
    val compiledNodeTestRuntime: File,
    val windowsSupervisorSha256: String?,
)

internal fun inspectNodeClassifier(
    target: String,
    manifest: DesktopCodexManifest,
    archive: File,
): NodeClassifierProof {
    val expectedTarget = desktopRuntimeEvidenceTargets.getValue(target)
    check(manifest.distributions.map(DesktopCodexDistributionSpec::target).toSet() ==
        desktopRuntimeEvidenceTargets.keys) { "Desktop distribution target set mismatch" }
    val distribution = manifest.distributions.single { it.target == target }
    check(distribution.classifier == expectedTarget.classifier) { "Node classifier identity mismatch for $target" }
    check(archive.name == "${expectedTarget.classifier}.zip" ||
        archive.name.endsWith("-${expectedTarget.classifier}.zip")) {
        "Node classifier archive filename mismatch for $target"
    }
    check(archive.isFile && archive.length() > 0) { "Node classifier archive is missing for $target" }
    val binarySha = ZipFile(archive).use { zip ->
        val entries = zip.entries().asSequence().toList()
        check(entries.none(ZipEntry::isDirectory)) { "Node classifier must not contain directories" }
        check(entries.map(ZipEntry::getName).toSet().size == entries.size) {
            "Node classifier contains duplicate members"
        }
        check(entries.all { it.name == File(it.name).name && '/' !in it.name && '\\' !in it.name }) {
            "Node classifier contains an unsafe member"
        }
        val expected = setOf(
            distribution.executableName,
            "openai-codex-LICENSE.txt",
            "openai-codex-NOTICE.txt",
        )
        check(entries.map(ZipEntry::getName).toSet() == expected && entries.size == expected.size) {
            "Node classifier member set mismatch for $target"
        }
        zip.getInputStream(zip.getEntry(distribution.executableName)).use { it.releaseDigest() }
    }
    check(binarySha == distribution.binarySha256) { "Node App Server hash is not pinned for $target" }
    return NodeClassifierProof(
        target,
        distribution.classifier,
        archive,
        archive.releaseDigest(),
        archive.length(),
        distribution.executableName,
        binarySha,
    )
}

internal fun buildNodeRuntimeEvidence(values: NodeRuntimeEvidenceValues): JsonObject {
    val target = desktopRuntimeEvidenceTargets.getValue(values.target)
    val classifier = values.classifierProof
    val compiled = values.compiledNodeTestRuntime
    check(classifier.target == values.target && classifier.classifier == target.classifier) {
        "Node evidence classifier does not match ${values.target}"
    }
    inspectNodeRuntimeRunnerArchive(compiled)
    return buildJsonObject {
        put("schemaVersion", JsonPrimitive(1))
        put("candidateCommit", JsonPrimitive(values.candidateCommit))
        put("target", JsonPrimitive(values.target))
        put("classifier", JsonPrimitive(target.classifier))
        put("runnerOs", JsonPrimitive(target.runnerOs))
        put("runnerArch", JsonPrimitive(target.runnerArch))
        put("nodeVersion", JsonPrimitive(PINNED_NODE_VERSION))
        put("testTask", JsonPrimitive(nodeRuntimeEvidenceTestTask(values.target)))
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
        put("compiledNodeTestRuntimeFileName", JsonPrimitive(compiled.name))
        put("compiledNodeTestRuntimeBytes", JsonPrimitive(compiled.length()))
        put("compiledNodeTestRuntimeSha256", JsonPrimitive(compiled.releaseDigest()))
        put(
            "windowsSupervisorSha256",
            values.windowsSupervisorSha256?.let(::JsonPrimitive) ?: JsonNull,
        )
        put("result", JsonPrimitive("passed"))
    }
}

internal fun validateNodeRuntimeEvidence(
    evidenceFiles: List<File>,
    expectedCommit: String,
    distributionManifest: File,
    classifierArchives: List<File>,
    compiledNodeTestRuntime: File,
    windowsSupervisor: File?,
): List<String> = buildList {
    if (!expectedCommit.matches(Regex("[0-9a-f]{40}"))) add("candidate commit is not immutable")
    val manifest = runCatching { readDesktopCodexManifest(distributionManifest) }
        .getOrElse { add("distribution manifest: ${it.message}"); return@buildList }
    if (manifest.distributions.map(DesktopCodexDistributionSpec::target).toSet() !=
        desktopRuntimeEvidenceTargets.keys) add("distribution target set mismatch")
    if (!compiledNodeTestRuntime.isFile || compiledNodeTestRuntime.length() == 0L) {
        add("compiled Node test/runtime artifact is missing")
    } else runCatching { inspectNodeRuntimeRunnerArchive(compiledNodeTestRuntime) }
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
    val expectedNames = desktopRuntimeEvidenceTargets.keys.map(::nodeRuntimeEvidenceFileName).toSet()
    if (evidenceFiles.size != expectedNames.size || byName.keys != expectedNames) add("evidence file set mismatch")
    val compiledDigest = compiledNodeTestRuntime.takeIf(File::isFile)?.releaseDigest()
    val supervisorDigest = windowsSupervisor?.takeIf(File::isFile)?.releaseDigest()

    desktopRuntimeEvidenceTargets.forEach { (target, expected) ->
        val file = byName[nodeRuntimeEvidenceFileName(target)] ?: return@forEach
        runCatching {
            val report = file.readReleaseObject()
            check(report.keys == NODE_RUNTIME_EVIDENCE_KEYS) { "schema fields mismatch" }
            check(report.releaseInt("schemaVersion") == 1) { "schema version mismatch" }
            check(report.releaseString("candidateCommit") == expectedCommit) { "commit mismatch" }
            check(report.releaseString("target") == target) { "target mismatch" }
            check(report.releaseString("classifier") == expected.classifier) { "classifier mismatch" }
            check(report.releaseString("runnerOs") == expected.runnerOs) { "runner OS mismatch" }
            check(report.releaseString("runnerArch") == expected.runnerArch) { "runner architecture mismatch" }
            check(report.releaseString("nodeVersion") == PINNED_NODE_VERSION) { "Node version mismatch" }
            check(report.releaseString("testTask") == nodeRuntimeEvidenceTestTask(target)) { "test task mismatch" }
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
            check(isSafeNodeEvidenceName(report.releaseString("compiledNodeTestRuntimeFileName"))) {
                "compiled artifact filename is unsafe"
            }
            check(report.releaseLong("compiledNodeTestRuntimeBytes") == compiledNodeTestRuntime.length() &&
                report.releaseString("compiledNodeTestRuntimeSha256") == compiledDigest) {
                "compiled Node test/runtime artifact mismatch"
            }
            val recordedSupervisor = report.releaseStringOrNull("windowsSupervisorSha256")
            if (target == "mingwX64") {
                check(supervisorDigest != null && recordedSupervisor == supervisorDigest) {
                    "Windows supervisor hash mismatch"
                }
            } else {
                check(recordedSupervisor == null) { "non-Windows evidence contains a supervisor hash" }
            }
        }.exceptionOrNull()?.let { add("$target: ${it.message}") }
    }
}

private val NODE_RUNTIME_EVIDENCE_KEYS = setOf(
    "schemaVersion", "candidateCommit", "target", "classifier", "runnerOs", "runnerArch",
    "nodeVersion", "testTask", "testClass", "testMethods", "tests", "skipped", "failures", "errors",
    "classifierArchiveFileName", "classifierArchiveBytes", "classifierArchiveSha256",
    "appServerBinarySha256", "compiledNodeTestRuntimeFileName", "compiledNodeTestRuntimeBytes",
    "compiledNodeTestRuntimeSha256", "windowsSupervisorSha256", "result",
)

private fun isSafeNodeEvidenceName(value: String): Boolean =
    value == File(value).name && '/' !in value && '\\' !in value

internal fun inspectNodeRuntimeRunnerArchive(archive: File): List<String> {
    check(archive.isFile && archive.length() > 0 && archive.name == NODE_RUNTIME_RUNNER_ARCHIVE) {
        "Compiled Node runtime archive is missing or misnamed"
    }
    return ZipFile(archive).use { zip ->
        val entries = zip.entries().asSequence().toList()
        val names = entries.map(ZipEntry::getName)
        check(entries.isNotEmpty() && entries.none(ZipEntry::isDirectory) && names.toSet().size == names.size) {
            "Compiled Node runtime archive has invalid members"
        }
        check(names.all { it == File(it).name && '/' !in it && '\\' !in it && it.endsWith(".js") }) {
            "Compiled Node runtime archive members must be root JavaScript files"
        }
        check(NODE_RUNTIME_RUNNER_ENTRY in names) { "Compiled Node runtime entry is missing" }
        entries.forEach { entry ->
            check(zip.getInputStream(entry).use { it.read() } != -1) {
                "Compiled Node runtime member is empty: ${entry.name}"
            }
        }
        names.sorted()
    }
}
