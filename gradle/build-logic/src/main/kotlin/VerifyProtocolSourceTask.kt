import java.io.File
import kotlinx.serialization.json.JsonObject
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
        check(schema.releaseDigest() == expectedSchemaSha256.get()) {
            "Pinned App Server protocol schema digest changed: ${schema.releaseDigest()}"
        }
        check(completeSchema.releaseDigest() == expectedCompleteSchemaSha256.get()) {
            "Pinned complete App Server protocol schema digest changed"
        }
        val provenanceFile = provenance.get().asFile
        verifyGeneratedOutputs(provenanceFile, provenanceFile.parentFile.parentFile.parentFile)
    }

    private fun verifyGeneratedOutputs(provenanceFile: File, root: File) {
        val generator = provenanceFile.readReleaseObject().releaseObject("generator")
        check(generator.releaseString("version") == "3") { "Unsupported protocol generator provenance" }
        val outputs = generator.releaseArray("outputs").map {
            it as? JsonObject ?: error("Invalid generated protocol output provenance")
        }
        check(outputs.isNotEmpty()) { "Generated protocol output provenance is empty" }
        outputs.forEach { output ->
            val path = output.releaseString("path")
            val file = root.resolve(path)
            check(file.isFile) { "Generated protocol output is missing: $path" }
            check(file.releaseDigest() == output.releaseString("sha256")) {
                "Generated protocol output drifted: $path"
            }
        }
    }
}
