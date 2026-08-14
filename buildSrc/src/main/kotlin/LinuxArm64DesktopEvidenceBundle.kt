import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val LINUX_ARM64_TARGET = "linuxArm64"
private const val LINUX_ARM64_CLASSIFIER = "app-server-linux-arm64"
private const val METADATA_PATH = "execution.json"
private const val TEST_PATH = "linuxArm64-test.kexe"
private const val CLASSIFIER_PATH = "app-server-linux-arm64.zip"
private const val APP_SERVER_PATH = "codex-app-server"
private const val SUPERVISOR_PATH = "codex-process-supervisor"
private val EXECUTION_PATHS = setOf(METADATA_PATH, TEST_PATH, CLASSIFIER_PATH, APP_SERVER_PATH, SUPERVISOR_PATH)
private val ZIP_EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0)

internal data class DesktopEvidenceProcessResult(val exitCode: Int, val output: String)
internal fun stageLinuxArm64DesktopEvidenceBundle(
    candidateCommit: String,
    testExecutable: File,
    classifierArchive: File,
    output: File,
) {
    requireCommit(candidateCommit)
    val exactClassifier = resolveLinuxArm64Classifier(classifierArchive)
    check(testExecutable.isFile) { "Linux ARM64 test executable is missing" }
    ZipFile(exactClassifier).use { classifier ->
        val entries = classifier.safeFiles()
        check(entries.map(ZipEntry::getName).toSet() == setOf(
            APP_SERVER_PATH, SUPERVISOR_PATH, "openai-codex-LICENSE.txt", "openai-codex-NOTICE.txt",
        )) { "Linux ARM64 classifier member set is invalid" }
        val appServer = classifier.getEntry(APP_SERVER_PATH)
        val supervisor = classifier.getEntry(SUPERVISOR_PATH)
        val metadata = LinuxArmExecutionMetadata(
            candidateCommit,
            APP_SERVER_PATH,
            SUPERVISOR_PATH,
            testExecutable.member(TEST_PATH),
            exactClassifier.member(CLASSIFIER_PATH),
            classifier.getInputStream(appServer).use { input ->
                LinuxArmExecutionMember(APP_SERVER_PATH, appServer.size, input.releaseDigest())
            },
            classifier.getInputStream(supervisor).use { input ->
                LinuxArmExecutionMember(SUPERVISOR_PATH, supervisor.size, input.releaseDigest())
            },
        )
        val metadataBytes = metadata.jsonBytes()
        val temporary = Files.createTempFile(output.parentFile.apply(File::mkdirs).toPath(), ".${output.name}-", ".tmp")
        try {
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { zip ->
                zip.setLevel(9)
                zip.add(METADATA_PATH, ByteArrayInputStream(metadataBytes))
                zip.add(TEST_PATH, testExecutable.inputStream())
                zip.add(CLASSIFIER_PATH, exactClassifier.inputStream())
                zip.add(APP_SERVER_PATH, classifier.getInputStream(appServer))
                zip.add(SUPERVISOR_PATH, classifier.getInputStream(supervisor))
            }
            try { Files.move(temporary, output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temporary, output.toPath(), REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}

internal fun resolveLinuxArm64Classifier(input: File): File {
    if (input.isFile) return input
    check(input.isDirectory) { "Linux ARM64 classifier input is missing" }
    val matches = input.listFiles().orEmpty().filter { file ->
        file.isFile && file.name.startsWith("codex-agent-runtime-desktop-") &&
            file.name.endsWith("-$LINUX_ARM64_CLASSIFIER.zip") && file.canonicalFile.parentFile == input.canonicalFile
    }
    check(matches.size == 1) { "Linux ARM64 distributions directory must contain exactly one classifier archive" }
    return matches.single()
}

internal fun executeLinuxArm64DesktopEvidenceBundle(
    candidateCommit: String,
    bundle: File,
    evidence: File,
    report: File,
    environment: Map<String, String> = System.getenv(),
    runner: (List<String>, Map<String, String>) -> DesktopEvidenceProcessResult = ::runDesktopEvidenceProcess,
) {
    requireCommit(candidateCommit)
    check(environment["RUNNER_OS"] == "Linux" && environment["RUNNER_ARCH"] == "ARM64") {
        "Linux ARM64 evidence must run on RUNNER_OS=Linux and RUNNER_ARCH=ARM64"
    }
    check(evidence.name == desktopRuntimeEvidenceFileName(LINUX_ARM64_TARGET)) { "Desktop evidence filename mismatch" }
    check(report.name == desktopRuntimeTestReportName()) { "Desktop test report filename mismatch" }
    val temporary = Files.createTempDirectory("codex-agent-linux-arm64-evidence").toFile()
    try {
        val metadata = extractExecutionBundle(bundle, temporary)
        check(metadata.commit == candidateCommit) { "Linux ARM64 evidence commit mismatch" }
        val test = temporary.resolve(TEST_PATH)
        val appServer = temporary.resolve(APP_SERVER_PATH)
        val supervisor = temporary.resolve(SUPERVISOR_PATH)
        val classifier = temporary.resolve(CLASSIFIER_PATH)
        check(test.setExecutable(true, false) && appServer.setExecutable(true, false) &&
            supervisor.setExecutable(true, false)) {
            "Linux ARM64 evidence executables could not be enabled"
        }
        verifyClassifier(classifier, metadata)
        val processEnvironment = mapOf(
            "CODEX_AGENT_APP_SERVER_EXECUTABLE" to appServer.absolutePath,
            "CODEX_AGENT_PROCESS_SUPERVISOR_EXECUTABLE" to supervisor.absolutePath,
            "CODEX_AGENT_PROCESS_SUPERVISOR_SHA256" to metadata.supervisor.sha256,
        )
        val listing = runner(listOf(test.absolutePath, "--ktest_list_tests"), processEnvironment)
        check(listing.exitCode == 0) { "Linux ARM64 test discovery failed: ${listing.output}" }
        verifyTestListing(listing.output)
        desktopRuntimeTestMethods.forEach { method ->
            val result = runner(listOf(
                test.absolutePath,
                "--ktest_filter=$DESKTOP_RUNTIME_TEST_CLASS.$method",
                "--ktest_logger=SILENT",
            ), processEnvironment)
            check(result.exitCode == 0) { "Linux ARM64 desktop test failed ($method): ${result.output}" }
        }
        writeTestReport(report)
        verifyDesktopRuntimeTestReport(report, LINUX_ARM64_TARGET)
        evidence.atomicWriteJson(buildDesktopRuntimeEvidence(DesktopRuntimeEvidenceValues(
            candidateCommit,
            LINUX_ARM64_TARGET,
            metadata.appServer.sha256,
            metadata.supervisor.sha256,
            metadata.classifier.sha256,
        )))
    } finally { temporary.deleteRecursively() }
}

private fun extractExecutionBundle(bundle: File, destination: File): LinuxArmExecutionMetadata = ZipFile(bundle).use { zip ->
    val entries = zip.safeFiles()
    check(entries.map(ZipEntry::getName).toSet() == EXECUTION_PATHS) { "Linux ARM64 execution bundle member set is invalid" }
    val metadataEntry = zip.getEntry(METADATA_PATH)
    check(metadataEntry.size in 1..65_536) { "Linux ARM64 execution metadata size is invalid" }
    val metadata = zip.getInputStream(metadataEntry).use { it.readBytes() }.decodeToString().linuxArmExecutionMetadata()
    listOf(metadata.test, metadata.classifier, metadata.appServer, metadata.supervisor).forEach { member ->
        val entry = zip.getEntry(member.path)
        check(entry.size == member.bytes) { "Linux ARM64 bundle size mismatch: ${member.path}" }
        val output = destination.resolve(member.path)
        zip.getInputStream(entry).use { input -> Files.copy(input, output.toPath(), REPLACE_EXISTING) }
        check(output.releaseDigest() == member.sha256) { "Linux ARM64 bundle hash mismatch: ${member.path}" }
    }
    metadata
}

private fun File.member(path: String) = LinuxArmExecutionMember(path, length(), releaseDigest())
private fun ZipFile.safeFiles(): List<ZipEntry> {
    val entries = entries().asSequence().toList()
    check(entries.none(ZipEntry::isDirectory) && entries.size == entries.map(ZipEntry::getName).toSet().size &&
        entries.all { it.name == File(it.name).name && '/' !in it.name && '\\' !in it.name }) {
        "Archive contains unsafe, duplicate, or directory entries"
    }
    return entries
}
private fun ZipOutputStream.add(path: String, input: java.io.InputStream) {
    putNextEntry(ZipEntry(path).apply { setTimeLocal(ZIP_EPOCH) }); input.use { it.copyTo(this) }; closeEntry()
}

private fun verifyClassifier(classifier: File, metadata: LinuxArmExecutionMetadata) = ZipFile(classifier).use { zip ->
    val entries = zip.safeFiles()
    check(entries.map(ZipEntry::getName).toSet() == setOf(
        metadata.executableName, metadata.supervisorExecutableName,
        "openai-codex-LICENSE.txt", "openai-codex-NOTICE.txt",
    )) { "Linux ARM64 classifier member set is invalid" }
    check(zip.getInputStream(zip.getEntry(metadata.executableName)).use { it.releaseDigest() } == metadata.appServer.sha256) {
        "Linux ARM64 classifier executable mismatch"
    }
    check(zip.getInputStream(zip.getEntry(metadata.supervisorExecutableName)).use { it.releaseDigest() } ==
        metadata.supervisor.sha256) { "Linux ARM64 classifier supervisor mismatch" }
}

private fun verifyTestListing(output: String) {
    val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    check(lines.firstOrNull() == "$DESKTOP_RUNTIME_TEST_CLASS." && lines.drop(1).toSet() == desktopRuntimeTestMethods &&
        lines.size == desktopRuntimeTestMethods.size + 1) { "Linux ARM64 test executable has an unexpected test set" }
}

private fun writeTestReport(report: File) {
    report.parentFile.mkdirs()
    report.writeText(buildString {
        append("<testsuite tests=\"4\" skipped=\"0\" failures=\"0\" errors=\"0\">\n")
        desktopRuntimeTestMethods.forEach { method ->
            append("  <testcase classname=\"linuxArm64Test.$DESKTOP_RUNTIME_TEST_CLASS\" name=\"").append(method).append("\"/>\n")
        }
        append("</testsuite>\n")
    })
}

private fun desktopRuntimeTestReportName() = "TEST-linuxArm64Test.$DESKTOP_RUNTIME_TEST_CLASS.xml"
private fun requireCommit(value: String) = check(value.matches(Regex("[0-9a-f]{40}"))) {
    "Linux ARM64 candidate commit is not immutable"
}

private fun runDesktopEvidenceProcess(command: List<String>, environment: Map<String, String>): DesktopEvidenceProcessResult {
    val log = Files.createTempFile("desktop-evidence-process", ".log").toFile()
    try {
        val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).apply {
            environment().putAll(environment)
        }.start()
        check(process.waitFor(5, TimeUnit.MINUTES)) {
            process.destroyForcibly(); "Linux ARM64 desktop test timed out"
        }
        return DesktopEvidenceProcessResult(process.exitValue(), log.readText())
    } finally { log.delete() }
}

fun main(arguments: Array<String>) {
    when (arguments.firstOrNull()) {
        "stage" -> { check(arguments.size == 5); stageLinuxArm64DesktopEvidenceBundle(arguments[1], File(arguments[2]), File(arguments[3]), File(arguments[4])) }
        "execute" -> { check(arguments.size == 5); executeLinuxArm64DesktopEvidenceBundle(arguments[1], File(arguments[2]), File(arguments[3]), File(arguments[4])) }
        else -> error("Expected stage or execute")
    }
}
