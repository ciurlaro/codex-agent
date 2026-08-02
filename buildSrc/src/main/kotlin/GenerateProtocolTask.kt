import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class GenerateProtocolTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {
    @get:Classpath
    abstract val generatorClasspath: ConfigurableFileCollection

    @get:Input
    abstract val generatorMainClass: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaSource: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val commonSource: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val threadSource: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val turnSource: RegularFileProperty

    @get:OutputFile
    abstract val schemaOutput: RegularFileProperty

    @get:OutputFile
    abstract val descriptorOutput: RegularFileProperty

    @get:OutputDirectory
    abstract val generatedSources: DirectoryProperty

    @get:OutputFile
    abstract val provenanceOutput: RegularFileProperty

    @TaskAction
    fun generate() {
        val generatedDirectory = generatedSources.get().asFile
        execOperations.javaexec {
            classpath(generatorClasspath)
            mainClass.set(generatorMainClass)
            args(
                schemaSource.get().asFile,
                commonSource.get().asFile,
                threadSource.get().asFile,
                turnSource.get().asFile,
                schemaOutput.get().asFile,
                descriptorOutput.get().asFile,
                generatedDirectory.resolve("GeneratedProtocolDescriptors.kt"),
                generatedDirectory.resolve("GeneratedProtocolModels.kt"),
                provenanceOutput.get().asFile,
            )
        }
    }
}
