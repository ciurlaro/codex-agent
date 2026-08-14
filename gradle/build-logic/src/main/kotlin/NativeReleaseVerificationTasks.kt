import java.io.File
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bridgeSource: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val cHeader: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val codexArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sqliteArchive: RegularFileProperty

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
    abstract val rustSrcComponent: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val rustSrcManifest: RegularFileProperty

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

    @get:Input
    abstract val releaseRustPathRemapPolicy: MapProperty<String, String>

    @get:Input
    abstract val minimumIosVersion: Property<String>

    @get:Input
    abstract val releaseDebug: Property<String>

    @get:Input
    abstract val releaseStrip: Property<String>

    @get:Input
    abstract val sqliteCompileFlags: Property<String>

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
        check(codexArchive.get().asFile.sha256() == archiveSha256.get()) {
            "Codex iOS source archive SHA-256 mismatch"
        }
        check(value("cargoLockSha256") == cargoLockSha256.get()) { "Codex iOS Cargo.lock provenance mismatch" }
        check(value("preparedCargoLockSha256") == preparedCargoLockSha256.get()) {
            "Codex iOS prepared Cargo.lock provenance mismatch"
        }
        check(value("rustToolchain") == rustToolchain.get()) { "Codex iOS Rust toolchain provenance mismatch" }
        check(value("rustSrcComponent") == rustSrcComponent.get()) { "Codex iOS rust-src provenance mismatch" }
        check(rustSrcManifest.get().asFile.isFile) { "Pinned Rust rust-src component is missing" }
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
        releaseRustPathRemapPolicy.get().forEach { (key, expected) ->
            check(value(key) == expected) { "Codex iOS $key provenance mismatch" }
        }
        check(value("minimumIosVersion") == minimumIosVersion.get()) {
            "Codex iOS deployment target provenance mismatch"
        }
        check(value("releaseDebug") == releaseDebug.get()) { "Codex iOS release debug provenance mismatch" }
        check(value("releaseStrip") == releaseStrip.get()) { "Codex iOS release strip provenance mismatch" }
        check(value("sqliteCompileFlags") == sqliteCompileFlags.get()) {
            "Codex iOS SQLite compiler flags provenance mismatch"
        }
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
            "cHeaderSha256" to cHeader,
        ).forEach { (key, file) ->
            check(file.get().asFile.sha256() == value(key)) { "Codex iOS $key mismatch" }
        }
        check(bridgeSource.get().asFile.treeSha256() == value("bridgeSourceSha256")) {
            "Codex iOS bridgeSourceSha256 mismatch"
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

private fun File.treeSha256(): String {
    val root = this
    val digest = MessageDigest.getInstance("SHA-256")
    walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
        digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
        digest.update(byteArrayOf(0))
        digest.update(file.length().toString().toByteArray())
        digest.update(byteArrayOf(0))
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
