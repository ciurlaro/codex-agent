import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
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

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val workingDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceInputs: ConfigurableFileCollection

    @get:Input
    abstract val provenanceValues: MapProperty<String, String>

    @get:Internal
    abstract val cargoTargetDirectory: DirectoryProperty

    init {
        extraEnvironment.convention(emptyMap())
        provenanceValues.convention(emptyMap())
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
    abstract val sqliteWorkspacePatch: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sqliteSourcePatch: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bridgeManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bridgeSource: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val cHeader: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sqliteArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sqlitePatch: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val workspaceCargoPatch: RegularFileProperty

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

    @get:Input
    abstract val sqliteVersion: Property<String>

    @get:Input
    abstract val sqliteArchiveSha256: Property<String>

    @get:Input
    abstract val sqliteSourceSha256: Property<String>

    @get:Input
    abstract val patchedSqliteSourceSha256: Property<String>

    @get:Input
    abstract val releaseLto: Property<String>

    @get:Input
    abstract val releaseCodegenUnits: Property<String>

    @get:Input
    abstract val releaseRustFlags: Property<String>

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
        check(value("libsqlite3SysVersion") == sqliteVersion.get()) {
            "Codex iOS libsqlite3-sys version provenance mismatch"
        }
        check(value("libsqlite3SysArchiveSha256") == sqliteArchiveSha256.get()) {
            "Codex iOS libsqlite3-sys archive provenance mismatch"
        }
        check(value("sqliteSourceSha256") == sqliteSourceSha256.get()) {
            "Codex iOS SQLite source provenance mismatch"
        }
        check(value("patchedSqliteSourceSha256") == patchedSqliteSourceSha256.get()) {
            "Codex iOS patched SQLite source provenance mismatch"
        }
        check(value("releaseLto") == releaseLto.get()) { "Codex iOS release LTO provenance mismatch" }
        check(value("releaseCodegenUnits") == releaseCodegenUnits.get()) {
            "Codex iOS release codegen-units provenance mismatch"
        }
        check(value("releaseRustFlags") == releaseRustFlags.get()) { "Codex iOS release Rust flags provenance mismatch" }
        check(value("sqliteSourceArchiveSha256") == sqliteArchiveSha256.get()) {
            "Codex iOS SQLite archive provenance mismatch"
        }
        check(sqliteArchive.get().asFile.sha256() == sqliteArchiveSha256.get()) {
            "Codex iOS SQLite archive SHA-256 mismatch"
        }
        mapOf(
            "adapterPatchSha256" to adapterPatch,
            "lockPatchSha256" to lockPatch,
            "sqliteWorkspacePatchSha256" to sqliteWorkspacePatch,
            "sqliteSourcePatchSha256" to sqliteSourcePatch,
            "bridgeManifestSha256" to bridgeManifest,
            "bridgeSourceSha256" to bridgeSource,
            "cHeaderSha256" to cHeader,
            "sqlitePatchSha256" to sqlitePatch,
            "workspaceCargoPatchSha256" to workspaceCargoPatch,
        ).forEach { (key, file) ->
            check(file.get().asFile.sha256() == value(key)) { "Codex iOS $key mismatch" }
        }
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
