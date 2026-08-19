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
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.CacheableTask
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Host Cargo tests produce no reusable artifact")
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

    @get:Input
    abstract val retainedEnvironment: MapProperty<String, String>

    @get:Input
    abstract val externalCargoConfigurationState: MapProperty<String, String>

    @get:Input
    abstract val rustcWrapperCommand: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rustcWrapperExecutable: ConfigurableFileCollection

    @get:Input
    abstract val cargoIncremental: Property<String>

    @get:Input
    abstract val rustCompilerIdentity: Property<String>

    @get:Input
    abstract val appleToolchainIdentity: Property<String>

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
        retainedEnvironment.convention(emptyMap())
        externalCargoConfigurationState.convention(emptyMap())
        rustcArguments.convention(emptyList())
        rustPathRemappings.convention(emptyList())
        rustFlagsEnvironmentVariable.convention("CARGO_ENCODED_RUSTFLAGS")
        provenanceValues.convention(emptyMap())
        rustSrcComponent.convention("not-required")
        rustcWrapperCommand.convention("")
        cargoIncremental.convention("")
        rustCompilerIdentity.convention("")
        appleToolchainIdentity.convention("")
    }

    @TaskAction
    fun runCargo() {
        requireNoExternalCargoConfiguration(externalCargoConfigurationState.get())
        val cargo = rustTool("cargo")
        val rustc = rustTool("rustc")
        val rustdoc = rustTool("rustdoc")
        val wrapper = rustcWrapperCommand.get()
        val environment = sanitizedCargoBaseEnvironment(
            retainedEnvironment.get(),
            if (wrapper.isBlank()) emptyMap() else System.getenv(),
        ).apply {
            check(extraEnvironment.get().keys.none { it in keys || it in cargoManagedEnvironmentNames }) {
                "Extra Cargo environment cannot replace the sanitized or task-managed environment"
            }
            putAll(extraEnvironment.get())
            if (rustcArguments.get().isNotEmpty() || rustPathRemappings.get().isNotEmpty()) {
                put(
                    rustFlagsEnvironmentVariable.get(),
                    encodeRustcArguments(rustcArguments.get(), rustPathRemappings.get()),
                )
            }
            check(wrapper.isBlank() == rustcWrapperExecutable.files.isEmpty()) {
                "RUSTC_WRAPPER command and executable inputs must be configured together"
            }
            if (wrapper.isNotBlank()) {
                val executable = rustcWrapperExecutable.singleFile.canonicalFile
                check(executable.isFile && executable.canExecute()) { "RUSTC_WRAPPER is not executable: $executable" }
                put("RUSTC_WRAPPER", executable.path)
            }
            cargoIncremental.get().takeIf(String::isNotBlank)?.let { incremental ->
                require(incremental in setOf("0", "1")) { "CARGO_INCREMENTAL must be 0 or 1" }
                put("CARGO_INCREMENTAL", incremental)
            }
            put("CARGO_TARGET_DIR", cargoTargetDirectory.get().asFile.absolutePath)
            put("RUSTC", rustc)
            put("RUSTDOC", rustdoc)
        }
        exec.exec {
            workingDir(workingDirectory)
            commandLine(cargo, *cargoArguments.get().toTypedArray())
            setEnvironment(environment)
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

@CacheableTask
abstract class CachedPinnedCargoTask @Inject constructor(exec: ExecOperations) : PinnedCargoTask(exec) {
    @get:OutputFile
    abstract val archiveOutput: RegularFileProperty
}

internal fun resolveRustcWrapper(command: String, searchPath: String): File {
    require(command.isNotBlank() && '\u0000' !in command) { "RUSTC_WRAPPER must name one executable" }
    val direct = File(command)
    val candidates = when {
        direct.isAbsolute -> listOf(direct)
        command.contains('/') || command.contains('\\') -> emptyList()
        else -> searchPath.split(File.pathSeparatorChar).filter(String::isNotBlank).map { File(it, command) }
    }
    return candidates.firstOrNull { it.isFile && it.canExecute() }?.canonicalFile
        ?: error("RUSTC_WRAPPER executable was not found: $command")
}

internal fun encodeRustcArguments(arguments: List<String>, pathRemappings: List<String>): String =
    (arguments + pathRemappings.flatMap { listOf("--remap-path-prefix", it) })
        .onEach { require('\u001f' !in it) { "rustc arguments cannot contain the unit separator" } }
        .joinToString("\u001f")

internal val cargoRetainedEnvironmentNames = setOf(
    "PATH", "CARGO_HOME", "DEVELOPER_DIR", "SCCACHE_GHA_ENABLED", "SCCACHE_GHA_VERSION", "SCCACHE_GHA_RW_MODE",
)
private val cargoManagedEnvironmentNames = setOf(
    "CARGO_ENCODED_RUSTFLAGS", "CARGO_INCREMENTAL", "CARGO_TARGET_DIR", "RUSTC", "RUSTDOC", "RUSTC_WRAPPER",
)
private val sccacheTransportEnvironmentNames = setOf(
    "ACTIONS_CACHE_SERVICE_V2", "ACTIONS_CACHE_URL", "ACTIONS_RESULTS_URL", "ACTIONS_RUNTIME_TOKEN",
)

internal fun sanitizedCargoBaseEnvironment(
    declared: Map<String, String>,
    ambient: Map<String, String>,
): MutableMap<String, String> {
    check(declared.keys.all { it in cargoRetainedEnvironmentNames }) { "Unsupported retained Cargo environment" }
    declared["SCCACHE_GHA_RW_MODE"]?.let { sccacheGhaRwModeEnvironment(it) }
    check(declared.getValue("PATH").isNotBlank()) { "Cargo PATH is required" }
    check(File(declared.getValue("CARGO_HOME")).isAbsolute) { "CARGO_HOME must be absolute" }
    return declared.toMutableMap().apply {
        sccacheTransportEnvironmentNames.forEach { name -> ambient[name]?.let { put(name, it) } }
    }
}

internal fun sccacheGhaRwModeEnvironment(value: String): Map<String, String> = when (value) {
    "" -> emptyMap()
    "READ_ONLY", "READ_WRITE" -> mapOf("SCCACHE_GHA_RW_MODE" to value)
    else -> error("SCCACHE_GHA_RW_MODE must be READ_ONLY or READ_WRITE")
}

internal fun requireNoExternalCargoConfiguration(state: Map<String, String>) {
    val present = state.filterValues { it != "missing" }.keys
    check(present.isEmpty()) { "External Cargo configuration is forbidden for cacheable iOS builds: $present" }
}
