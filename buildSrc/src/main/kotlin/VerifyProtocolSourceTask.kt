import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Verification has no reusable outputs")
abstract class VerifyProtocolSourceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val protocolSchema: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val completeProtocolSchema: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val provenance: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedSources: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val descriptor: RegularFileProperty

    @get:Input
    abstract val expectedSchemaSha256: Property<String>

    @get:Input
    abstract val expectedCompleteSchemaSha256: Property<String>

    @TaskAction
    fun verifyProtocol() {
        val schema = protocolSchema.get().asFile
        val completeSchema = completeProtocolSchema.get().asFile
        check(schema.sha256() == expectedSchemaSha256.get()) {
            "Pinned App Server protocol schema digest changed: ${schema.sha256()}"
        }
        check(completeSchema.sha256() == expectedCompleteSchemaSha256.get()) {
            "Pinned complete App Server protocol schema digest changed"
        }
        val provenanceFile = provenance.get().asFile
        verifyGeneratedOutputs(provenanceFile, provenanceFile.parentFile.parentFile.parentFile)
    }

    @Suppress("UNCHECKED_CAST")
    private fun verifyGeneratedOutputs(provenanceFile: File, root: File) {
        val provenanceData = JsonSlurper().parse(provenanceFile) as Map<String, Any?>
        val generator = provenanceData["generator"] as? Map<String, Any?>
            ?: error("Protocol generator provenance is missing")
        check(generator["version"] == "3") { "Unsupported protocol generator provenance" }
        val outputs = generator["outputs"] as? List<Map<String, String>>
            ?: error("Generated protocol outputs are missing from provenance")
        check(outputs.isNotEmpty()) { "Generated protocol output provenance is empty" }
        outputs.forEach { output ->
            val file = root.resolve(output.getValue("path"))
            check(file.isFile) { "Generated protocol output is missing: ${output.getValue("path")}" }
            check(file.sha256() == output.getValue("sha256")) {
                "Generated protocol output drifted: ${output.getValue("path")}"
            }
        }
    }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        generateSequence { input.read(buffer).takeIf { it >= 0 } }.forEach { count ->
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
