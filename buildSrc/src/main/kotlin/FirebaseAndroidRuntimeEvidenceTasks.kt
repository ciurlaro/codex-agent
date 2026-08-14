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

@DisableCachingByDefault(because = "Firebase evidence must be recorded for every immutable candidate")
abstract class RecordFirebaseAndroidRuntimeEvidenceTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val pinnedRuntimeSha256: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val applicationOutputMetadata: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val testOutputMetadata: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val matrixFile: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val testResults: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val releaseAar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val apkanalyzerExecutable: RegularFileProperty
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:OutputDirectory abstract val evidenceDirectory: DirectoryProperty

    @TaskAction
    fun record() {
        val commit = candidateCommit.get()
        check(commit.matches(Regex("[0-9a-f]{40}"))) { "Candidate commit must be immutable" }
        check(run("git", "-C", repositoryDirectory.get().asFile.absolutePath, "rev-parse", "HEAD") == commit) {
            "Firebase Android evidence checkout does not match candidate commit"
        }
        val app = resolveSingleApk(applicationOutputMetadata.get().asFile, "application")
        val test = resolveSingleApk(testOutputMetadata.get().asFile, "instrumentation test")
        val aar = releaseAar.get().asFile
        val matrix = parseFirebaseTestMatrix(matrixFile.get().asFile)
        val report = findPassingAndroidRuntimeReport(testResults.get().asFile).file
        val appId = parseAndroidApplicationId(printManifest(app))
        val testId = parseAndroidManifestIdentity(printManifest(test))
        check(appId == FIREBASE_APPLICATION_ID) { "Unexpected Android host application ID" }
        check(testId == AndroidManifestIdentity(FIREBASE_TEST_APPLICATION_ID, FIREBASE_APPLICATION_ID)) {
            "Unexpected Android instrumentation identity"
        }
        val appRuntime = app.singleZipEntryDigest(APK_RUNTIME_ENTRY)
        val aarRuntime = aar.singleZipEntryDigest(AAR_RUNTIME_ENTRY)
        val pinnedRuntime = pinnedRuntimeSha256.get()
        check(appRuntime == aarRuntime && appRuntime == pinnedRuntime) { "Android runtime bytes are not pinned" }

        val output = evidenceDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()
        val copiedMatrix = copy(matrixFile.get().asFile, output.resolve(FIREBASE_MATRIX_FILE))
        val copiedReport = copy(report, output.resolve(FIREBASE_ANDROID_REPORT))
        val copiedApp = copy(app, output.resolve(FIREBASE_APPLICATION_APK))
        val copiedTest = copy(test, output.resolve(FIREBASE_TEST_APK))
        val copiedAar = copy(aar, output.resolve(FIREBASE_RELEASE_AAR))
        val evidence = output.resolve(FIREBASE_ANDROID_EVIDENCE_FILE)
        evidence.atomicWriteJson(buildFirebaseAndroidEvidence(FirebaseAndroidEvidenceValues(
            commit, matrix, copiedMatrix.releaseDigest(), copiedReport.releaseDigest(),
            copiedApp.releaseDigest(), copiedTest.releaseDigest(), copiedAar.releaseDigest(),
            appRuntime, aarRuntime,
        )))
        verifyFirebaseAndroidRuntimeEvidenceArtifacts(
            evidence, output, commit, pinnedRuntime, ::applicationId, ::testIdentity,
        )
    }

    private fun applicationId(apk: File): String = parseAndroidApplicationId(printManifest(apk))
    private fun testIdentity(apk: File): AndroidManifestIdentity = parseAndroidManifestIdentity(printManifest(apk))
    private fun printManifest(apk: File): String = run(
        apkanalyzerExecutable.get().asFile.absolutePath, "manifest", "print", apk.absolutePath,
    )
    private fun copy(source: File, target: File): File = target.also {
        Files.copy(source.toPath(), it.toPath(), REPLACE_EXISTING)
    }
    private fun run(executable: String, vararg arguments: String): String {
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val result = processes.exec {
            commandLine(listOf(executable) + arguments)
            standardOutput = output
            errorOutput = error
            isIgnoreExitValue = true
        }
        check(result.exitValue == 0) { "$executable failed: ${error.toString(Charsets.UTF_8)}" }
        return output.toString(Charsets.UTF_8).trim().replace("\r", "")
    }
}

@CacheableTask
abstract class VerifyFirebaseAndroidRuntimeEvidenceTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val expectedCommit: Property<String>
    @get:Input abstract val pinnedRuntimeSha256: Property<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val evidenceDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val apkanalyzerExecutable: RegularFileProperty
    @get:OutputFile abstract val verificationFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val directory = evidenceDirectory.get().asFile
        val verified = verifyFirebaseAndroidRuntimeEvidenceArtifacts(
            directory.resolve(FIREBASE_ANDROID_EVIDENCE_FILE), directory, expectedCommit.get(),
            pinnedRuntimeSha256.get(), ::applicationId, ::testIdentity,
        )
        verificationFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("result", JsonPrimitive("passed"))
            put("evidenceSha256", JsonPrimitive(verified.evidenceSha256))
            put("firebaseMatrixSha256", JsonPrimitive(verified.matrixSha256))
            put("testReportSha256", JsonPrimitive(verified.testReportSha256))
            put("applicationApkSha256", JsonPrimitive(verified.applicationApkSha256))
            put("testApkSha256", JsonPrimitive(verified.testApkSha256))
            put("releaseAarSha256", JsonPrimitive(verified.releaseAarSha256))
            put("bundledRuntimeSha256", JsonPrimitive(verified.bundledRuntimeSha256))
        })
    }

    private fun applicationId(apk: File): String = parseAndroidApplicationId(printManifest(apk))
    private fun testIdentity(apk: File): AndroidManifestIdentity = parseAndroidManifestIdentity(printManifest(apk))
    private fun printManifest(apk: File): String {
        val output = ByteArrayOutputStream()
        processes.exec {
            commandLine(apkanalyzerExecutable.get().asFile, "manifest", "print", apk)
            standardOutput = output
        }
        return output.toString(Charsets.UTF_8)
    }
}
