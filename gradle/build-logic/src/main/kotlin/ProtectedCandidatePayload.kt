import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.json.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The final transported payload must be copied and verified from fresh proof bytes")
abstract class StageProtectedCandidatePayloadTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifestFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val sourceFiles: ConfigurableFileCollection
    @get:Input abstract val expectedVersion: Property<String>
    @get:Input abstract val expectedTag: Property<String>
    @get:Input abstract val expectedCommit: Property<String>
    @get:Internal abstract val candidateDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:OutputFile abstract val verificationFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun stage() = stageProtectedCandidatePayload(
        manifestFile.get().asFile,
        sourceFiles.files,
        candidateDirectory.get().asFile,
        outputDirectory.get().asFile,
        verificationFile.get().asFile,
        expectedVersion.get(),
        expectedTag.get(),
        expectedCommit.get(),
    )
}

internal fun stageProtectedCandidatePayload(
    manifestFile: File,
    sourceFiles: Collection<File>,
    candidateDirectory: File,
    outputDirectory: File,
    verificationFile: File,
    expectedVersion: String,
    expectedTag: String,
    expectedCommit: String,
) {
    val candidate = candidateDirectory.canonicalFile
    val output = outputDirectory.canonicalFile
    check(output == candidate.resolve("payload").canonicalFile) { "Candidate payload must be commit-isolated" }
    check(verificationFile.canonicalFile == candidate.resolve("reports/payload-verification.json").canonicalFile) {
        "Candidate payload verification must be commit-isolated"
    }

    val manifest = manifestFile.readReleaseObject()
    verifyCandidateManifestStructure(manifest)
    val policies = manifest.releaseObject("policies")
    val expectedNames = candidatePayloadNames(manifest)
    check(expectedNames.size == expectedNames.toSet().size) { "Candidate manifest basenames must be unique" }
    val manifestName = manifestFile.name
    check(manifestName.isNotBlank() && manifestName !in setOf(".", "..") && '/' !in manifestName && '\\' !in manifestName) {
        "Unsafe candidate manifest basename: $manifestName"
    }
    val sourceNames = sourceFiles.map { source ->
        check(source.isFile) { "Candidate payload source is missing: $source" }
        source.name.also { name ->
            check(name.isNotBlank() && name !in setOf(".", "..") && '/' !in name && '\\' !in name) {
                "Unsafe candidate payload basename: $name"
            }
        }
    }
    check(sourceNames.size == sourceNames.toSet().size) { "Candidate payload basenames must be unique" }
    check(manifestName !in sourceNames) { "Candidate manifest basename conflicts with a payload file" }
    check(sourceNames.toSet() == expectedNames.toSet()) { "Candidate payload sources do not match the manifest" }

    output.deleteRecursively()
    output.mkdirs()
    sourceFiles.forEach { source -> Files.copy(source.toPath(), output.resolve(source.name).toPath(), REPLACE_EXISTING) }
    val stagedPolicies = policies.mapValues { (_, record) ->
        output.resolve((record as JsonObject).releaseString("fileName"))
    }
    val result = verifyCandidatePayload(
        manifestFile, output, expectedVersion, expectedTag, expectedCommit, stagedPolicies,
    )
    Files.copy(manifestFile.toPath(), output.resolve(manifestName).toPath(), REPLACE_EXISTING)
    verificationFile.atomicWriteJson(result)
}

private fun candidatePayloadNames(manifest: JsonObject): List<String> = buildList {
    manifest.releaseObject("artifacts").values.forEach { add((it as JsonObject).releaseString("fileName")) }
    val evidence = manifest.releaseObject("evidence")
    evidence.filterKeys { it !in candidateEvidenceArrayNames }.values
        .forEach { add((it as JsonObject).releaseString("fileName")) }
    candidateEvidenceArrayNames.forEach { name ->
        evidence.releaseArray(name).forEach { add((it as JsonObject).releaseString("fileName")) }
    }
    manifest.releaseObject("policies").values.forEach { add((it as JsonObject).releaseString("fileName")) }
}
