import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

abstract class PinnedCargoTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val toolchain: Property<String>

    @get:Input
    abstract val cargoArguments: ListProperty<String>

    @get:Input
    abstract val rustcArguments: ListProperty<String>

    @get:Input
    abstract val rustPathRemappings: ListProperty<String>

    @get:Input
    abstract val rustFlagsEnvironmentVariable: Property<String>

    @get:Input
    abstract val extraEnvironment: MapProperty<String, String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workingDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceInputs: ConfigurableFileCollection

    @get:Input
    abstract val provenanceValues: MapProperty<String, String>

    @get:Input
    abstract val rustSrcComponent: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rustSrcManifest: RegularFileProperty

    @get:Internal
    abstract val cargoTargetDirectory: DirectoryProperty

    init {
        extraEnvironment.convention(emptyMap())
        rustcArguments.convention(emptyList())
        rustPathRemappings.convention(emptyList())
        rustFlagsEnvironmentVariable.convention("CARGO_ENCODED_RUSTFLAGS")
        provenanceValues.convention(emptyMap())
        rustSrcComponent.convention("not-required")
    }

    @TaskAction
    fun runCargo() {
        val rustc = rustTool("rustc")
        val rustdoc = rustTool("rustdoc")
        exec.exec {
            workingDir(workingDirectory)
            commandLine("rustup", "run", toolchain.get(), "cargo", *cargoArguments.get().toTypedArray())
            environment(extraEnvironment.get().toMutableMap().apply {
                if (rustcArguments.get().isNotEmpty() || rustPathRemappings.get().isNotEmpty()) {
                    put(
                        rustFlagsEnvironmentVariable.get(),
                        encodeRustcArguments(rustcArguments.get(), rustPathRemappings.get()),
                    )
                }
            })
            environment("CARGO_TARGET_DIR", cargoTargetDirectory.get().asFile.absolutePath)
            environment("RUSTC", rustc)
            environment("RUSTDOC", rustdoc)
        }.assertNormalExitValue()
    }

    private fun rustTool(name: String): String {
        val output = ByteArrayOutputStream()
        exec.exec {
            commandLine("rustup", "which", "--toolchain", toolchain.get(), name)
            standardOutput = output
        }.assertNormalExitValue()
        return output.toString().trim().also { path ->
            check(File(path).isFile) { "Pinned Rust tool is missing: $name" }
        }
    }
}

internal fun encodeRustcArguments(arguments: List<String>, pathRemappings: List<String>): String =
    (arguments + pathRemappings.flatMap { listOf("--remap-path-prefix", it) })
        .onEach { require('\u001f' !in it) { "rustc arguments cannot contain the unit separator" } }
        .joinToString("\u001f")
