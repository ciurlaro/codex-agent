import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonObject
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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

internal fun verifyCandidatePayload(
    manifestFile: File,
    payload: File,
    expectedVersion: String,
    expectedTag: String,
    expectedCommit: String,
    policyFiles: Map<String, File>,
): JsonObject {
    val manifest = manifestFile.readReleaseObject()
    verifyCandidateManifestStructure(manifest)
    check(manifest.releaseString("version") == expectedVersion) { "Candidate version mismatch" }
    check(manifest.releaseString("releaseTag") == expectedTag) { "Candidate release tag mismatch" }
    check(manifest.releaseString("candidateCommit") == expectedCommit) { "Candidate commit mismatch" }
    val artifacts = manifest.releaseObject("artifacts")
    val evidence = manifest.releaseObject("evidence")
    val policies = manifest.releaseObject("policies")
    val records = buildList {
        artifacts.values.forEach { add(it as JsonObject) }
        evidence.filterKeys { it != "resourceMeasurements" }.values.forEach { add(it as JsonObject) }
        evidence.releaseArray("resourceMeasurements").forEach { add(it as JsonObject) }
        policies.values.forEach { add(it as JsonObject) }
    }
    val expectedFiles = records.map { it.releaseString("fileName") }
    check(expectedFiles.toSet().size == expectedFiles.size) { "Candidate payload file names are not unique" }
    check(payload.isDirectory) { "Candidate payload directory is missing" }
    val actualFiles = Files.walk(payload.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).map { payload.toPath().relativize(it).toString().replace(File.separatorChar, '/') }
            .toList().toSet()
    }
    check(actualFiles == expectedFiles.toSet()) {
        "Candidate payload file set mismatch: expected=${expectedFiles.toSet().sorted()} actual=${actualFiles.sorted()}"
    }
    records.forEach { verifyPayloadRecord(payload, it) }
    check(policyFiles.keys == policies.keys) { "Candidate policy verifier is incomplete" }
    policyFiles.forEach { (name, file) ->
        val record = policies.releaseObject(name)
        check(record.releaseString("fileName") == file.name) { "Candidate policy file name mismatch: $name" }
        verifyReleaseRecord(file, record)
    }
    return buildJsonObject {
        put("schemaVersion", JsonPrimitive(1))
        put("result", JsonPrimitive("passed"))
        put("releaseTag", JsonPrimitive(expectedTag))
        put("swiftAsset", JsonPrimitive(artifacts.releaseObject("swiftPackage").releaseString("fileName")))
        put("centralBundle", JsonPrimitive(artifacts.releaseObject("centralBundle").releaseString("fileName")))
    }
}

internal fun candidateGithubOutputs(result: JsonObject): String = buildString {
    append("releaseTag=").append(result.releaseString("releaseTag")).append('\n')
    append("swiftAsset=").append(result.releaseString("swiftAsset")).append('\n')
    append("centralBundle=").append(result.releaseString("centralBundle")).append('\n')
}

private fun verifyPayloadRecord(payload: File, record: JsonObject) {
    verifyReleaseRecord(safePayloadFile(payload, record.releaseString("fileName")), record)
}

@CacheableTask
abstract class VerifyCandidatePayloadTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifestFile: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val payloadDirectory: DirectoryProperty
    @get:Input abstract val expectedVersion: Property<String>
    @get:Input abstract val expectedTag: Property<String>
    @get:Input abstract val expectedCommit: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val approvalsFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyDataFlowReview: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyReviews: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val packageSwift: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:Optional @get:OutputFile abstract val githubOutputFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val result = verifyCandidatePayload(
            manifestFile.get().asFile,
            payloadDirectory.get().asFile,
            expectedVersion.get(),
            expectedTag.get(),
            expectedCommit.get(),
            buildMap {
                put("approvals", approvalsFile.get().asFile)
                put("privacyManifest", privacyManifest.get().asFile)
                put("privacyDataFlowReview", privacyDataFlowReview.get().asFile)
                privacyReviews.orNull?.asFile?.let { put("privacyRequiredReasonReviews", it) }
                put("packageSwift", packageSwift.get().asFile)
            },
        )
        outputFile.get().asFile.atomicWriteJson(result)
        githubOutputFile.orNull?.asFile?.let { file ->
            file.parentFile.mkdirs()
            file.writeText(candidateGithubOutputs(result))
        }
    }
}
