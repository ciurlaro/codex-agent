import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val JVM_ARM_COMMIT = "candidate-commit.txt"
private const val JVM_ARM_MANIFEST = "codex-app-server-distributions.json"
private const val JVM_ARM_CLASSIFIER = "app-server-linux-arm64.zip"
private val JVM_ARM_MEMBERS = setOf(
    JVM_ARM_COMMIT,
    JVM_ARM_MANIFEST,
    JVM_ARM_CLASSIFIER,
    JVM_RUNTIME_RUNNER_ARCHIVE,
)
private val JVM_ARM_ZIP_EPOCH = LocalDateTime.of(1980, 1, 1, 0, 0)

internal fun stageLinuxArm64JvmEvidenceBundle(
    candidateCommit: String,
    distributionManifest: File,
    classifierInput: File,
    compiledJvmTestRuntime: File,
    output: File,
) {
    requireJvmArmCommit(candidateCommit)
    val classifier = resolveLinuxArm64Classifier(classifierInput)
    inspectDesktopClassifier("linuxArm64", readDesktopCodexManifest(distributionManifest), classifier)
    inspectJvmRuntimeRunnerArchive(compiledJvmTestRuntime)
    val temporary = Files.createTempFile(output.absoluteFile.parentFile.apply(File::mkdirs).toPath(),
        ".${output.name}-", ".tmp")
    try {
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(temporary))).use { zip ->
            zip.setLevel(9)
            zip.addJvmArmMember(JVM_ARM_COMMIT, (candidateCommit + "\n").byteInputStream())
            zip.addJvmArmMember(JVM_ARM_MANIFEST, distributionManifest.inputStream())
            zip.addJvmArmMember(JVM_ARM_CLASSIFIER, classifier.inputStream())
            zip.addJvmArmMember(JVM_RUNTIME_RUNNER_ARCHIVE, compiledJvmTestRuntime.inputStream())
        }
        try {
            Files.move(temporary, output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary, output.toPath(), REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal fun executeLinuxArm64JvmEvidenceBundle(
    candidateCommit: String,
    bundle: File,
    javaExecutable: String,
    evidence: File,
    environment: Map<String, String> = System.getenv(),
    runner: (List<String>, Map<String, String>) -> JvmEvidenceProcessResult,
) {
    requireJvmArmCommit(candidateCommit)
    check(environment["RUNNER_OS"] == "Linux" && environment["RUNNER_ARCH"] == "ARM64") {
        "Linux ARM64 JVM evidence must run on RUNNER_OS=Linux and RUNNER_ARCH=ARM64"
    }
    val temporary = Files.createTempDirectory("codex-agent-linux-arm64-jvm-evidence").toFile()
    try {
        extractJvmArmBundle(bundle, temporary)
        check(temporary.resolve(JVM_ARM_COMMIT).readText().trim() == candidateCommit) {
            "Linux ARM64 JVM evidence commit mismatch"
        }
        executeJvmRuntimeEvidence(
            candidateCommit = candidateCommit,
            target = "linuxArm64",
            runnerOs = "Linux",
            runnerArch = "ARM64",
            javaExecutable = javaExecutable,
            distributionManifest = temporary.resolve(JVM_ARM_MANIFEST),
            classifierArchive = temporary.resolve(JVM_ARM_CLASSIFIER),
            compiledJvmTestRuntime = temporary.resolve(JVM_RUNTIME_RUNNER_ARCHIVE),
            evidenceFile = evidence,
            runner = runner,
        )
    } finally {
        temporary.deleteRecursively()
    }
}

private fun extractJvmArmBundle(bundle: File, destination: File) = ZipFile(bundle).use { zip ->
    val entries = zip.entries().asSequence().toList()
    check(entries.none(ZipEntry::isDirectory) && entries.map(ZipEntry::getName).toSet() == JVM_ARM_MEMBERS &&
        entries.size == JVM_ARM_MEMBERS.size) { "Linux ARM64 JVM evidence bundle member set is invalid" }
    entries.forEach { entry ->
        check(entry.name == File(entry.name).name && '/' !in entry.name && '\\' !in entry.name) {
            "Linux ARM64 JVM evidence bundle contains an unsafe member"
        }
        zip.getInputStream(entry).use { input ->
            Files.copy(input, destination.resolve(entry.name).toPath(), REPLACE_EXISTING)
        }
    }
}

private fun ZipOutputStream.addJvmArmMember(path: String, input: java.io.InputStream) {
    putNextEntry(ZipEntry(path).apply { setTimeLocal(JVM_ARM_ZIP_EPOCH) })
    input.use { it.copyTo(this) }
    closeEntry()
}

private fun requireJvmArmCommit(value: String) = check(value.matches(Regex("[0-9a-f]{40}"))) {
    "Linux ARM64 JVM candidate commit is not immutable"
}

fun main(arguments: Array<String>) {
    when (arguments.firstOrNull()) {
        "stage" -> {
            check(arguments.size == 6)
            stageLinuxArm64JvmEvidenceBundle(
                arguments[1], File(arguments[2]), File(arguments[3]), File(arguments[4]), File(arguments[5]),
            )
        }
        "execute" -> {
            check(arguments.size == 5)
            executeLinuxArm64JvmEvidenceBundle(
                arguments[1], File(arguments[2]), arguments[3], File(arguments[4]), runner = ::runJvmArmProcess,
            )
        }
        else -> error("Expected stage or execute")
    }
}

private fun runJvmArmProcess(
    command: List<String>,
    environment: Map<String, String>,
): JvmEvidenceProcessResult {
    val process = ProcessBuilder(command).redirectErrorStream(true).apply { environment().putAll(environment) }.start()
    val output = process.inputStream.bufferedReader().readText()
    return JvmEvidenceProcessResult(process.waitFor(), output)
}
