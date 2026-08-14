import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val JVM_RUNTIME_RUNNER_ARCHIVE = "codex-agent-jvm-runtime-evidence-runner.zip"
internal const val JVM_RUNTIME_RUNNER_ENTRYPOINT =
    "io.github.ciurlaro.codexmobile.appserver.runtime.JvmRuntimeEvidenceMain"
internal const val JVM_RUNTIME_TEST_TASK = ":codex-agent-runtime-desktop:jvmTest"

fun jvmRuntimeEvidenceFileName(target: String) = "jvm-runtime-$target.json"

internal data class JvmRuntimeEvidenceValues(
    val candidateCommit: String,
    val target: String,
    val classifier: DesktopClassifierProof,
    val compiledJvmTestRuntime: File,
)

internal fun buildJvmRuntimeEvidence(values: JvmRuntimeEvidenceValues): JsonObject {
    val expected = desktopRuntimeEvidenceTargets.getValue(values.target)
    val classifier = values.classifier
    check(classifier.target == values.target && classifier.classifier == expected.classifier) {
        "JVM evidence classifier does not match ${values.target}"
    }
    inspectJvmRuntimeRunnerArchive(values.compiledJvmTestRuntime)
    return buildJsonObject {
        put("schemaVersion", JsonPrimitive(1))
        put("candidateCommit", JsonPrimitive(values.candidateCommit))
        put("target", JsonPrimitive(values.target))
        put("classifier", JsonPrimitive(expected.classifier))
        put("runnerOs", JsonPrimitive(expected.runnerOs))
        put("runnerArch", JsonPrimitive(expected.runnerArch))
        put("testTask", JsonPrimitive(JVM_RUNTIME_TEST_TASK))
        put("testClass", JsonPrimitive(DESKTOP_RUNTIME_TEST_CLASS))
        put("testMethods", buildJsonArray { desktopRuntimeTestMethods.forEach { add(JsonPrimitive(it)) } })
        put("tests", JsonPrimitive(desktopRuntimeTestMethods.size))
        put("skipped", JsonPrimitive(0))
        put("failures", JsonPrimitive(0))
        put("errors", JsonPrimitive(0))
        put("classifierArchiveFileName", JsonPrimitive(classifier.archiveFile.name))
        put("classifierArchiveBytes", JsonPrimitive(classifier.archiveBytes))
        put("classifierArchiveSha256", JsonPrimitive(classifier.archiveSha256))
        put("appServerBinarySha256", JsonPrimitive(classifier.binarySha256))
        put("supervisorBinarySha256", JsonPrimitive(classifier.supervisorSha256))
        put("compiledJvmTestRuntimeFileName", JsonPrimitive(values.compiledJvmTestRuntime.name))
        put("compiledJvmTestRuntimeBytes", JsonPrimitive(values.compiledJvmTestRuntime.length()))
        put("compiledJvmTestRuntimeSha256", JsonPrimitive(values.compiledJvmTestRuntime.releaseDigest()))
        put("result", JsonPrimitive("passed"))
    }
}

