import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

data class DesktopRuntimeEvidenceTarget(
    val classifier: String,
    val runnerOs: String,
    val runnerArch: String,
)

val desktopRuntimeEvidenceTargets = linkedMapOf(
    "macosArm64" to DesktopRuntimeEvidenceTarget("app-server-macos-arm64", "macOS", "ARM64"),
    "macosX64" to DesktopRuntimeEvidenceTarget("app-server-macos-x64", "macOS", "X64"),
    "linuxArm64" to DesktopRuntimeEvidenceTarget("app-server-linux-arm64", "Linux", "ARM64"),
    "linuxX64" to DesktopRuntimeEvidenceTarget("app-server-linux-x64", "Linux", "X64"),
    "mingwX64" to DesktopRuntimeEvidenceTarget("app-server-windows-x64", "Windows", "X64"),
)

internal const val DESKTOP_RUNTIME_TEST_CLASS =
    "io.github.ciurlaro.codexmobile.appserver.runtime.DesktopCodexRuntimeTest"
internal val desktopRuntimeTestMethods = sortedSetOf(
    "closeDuringStartClosesNewProcessExactlyOnce",
    "initializesAndShutsDownOfficialAppServerWhenProvided",
    "rejectsRelativeExecutableBeforeStarting",
    "rejectsWrongTargetChecksum",
)

fun desktopRuntimeEvidenceFileName(target: String) = "desktop-runtime-$target.json"

internal fun desktopRuntimeEvidenceTestTask(target: String) = if (target == "linuxArm64") {
    LINUX_ARM64_RUNTIME_EVIDENCE_TASK
} else {
    ":codex-agent-runtime-desktop:${target}Test"
}

internal data class DesktopRuntimeEvidenceValues(
    val candidateCommit: String,
    val target: String,
    val binarySha256: String,
    val supervisorSha256: String,
    val classifierArchiveSha256: String,
)

internal fun buildDesktopRuntimeEvidence(values: DesktopRuntimeEvidenceValues) = buildJsonObject {
    val expected = desktopRuntimeEvidenceTargets.getValue(values.target)
    put("schemaVersion", JsonPrimitive(3))
    put("candidateCommit", JsonPrimitive(values.candidateCommit))
    put("target", JsonPrimitive(values.target))
    put("classifier", JsonPrimitive(expected.classifier))
    put("runnerOs", JsonPrimitive(expected.runnerOs))
    put("runnerArch", JsonPrimitive(expected.runnerArch))
    put("testTask", JsonPrimitive(desktopRuntimeEvidenceTestTask(values.target)))
    put("testClass", JsonPrimitive(DESKTOP_RUNTIME_TEST_CLASS))
    put("testMethods", buildJsonArray { desktopRuntimeTestMethods.forEach { add(JsonPrimitive(it)) } })
    put("tests", JsonPrimitive(desktopRuntimeTestMethods.size))
    put("skipped", JsonPrimitive(0))
    put("failures", JsonPrimitive(0))
    put("errors", JsonPrimitive(0))
    put("binarySha256", JsonPrimitive(values.binarySha256))
    put("supervisorSha256", JsonPrimitive(values.supervisorSha256))
    put("classifierArchiveSha256", JsonPrimitive(values.classifierArchiveSha256))
    put("result", JsonPrimitive("passed"))
}

@DisableCachingByDefault(because = "Platform smoke evidence must execute for every immutable candidate")
abstract class RecordDesktopRuntimeEvidenceTask : DefaultTask() {
    @get:Input abstract val target: Property<String>
    @get:Input abstract val classifier: Property<String>
    @get:Input abstract val binarySha256: Property<String>
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val runnerOs: Property<String>
    @get:Input abstract val runnerArch: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val classifierArchive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val distributionManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val testReport: RegularFileProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun record() {
        val targetName = target.get()
        val commit = candidateCommit.get()
        check(commit.matches(Regex("[0-9a-f]{40}"))) { "Desktop evidence commit is not immutable" }
        verifyDesktopRuntimeTestReport(testReport.get().asFile, targetName)
        val expected = desktopRuntimeEvidenceTargets.getValue(targetName)
        check(classifier.get() == expected.classifier) { "Desktop evidence classifier mismatch" }
        check(runnerOs.get() == expected.runnerOs && runnerArch.get() == expected.runnerArch) {
            "Desktop evidence runner does not match $targetName"
        }
        val proof = inspectDesktopClassifier(
            targetName,
            readDesktopCodexManifest(distributionManifest.get().asFile),
            classifierArchive.get().asFile,
        )
        check(binarySha256.get() == proof.binarySha256) { "Desktop evidence App Server hash mismatch" }
        evidenceFile.get().asFile.atomicWriteJson(buildDesktopRuntimeEvidence(DesktopRuntimeEvidenceValues(
            commit,
            targetName,
            proof.binarySha256,
            proof.supervisorSha256,
            proof.archiveSha256,
        )))
    }
}

