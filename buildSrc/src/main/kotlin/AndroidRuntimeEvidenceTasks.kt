import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Real-device evidence must execute for every immutable candidate")
abstract class RecordAndroidRuntimeEvidenceTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val pinnedRuntimeSha256: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val outputMetadata: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val testResults: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val releaseAar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val adbExecutable: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val apkanalyzerExecutable: RegularFileProperty
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:OutputDirectory abstract val evidenceDirectory: DirectoryProperty

    @TaskAction
    fun record() {
        val commit = candidateCommit.get()
        check(commit.matches(Regex("[0-9a-f]{40}"))) { "Candidate commit must be 40 lowercase hexadecimal characters" }
        val repository = repositoryDirectory.get().asFile
        check(run("git", "-C", repository.absolutePath, "rev-parse", "HEAD") == commit) {
            "Android evidence checkout does not match candidate commit"
        }

        val adb = adbExecutable.get().asFile.absolutePath
        val serial = singleAuthorizedAndroidDevice(run(adb, "devices"))
        val architecture = run(adb, "-s", serial, "shell", "getprop", "ro.product.cpu.abi")
        check(architecture == "arm64-v8a") { "Android evidence device must use arm64-v8a" }
        val api = run(adb, "-s", serial, "shell", "getprop", "ro.build.version.sdk").toIntOrNull()
        check(api != null && api >= 26) { "Android evidence device API must be at least 26" }

        val apk = resolveAndroidTestApk(outputMetadata.get().asFile)
        val report = findPassingAndroidRuntimeReport(testResults.get().asFile)
        val aar = releaseAar.get().asFile
        val identity = readManifestIdentity(apk)
        check(identity.applicationId == ANDROID_TEST_APPLICATION_ID &&
            identity.applicationId == identity.instrumentationTargetPackage
        ) { "Android instrumentation APK must be the expected self-instrumenting application" }
        val apkRuntime = apk.singleZipEntryDigest(APK_RUNTIME_ENTRY)
        val aarRuntime = aar.singleZipEntryDigest(AAR_RUNTIME_ENTRY)
        val pinnedRuntime = pinnedRuntimeSha256.get()
        check(apkRuntime == aarRuntime && apkRuntime == pinnedRuntime) { "Android runtime bytes are not pinned" }

        val directory = evidenceDirectory.get().asFile
        directory.deleteRecursively()
        directory.mkdirs()
        val copiedApk = directory.resolve(ANDROID_EVIDENCE_APK)
        val copiedReport = directory.resolve(ANDROID_EVIDENCE_REPORT)
        Files.copy(apk.toPath(), copiedApk.toPath(), REPLACE_EXISTING)
        Files.copy(report.file.toPath(), copiedReport.toPath(), REPLACE_EXISTING)
        val evidenceFile = directory.resolve(ANDROID_EVIDENCE_FILE)
        evidenceFile.atomicWriteJson(buildAndroidRuntimeEvidence(AndroidRuntimeEvidenceValues(
            commit,
            architecture,
            api,
            copiedReport.releaseDigest(),
            copiedApk.releaseDigest(),
            identity.applicationId,
            identity.instrumentationTargetPackage,
            aar.name,
            aar.releaseDigest(),
            apkRuntime,
            aarRuntime,
        )))
        verifyAndroidRuntimeEvidenceArtifacts(
            evidenceFile,
            directory,
            aar,
            commit,
            pinnedRuntime,
            ::readManifestIdentity,
        )
    }

    private fun readManifestIdentity(apk: File): AndroidManifestIdentity = parseAndroidManifestIdentity(
        run(apkanalyzerExecutable.get().asFile.absolutePath, "manifest", "print", apk.absolutePath),
    )

    private fun run(executable: String, vararg arguments: String): String {
        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()
        val result = processes.exec {
            commandLine(listOf(executable) + arguments)
            this.standardOutput = standardOutput
            this.errorOutput = errorOutput
            isIgnoreExitValue = true
        }
        check(result.exitValue == 0) {
            "$executable failed with exit ${result.exitValue}: ${errorOutput.toString(Charsets.UTF_8)}"
        }
        return standardOutput.toString(Charsets.UTF_8).trim().replace("\r", "")
    }
}

@CacheableTask
abstract class VerifyAndroidRuntimeEvidenceTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val expectedCommit: Property<String>
    @get:Input abstract val pinnedRuntimeSha256: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val evidenceFile: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val evidenceDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val stagedAar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val apkanalyzerExecutable: RegularFileProperty
    @get:OutputFile abstract val verificationFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val verified = verifyAndroidRuntimeEvidenceArtifacts(
            evidenceFile.get().asFile,
            evidenceDirectory.get().asFile,
            stagedAar.get().asFile,
            expectedCommit.get(),
            pinnedRuntimeSha256.get(),
        ) { apk ->
            parseAndroidManifestIdentity(runApkanalyzer(apk))
        }
        verificationFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("result", JsonPrimitive("passed"))
            put("evidenceSha256", JsonPrimitive(verified.evidenceSha256))
            put("testReportSha256", JsonPrimitive(verified.testReportSha256))
            put("instrumentationApkSha256", JsonPrimitive(verified.instrumentationApkSha256))
            put("releaseAarSha256", JsonPrimitive(verified.releaseAarSha256))
            put("bundledRuntimeSha256", JsonPrimitive(verified.bundledRuntimeSha256))
        })
    }

    private fun runApkanalyzer(apk: File): String {
        val output = ByteArrayOutputStream()
        processes.exec {
            commandLine(apkanalyzerExecutable.get().asFile, "manifest", "print", apk)
            standardOutput = output
        }
        return output.toString(Charsets.UTF_8)
    }
}
