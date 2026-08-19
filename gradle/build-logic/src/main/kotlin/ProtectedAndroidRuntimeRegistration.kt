import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal const val FIREBASE_ANDROID_VERIFICATION_RECEIPT_FILE =
    "firebase-android-runtime-verification.json"
internal const val FIREBASE_ANDROID_EVIDENCE_DIRECTORY_PROPERTY =
    "codexAgent.androidRuntimeEvidenceDirectory"
internal const val FIREBASE_ANDROID_VERIFY_TASK_PATH =
    ":tooling:android-runtime-evidence:verifyFirebaseAndroidRuntimeEvidence"

internal val protectedFirebaseAndroidRuntimeRawFiles = listOf(
    "Record" to FIREBASE_ANDROID_EVIDENCE_FILE,
    "Matrix" to FIREBASE_MATRIX_FILE,
    "Report" to FIREBASE_ANDROID_REPORT,
    "ApplicationApk" to FIREBASE_APPLICATION_APK,
    "TestApk" to FIREBASE_TEST_APK,
    "ReleaseAar" to FIREBASE_RELEASE_AAR,
)

data class ProtectedFirebaseAndroidRuntimeEvidenceRegistration(
    val stageTask: TaskProvider<Task>,
    val stagedFiles: ConfigurableFileCollection,
)

fun Project.registerProtectedFirebaseAndroidRuntimeEvidence(
    candidateEvidence: Provider<Directory>,
): ProtectedFirebaseAndroidRuntimeEvidenceRegistration {
    val importedEvidence = layout.dir(
        providers.gradleProperty(FIREBASE_ANDROID_EVIDENCE_DIRECTORY_PROPERTY).map(::file),
    )
    val verifierReceipt = project(":tooling:android-runtime-evidence").layout.buildDirectory.file(
        "reports/$FIREBASE_ANDROID_VERIFICATION_RECEIPT_FILE",
    )
    val stagedFiles = objects.fileCollection()

    fun registerCopy(name: String, fileName: String, source: Provider<RegularFile>) =
        tasks.register<CopyCandidateFileTask>(name) {
            dependsOn(FIREBASE_ANDROID_VERIFY_TASK_PATH)
            sourceFile.set(source)
            outputFile.set(candidateEvidence.map { it.file(fileName) })
        }.also { stagedFiles.from(it.flatMap(CopyCandidateFileTask::outputFile)) }

    val copies = protectedFirebaseAndroidRuntimeRawFiles.map { (suffix, fileName) ->
        registerCopy(
            "stageProtectedFirebaseAndroidRuntime$suffix",
            fileName,
            importedEvidence.map { it.file(fileName) },
        )
    } + registerCopy(
        "stageProtectedFirebaseAndroidRuntimeVerificationReceipt",
        FIREBASE_ANDROID_VERIFICATION_RECEIPT_FILE,
        verifierReceipt,
    )
    val stage = tasks.register("stageProtectedFirebaseAndroidRuntimeEvidence") {
        dependsOn(copies)
    }
    return ProtectedFirebaseAndroidRuntimeEvidenceRegistration(stage, stagedFiles)
}