internal fun validateDesktopRuntimeEvidence(
    files: List<File>,
    expectedCommit: String,
    version: String? = null,
    mavenInventory: File? = null,
    distributionManifest: File? = null,
    classifierArchives: List<File> = emptyList(),
): List<String> = buildList {
    val byName = files.associateBy(File::getName)
    val expectedNames = desktopRuntimeEvidenceTargets.keys.map(::desktopRuntimeEvidenceFileName).toSet()
    if (byName.keys != expectedNames) add("file set mismatch")
    val inventoryFiles = mavenInventory?.readReleaseObject()?.releaseArray("files")
        ?.associate { record ->
            val value = record as kotlinx.serialization.json.JsonObject
            value.releaseString("path") to value.releaseString("sha256")
        }.orEmpty()
    val distributions = distributionManifest?.let(::readDesktopCodexManifest)?.distributions
        ?.associateBy(DesktopCodexDistributionSpec::target).orEmpty()
    if (distributionManifest != null && distributions.keys != desktopRuntimeEvidenceTargets.keys) {
        add("distribution target set mismatch")
    }
    val classifierProofs = if (distributionManifest == null) emptyMap() else {
        val manifest = readDesktopCodexManifest(distributionManifest)
        desktopRuntimeEvidenceTargets.keys.mapNotNull { target ->
            val matches = classifierArchives.mapNotNull { archive ->
                runCatching { inspectDesktopClassifier(target, manifest, archive) }.getOrNull()
            }
            if (classifierArchives.isNotEmpty() && matches.size != 1) {
                add("$target: expected exactly one matching classifier archive")
                null
            } else matches.singleOrNull()
        }.associateBy(DesktopClassifierProof::target)
    }
    desktopRuntimeEvidenceTargets.forEach { (target, expected) ->
        val file = byName[desktopRuntimeEvidenceFileName(target)] ?: return@forEach
        runCatching {
            val report = file.readReleaseObject()
            check(report.keys == setOf(
                "schemaVersion", "candidateCommit", "target", "classifier", "runnerOs", "runnerArch",
                "testTask", "testClass", "testMethods", "tests", "skipped", "failures", "errors", "binarySha256",
                "supervisorSha256", "classifierArchiveSha256", "result",
            )) { "schema fields mismatch" }
            check(report.releaseInt("schemaVersion") == 3) { "schema version mismatch" }
            check(report.releaseString("candidateCommit") == expectedCommit) { "commit mismatch" }
            check(report.releaseString("target") == target) { "target mismatch" }
            check(report.releaseString("classifier") == expected.classifier) { "classifier mismatch" }
            check(report.releaseString("runnerOs") == expected.runnerOs) { "runner OS mismatch" }
            check(report.releaseString("runnerArch") == expected.runnerArch) { "runner architecture mismatch" }
            check(report.releaseString("testTask") == desktopRuntimeEvidenceTestTask(target)) {
                "test task mismatch"
            }
            check(report.releaseString("testClass") == DESKTOP_RUNTIME_TEST_CLASS) { "test class mismatch" }
            check(report.releaseArray("testMethods").map { it.toString().trim('"') }.toSet() ==
                desktopRuntimeTestMethods) { "test methods mismatch" }
            check(report.releaseInt("tests") == desktopRuntimeTestMethods.size && report.releaseInt("skipped") == 0 &&
                report.releaseInt("failures") == 0 && report.releaseInt("errors") == 0) {
                "test result mismatch"
            }
            check(report.releaseString("result") == "passed") { "result mismatch" }
            val binaryHash = report.releaseString("binarySha256")
            check(binaryHash.matches(Regex("[0-9a-f]{64}"))) { "binary hash invalid" }
            if (distributionManifest != null) {
                val distribution = distributions.getValue(target)
                check(distribution.classifier == expected.classifier) { "distribution classifier mismatch" }
                check(distribution.binarySha256 == binaryHash) { "binary hash is not pinned by distribution manifest" }
            }
            val archiveHash = report.releaseString("classifierArchiveSha256")
            check(archiveHash.matches(Regex("[0-9a-f]{64}"))) { "classifier hash invalid" }
            val supervisorHash = report.releaseString("supervisorSha256")
            check(supervisorHash.matches(Regex("[0-9a-f]{64}"))) { "supervisor hash invalid" }
            classifierProofs[target]?.let { proof ->
                check(proof.binarySha256 == binaryHash) { "classifier App Server hash mismatch" }
                check(proof.supervisorSha256 == supervisorHash) { "classifier supervisor hash mismatch" }
                check(proof.archiveSha256 == archiveHash) { "classifier archive hash mismatch" }
            }
            if (version != null && mavenInventory != null) {
                val path = "io/github/ciurlaro/codex-agent-runtime-desktop/$version/" +
                    "codex-agent-runtime-desktop-$version-${expected.classifier}.zip"
                check(inventoryFiles[path] == archiveHash) { "classifier hash is not bound to Maven inventory" }
            }
        }.exceptionOrNull()?.let { add("$target: ${it.message}") }
    }
}

internal fun verifyDesktopRuntimeTestReport(file: File, target: String) {
    val suite = secureDocumentBuilderFactory(namespaceAware = true).newDocumentBuilder().parse(file).documentElement
    check(suite.tagName == "testsuite") { "Desktop test report has no testsuite root" }
    check(suite.getAttribute("tests").toInt() == desktopRuntimeTestMethods.size &&
        suite.getAttribute("skipped").toInt() == 0 && suite.getAttribute("failures").toInt() == 0 &&
        suite.getAttribute("errors").toInt() == 0) {
        "Desktop smoke must run all exact tests without skips or failures"
    }
    val cases = suite.getElementsByTagName("testcase").let { nodes ->
        (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
    }
    val expectedClass = "${target}Test.$DESKTOP_RUNTIME_TEST_CLASS"
    check(cases.size == desktopRuntimeTestMethods.size &&
        cases.map { it.getAttribute("classname") }.toSet() == setOf(expectedClass)) {
        "Desktop smoke test class is incomplete or unexpected"
    }
    check(cases.map { it.getAttribute("name").substringBefore('[') }.toSet() == desktopRuntimeTestMethods) {
        "Desktop smoke test methods are incomplete or unexpected"
    }
}