internal fun validateJvmRuntimeEvidence(
    evidenceFiles: List<File>,
    expectedCommit: String,
    distributionManifest: File,
    classifierArchives: List<File>,
    compiledJvmTestRuntime: File,
): List<String> = buildList {
    if (!expectedCommit.matches(Regex("[0-9a-f]{40}"))) add("candidate commit is not immutable")
    val manifest = runCatching { readDesktopCodexManifest(distributionManifest) }
        .getOrElse { add("distribution manifest: ${it.message}"); return@buildList }
    if (classifierArchives.size != desktopRuntimeEvidenceTargets.size) add("classifier archive set mismatch")
    val classifiers = desktopRuntimeEvidenceTargets.keys.mapNotNull { target ->
        val matches = classifierArchives.mapNotNull { archive ->
            runCatching { inspectDesktopClassifier(target, manifest, archive) }.getOrNull()
        }
        if (matches.size != 1) {
            add("$target: expected exactly one matching classifier archive")
            null
        } else matches.single()
    }.associateBy(DesktopClassifierProof::target)
    val runnerDigest = if (compiledJvmTestRuntime.isFile) {
        runCatching { inspectJvmRuntimeRunnerArchive(compiledJvmTestRuntime) }
            .exceptionOrNull()?.let { add("compiled JVM test runtime: ${it.message}") }
        compiledJvmTestRuntime.releaseDigest()
    } else {
        add("compiled JVM test runtime is missing")
        null
    }
    val byName = evidenceFiles.associateBy(File::getName)
    val expectedNames = desktopRuntimeEvidenceTargets.keys.map(::jvmRuntimeEvidenceFileName).toSet()
    if (evidenceFiles.size != expectedNames.size || byName.keys != expectedNames) add("evidence file set mismatch")

    desktopRuntimeEvidenceTargets.forEach { (target, expected) ->
        val file = byName[jvmRuntimeEvidenceFileName(target)] ?: return@forEach
        runCatching {
            val report = file.readReleaseObject()
            check(report.keys == JVM_RUNTIME_EVIDENCE_KEYS) { "schema fields mismatch" }
            check(report.releaseInt("schemaVersion") == 1) { "schema version mismatch" }
            check(report.releaseString("candidateCommit") == expectedCommit) { "commit mismatch" }
            check(report.releaseString("target") == target) { "target mismatch" }
            check(report.releaseString("classifier") == expected.classifier) { "classifier mismatch" }
            check(report.releaseString("runnerOs") == expected.runnerOs) { "runner OS mismatch" }
            check(report.releaseString("runnerArch") == expected.runnerArch) { "runner architecture mismatch" }
            check(report.releaseString("testTask") == JVM_RUNTIME_TEST_TASK) { "test task mismatch" }
            check(report.releaseString("testClass") == DESKTOP_RUNTIME_TEST_CLASS) { "test class mismatch" }
            check(report.releaseArray("testMethods").map { it.jsonPrimitive.content }.toSet() ==
                desktopRuntimeTestMethods) { "test methods mismatch" }
            check(report.releaseInt("tests") == desktopRuntimeTestMethods.size &&
                report.releaseInt("skipped") == 0 && report.releaseInt("failures") == 0 &&
                report.releaseInt("errors") == 0 && report.releaseString("result") == "passed") {
                "test result mismatch"
            }
            val proof = classifiers.getValue(target)
            val archiveName = report.releaseString("classifierArchiveFileName")
            check(archiveName == File(archiveName).name && '/' !in archiveName && '\\' !in archiveName) {
                "classifier archive filename is unsafe"
            }
            check(report.releaseLong("classifierArchiveBytes") == proof.archiveBytes &&
                report.releaseString("classifierArchiveSha256") == proof.archiveSha256) {
                "classifier archive mismatch"
            }
            check(report.releaseString("appServerBinarySha256") == proof.binarySha256) {
                "App Server hash mismatch"
            }
            check(report.releaseString("supervisorBinarySha256") == proof.supervisorSha256) {
                "supervisor hash mismatch"
            }
            check(report.releaseString("compiledJvmTestRuntimeFileName") == JVM_RUNTIME_RUNNER_ARCHIVE &&
                report.releaseLong("compiledJvmTestRuntimeBytes") == compiledJvmTestRuntime.length() &&
                report.releaseString("compiledJvmTestRuntimeSha256") == runnerDigest) {
                "compiled JVM test runtime mismatch"
            }
        }.exceptionOrNull()?.let { add("$target: ${it.message}") }
    }
}

internal fun inspectJvmRuntimeRunnerArchive(archive: File): List<String> {
    check(archive.isFile && archive.length() > 0 && archive.name == JVM_RUNTIME_RUNNER_ARCHIVE) {
        "Compiled JVM runtime archive is missing or misnamed"
    }
    return ZipFile(archive).use { zip ->
        val entries = zip.entries().asSequence().filterNot(ZipEntry::isDirectory).toList()
        val names = entries.map(ZipEntry::getName)
        check(entries.isNotEmpty() && names.toSet().size == names.size) {
            "Compiled JVM runtime archive has invalid members"
        }
        check(names.all { name ->
            (name.startsWith("classes/") || name.startsWith("lib/")) && '\\' !in name &&
                name.split('/').none { it.isEmpty() || it == "." || it == ".." }
        }) { "Compiled JVM runtime archive has an unsafe layout" }
        val entryClass = "classes/${JVM_RUNTIME_RUNNER_ENTRYPOINT.replace('.', '/')}.class"
        check(entryClass in names) { "Compiled JVM runtime entrypoint is missing" }
        check(names.any { it.startsWith("lib/") && it.endsWith(".jar") }) {
            "Compiled JVM runtime dependencies are missing"
        }
        entries.forEach { entry ->
            check(zip.getInputStream(entry).use { it.read() } != -1) {
                "Compiled JVM runtime member is empty: ${entry.name}"
            }
        }
        names.sorted()
    }
}

private val JVM_RUNTIME_EVIDENCE_KEYS = setOf(
    "schemaVersion", "candidateCommit", "target", "classifier", "runnerOs", "runnerArch",
    "testTask", "testClass", "testMethods", "tests", "skipped", "failures", "errors",
    "classifierArchiveFileName", "classifierArchiveBytes", "classifierArchiveSha256",
    "appServerBinarySha256", "supervisorBinarySha256", "compiledJvmTestRuntimeFileName",
    "compiledJvmTestRuntimeBytes", "compiledJvmTestRuntimeSha256", "result",
)
