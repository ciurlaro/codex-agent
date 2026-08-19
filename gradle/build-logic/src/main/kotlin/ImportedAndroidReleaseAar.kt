import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider

internal const val IMPORTED_ANDROID_RELEASE_AAR_PROPERTY = "codexAgent.importedAndroidReleaseAar"

private val ANDROID_RELEASE_PUBLICATION_CONFIGURATIONS = listOf(
    "releaseVariantReleaseApiPublication",
    "releaseVariantReleaseRuntimePublication",
)

internal fun verifyImportedAndroidReleaseAar(
    aar: File,
    firebaseEvidenceFile: File,
    candidateCommit: String,
    pinnedRuntimeSha256: String,
): JsonObject {
    check(candidateCommit.matches(Regex("[0-9a-f]{40}"))) { "Candidate commit must be immutable" }
    check(pinnedRuntimeSha256.matches(Regex("[0-9a-f]{64}"))) { "Pinned Android runtime SHA-256 is invalid" }
    check(aar.isFile && aar.name == FIREBASE_RELEASE_AAR) { "Exact-main Android release AAR is missing or misnamed" }
    val evidence = firebaseEvidenceFile.readReleaseObject()
    val errors = validateFirebaseAndroidEvidence(evidence, candidateCommit)
    check(errors.isEmpty()) { "Firebase Android evidence is invalid: ${errors.joinToString()}" }
    val aarSha256 = aar.releaseDigest()
    check(aarSha256 == evidence.releaseString("releaseAarSha256")) {
        "Exact-main Android release AAR is not bound to Firebase evidence"
    }
    val runtimeSha256 = aar.singleZipEntryDigest(AAR_RUNTIME_ENTRY)
    check(runtimeSha256 == pinnedRuntimeSha256 &&
        runtimeSha256 == evidence.releaseString("aarBundledRuntimeSha256")) {
        "Exact-main Android release AAR does not contain the pinned runtime"
    }
    return buildJsonObject {
        put("schemaVersion", JsonPrimitive(1))
        put("candidateCommit", JsonPrimitive(candidateCommit))
        put("firebaseEvidenceSha256", JsonPrimitive(firebaseEvidenceFile.releaseDigest()))
        put("releaseAarSha256", JsonPrimitive(aarSha256))
        put("bundledRuntimeSha256", JsonPrimitive(runtimeSha256))
        put("result", JsonPrimitive("passed"))
    }
}

@CacheableTask
abstract class VerifyImportedAndroidReleaseAarTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val releaseAar: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val firebaseEvidence: RegularFileProperty
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val pinnedRuntimeSha256: Property<String>
    @get:OutputFile abstract val verificationFile: RegularFileProperty

    @TaskAction
    fun verify() = verificationFile.get().asFile.atomicWriteJson(verifyImportedAndroidReleaseAar(
        releaseAar.get().asFile,
        firebaseEvidence.get().asFile,
        candidateCommit.get(),
        pinnedRuntimeSha256.get(),
    ))
}

internal fun Project.replaceAndroidReleaseComponentAar(
    importedAar: Provider<RegularFile>,
    validationTask: TaskProvider<out Task>,
) {
    ANDROID_RELEASE_PUBLICATION_CONFIGURATIONS.forEach { name ->
        val outgoing = configurations.getByName(name).outgoing
        val primaryAars = outgoing.artifacts.filter { it.extension == "aar" && it.classifier == null }
        check(primaryAars.size == 1) { "$name must contain exactly one primary Android AAR artifact" }
        primaryAars.forEach(outgoing.artifacts::remove)
        outgoing.artifact(importedAar, Action {
            type = "aar"
            extension = "aar"
            builtBy(validationTask)
        })
    }
}
