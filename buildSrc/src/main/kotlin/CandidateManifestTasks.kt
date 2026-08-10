import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateCandidateManifestTask : DefaultTask() {
    @get:Input abstract val candidateVersion: Property<String>
    @get:Input abstract val candidateCommit: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftZip: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftChecksum: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val centralBundle: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val centralInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mavenInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val approvalsFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyAudit: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyReviews: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val androidEvidence: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val evidenceFile = androidEvidence.get().asFile
        val evidenceErrors = validateAndroidEvidence(evidenceFile, candidateCommit.get())
        val files = linkedMapOf(
            "swiftZip" to swiftZip.get().asFile,
            "centralBundle" to centralBundle.get().asFile,
            "centralInventory" to centralInventory.get().asFile,
            "mavenInventory" to mavenInventory.get().asFile,
            "approvals" to approvalsFile.get().asFile,
            "privacyManifest" to privacyManifest.get().asFile,
            "privacyInventory" to privacyInventory.get().asFile,
            "privacyAudit" to privacyAudit.get().asFile,
            "privacyReviews" to privacyReviews.get().asFile,
            "androidRuntimeEvidence" to evidenceFile,
        ).mapValues { (_, file) -> mapOf("bytes" to file.length(), "sha256" to file.sha256()) }
        outputFile.get().asFile.writeJson(
            linkedMapOf(
                "schemaVersion" to 1,
                "version" to candidateVersion.get(),
                "candidateCommit" to candidateCommit.get(),
                "protectedCandidate" to evidenceErrors.isEmpty(),
                "swiftPmChecksum" to swiftChecksum.get().asFile.readText().trim(),
                "androidRuntimeEvidenceErrors" to evidenceErrors,
                "artifacts" to files,
            ),
        )
    }
}

@CacheableTask
abstract class VerifyAndroidRuntimeEvidenceTask : DefaultTask() {
    @get:Input abstract val expectedCommit: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val evidenceFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val errors = validateAndroidEvidence(evidenceFile.get().asFile, expectedCommit.get())
        check(errors.isEmpty()) { "Android real-runtime evidence is invalid: ${errors.joinToString()}" }
    }
}

@CacheableTask
abstract class VerifyCandidateManifestTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun verify() {
        check(manifestFile.get().asFile.jsonObject().boolean("protectedCandidate")) {
            "Candidate is not protected by exact Android real-runtime evidence"
        }
    }
}

internal fun validateAndroidEvidence(file: File, expectedCommit: String): List<String> {
    val evidence = file.jsonObject()
    return buildList {
        if (!expectedCommit.matches(Regex("[0-9a-f]{40}"))) add("candidate commit is not immutable")
        if (evidence.stringOrNull("commitSha") != expectedCommit) add("commit SHA mismatch")
        if (evidence.stringOrNull("testCommand").isNullOrBlank()) add("test command missing")
        if (evidence.stringOrNull("deviceArchitecture") !in setOf("arm64-v8a", "aarch64")) {
            add("device architecture is not ARM64")
        }
        if ((evidence["deviceApi"] as? Number)?.toInt()?.let { it >= 26 } != true) add("device API missing")
        if (evidence.stringOrNull("result") != "passed") add("result is not passed")
        listOf("testApkSha256", "targetApkSha256", "runtimeSha256").forEach { key ->
            if (!evidence.stringOrNull(key).orEmpty().matches(Regex("[0-9a-f]{64}"))) add("$key missing")
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun File.jsonObject(): Map<String, Any?> = JsonSlurper().parse(this) as? Map<String, Any?>
    ?: error("Expected JSON object: $path")

private fun Map<String, Any?>.stringOrNull(name: String): String? = this[name] as? String
private fun Map<String, Any?>.boolean(name: String): Boolean = this[name] as? Boolean
    ?: error("Missing JSON boolean: $name")

private fun File.writeJson(value: Any?) {
    parentFile.mkdirs()
    writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n")
}

private fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
