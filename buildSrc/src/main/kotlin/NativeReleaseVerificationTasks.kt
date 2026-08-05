import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
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
    abstract val extraEnvironment: MapProperty<String, String>

    @get:Internal
    abstract val workingDirectory: DirectoryProperty

    @get:Internal
    abstract val cargoTargetDirectory: DirectoryProperty

    init {
        extraEnvironment.convention(emptyMap())
    }

    @TaskAction
    fun runCargo() {
        val rustc = rustTool("rustc")
        val rustdoc = rustTool("rustdoc")
        exec.exec {
            workingDir(workingDirectory)
            commandLine("rustup", "run", toolchain.get(), "cargo", *cargoArguments.get().toTypedArray())
            environment(extraEnvironment.get())
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

@CacheableTask
abstract class VerifyCodexIosProvenanceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val provenanceFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val adapterPatch: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val lockPatch: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bridgeManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bridgeSource: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val cHeader: RegularFileProperty

    @get:Input
    abstract val revision: Property<String>

    @get:Input
    abstract val archiveSha256: Property<String>

    @get:Input
    abstract val cargoLockSha256: Property<String>

    @get:Input
    abstract val preparedCargoLockSha256: Property<String>

    @get:Input
    abstract val rustToolchain: Property<String>

    @TaskAction
    fun verify() {
        val record = provenanceFile.get().asFile.readText()
        fun value(key: String): String =
            Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([^\"]+)\"")
                .find(record)
                ?.groupValues
                ?.get(1)
                ?: error("Missing iOS provenance value: $key")
        check(value("gitRevision") == revision.get()) { "Codex iOS revision provenance mismatch" }
        check(value("sourceArchiveSha256") == archiveSha256.get()) { "Codex iOS archive provenance mismatch" }
        check(value("cargoLockSha256") == cargoLockSha256.get()) { "Codex iOS Cargo.lock provenance mismatch" }
        check(value("preparedCargoLockSha256") == preparedCargoLockSha256.get()) {
            "Codex iOS prepared Cargo.lock provenance mismatch"
        }
        check(value("rustToolchain") == rustToolchain.get()) { "Codex iOS Rust toolchain provenance mismatch" }
        mapOf(
            "adapterPatchSha256" to adapterPatch,
            "lockPatchSha256" to lockPatch,
            "bridgeManifestSha256" to bridgeManifest,
            "bridgeSourceSha256" to bridgeSource,
            "cHeaderSha256" to cHeader,
        ).forEach { (key, file) ->
            check(file.get().asFile.sha256() == value(key)) { "Codex iOS $key mismatch" }
        }
    }
}

@CacheableTask
abstract class GenerateSha256Task : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val inputFile: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText("${inputFile.get().asFile.sha256()}\n")
        }
    }
}

@CacheableTask
abstract class VerifySwiftPackageBinaryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val checksumFile: RegularFileProperty

    @get:Input
    abstract val expectedUrl: Property<String>

    @TaskAction
    fun verify() {
        val contents = manifest.get().asFile.readText()
        val checksum = checksumFile.get().asFile.readText().trim()
        check(contents.contains("url: \"${expectedUrl.get()}\"")) { "SwiftPM release URL mismatch" }
        check(contents.contains("checksum: \"$checksum\"")) { "SwiftPM binary checksum mismatch" }
    }
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
