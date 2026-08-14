import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

internal data class JvmEvidenceProcessResult(val exitCode: Int, val output: String)

internal fun executeJvmRuntimeEvidence(
    candidateCommit: String,
    target: String,
    runnerOs: String,
    runnerArch: String,
    javaExecutable: String,
    distributionManifest: File,
    classifierArchive: File,
    compiledJvmTestRuntime: File,
    evidenceFile: File,
    runner: (List<String>, Map<String, String>) -> JvmEvidenceProcessResult = ::runJvmEvidenceProcess,
) {
    evidenceFile.delete()
    check(candidateCommit.matches(Regex("[0-9a-f]{40}"))) { "JVM evidence commit is not immutable" }
    val expected = desktopRuntimeEvidenceTargets.getValue(target)
    check(runnerOs == expected.runnerOs && runnerArch == expected.runnerArch) {
        "JVM evidence runner does not match $target"
    }
    check(evidenceFile.name == jvmRuntimeEvidenceFileName(target)) { "JVM evidence filename mismatch" }
    check(javaExecutable.isNotBlank() && '\n' !in javaExecutable && '\r' !in javaExecutable) {
        "Java executable is invalid"
    }
    val classifier = inspectDesktopClassifier(
        target,
        readDesktopCodexManifest(distributionManifest),
        classifierArchive,
    )
    inspectJvmRuntimeRunnerArchive(compiledJvmTestRuntime)
    val temporary = Files.createTempDirectory("codex-agent-jvm-evidence-$target").toFile().canonicalFile
    try {
        val appServer = extractClassifierMember(classifierArchive, classifier.executableName, temporary)
        val supervisor = extractClassifierMember(classifierArchive, classifier.supervisorExecutableName, temporary)
        check(appServer.releaseDigest() == classifier.binarySha256) { "Extracted App Server hash mismatch" }
        check(supervisor.releaseDigest() == classifier.supervisorSha256) { "Extracted supervisor hash mismatch" }
        if (target != "mingwX64") {
            check(appServer.setExecutable(true, false)) { "App Server could not be made executable" }
            check(supervisor.setExecutable(true, false)) { "Process supervisor could not be made executable" }
        }
        val runnerRoot = temporary.resolve("runner")
        extractJvmRunner(compiledJvmTestRuntime, runnerRoot)
        val separator = if (target == "mingwX64") ";" else ":"
        val classpath = "${runnerRoot.resolve("classes")}$separator${runnerRoot.resolve("lib/*")}"
        val baseCommand = listOf(javaExecutable, "-cp", classpath, JVM_RUNTIME_RUNNER_ENTRYPOINT)
        val environment = mapOf(
            "CODEX_AGENT_APP_SERVER_EXECUTABLE" to appServer.absolutePath,
            "CODEX_AGENT_PROCESS_SUPERVISOR_EXECUTABLE" to supervisor.absolutePath,
            "CODEX_AGENT_PROCESS_SUPERVISOR_SHA256" to classifier.supervisorSha256,
            "CODEX_AGENT_DESKTOP_TARGET" to target,
        )
        val listing = runner(baseCommand + "--list-tests", environment)
        check(listing.exitCode == 0) { "JVM test discovery failed: ${listing.output}" }
        verifyJvmTestListing(listing.output)
        desktopRuntimeTestMethods.forEach { method ->
            val result = runner(
                baseCommand + "--run-test=$DESKTOP_RUNTIME_TEST_CLASS.$method",
                environment,
            )
            check(result.exitCode == 0) { "JVM runtime test failed ($method): ${result.output}" }
        }
        evidenceFile.atomicWriteJson(buildJvmRuntimeEvidence(JvmRuntimeEvidenceValues(
            candidateCommit,
            target,
            classifier,
            compiledJvmTestRuntime,
        )))
    } finally {
        temporary.deleteRecursively()
    }
}

private fun extractClassifierMember(archive: File, name: String, destination: File): File =
    destination.resolve(name).also { output ->
        ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry(name)).use { input ->
                Files.copy(input, output.toPath(), REPLACE_EXISTING)
            }
        }
    }

private fun extractJvmRunner(archive: File, destination: File) {
    val members = inspectJvmRuntimeRunnerArchive(archive)
    ZipFile(archive).use { zip ->
        members.forEach { name ->
            val output = destination.resolve(name)
            output.parentFile.mkdirs()
            zip.getInputStream(zip.getEntry(name)).use { input ->
                Files.copy(input, output.toPath(), REPLACE_EXISTING)
            }
        }
    }
}

internal fun verifyJvmTestListing(output: String) {
    val actual = output.replace("\r", "").lineSequence().filter(String::isNotBlank).toList()
    val expected = listOf("$DESKTOP_RUNTIME_TEST_CLASS.") + desktopRuntimeTestMethods.map { "  $it" }
    check(actual == expected) { "JVM test inventory is incomplete or unexpected" }
}

private fun runJvmEvidenceProcess(
    command: List<String>,
    environment: Map<String, String>,
): JvmEvidenceProcessResult {
    val log = Files.createTempFile("jvm-runtime-evidence", ".log").toFile()
    return try {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(log)
            .apply { environment().putAll(environment) }
            .start()
        val completed = process.waitFor(5, TimeUnit.MINUTES)
        if (!completed) process.destroyForcibly().waitFor()
        JvmEvidenceProcessResult(if (completed) process.exitValue() else -1, log.readText())
    } finally {
        log.delete()
    }
}
