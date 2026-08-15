import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class PrepareCodexIosSourceTask @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input
    abstract val revision: Property<String>

    @get:Input
    abstract val archiveSha256: Property<String>

    @get:Input
    abstract val cargoLockSha256: Property<String>

    @get:Input
    abstract val preparedCargoLockSha256: Property<String>

    @get:Input
    abstract val sqliteVersion: Property<String>

    @get:Input
    abstract val sqliteArchiveSha256: Property<String>

    @get:Input
    abstract val sqliteSourceSha256: Property<String>

    @get:Input
    abstract val patchedSqliteSourceSha256: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sourceArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sqliteArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val sqlitePatch: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val patches: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bridgeSource: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val expectedRevision = revision.get()
        require(expectedRevision.matches(Regex("[0-9a-f]{40}"))) { "invalid Codex source revision" }
        requireHash(archiveSha256.get())
        requireHash(cargoLockSha256.get())
        requireHash(preparedCargoLockSha256.get())
        val expectedSqliteVersion = sqliteVersion.get()
        require(expectedSqliteVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            "invalid libsqlite3-sys version"
        }
        requireHash(sqliteArchiveSha256.get())
        requireHash(sqliteSourceSha256.get())
        requireHash(patchedSqliteSourceSha256.get())
        val temporary = Files.createTempDirectory(temporaryDir.toPath(), "source-")
        try {
            val archive = sourceArchive.get().asFile
            check(archive.releaseDigest() == archiveSha256.get()) {
                "Codex iOS source archive SHA-256 mismatch"
            }

            val extracted = temporary.resolve("extracted").toFile().also { it.mkdirs() }
            files.copy {
                from(archives.tarTree(archives.gzip(archive)))
                into(extracted)
            }
            val roots = extracted.listFiles().orEmpty().filter(java.io.File::isDirectory)
            check(roots.size == 1 && roots.single().name == "codex-$expectedRevision") {
                "Codex source archive must contain the exact revision root"
            }
            val staged = temporary.resolve("staged").toFile().also { it.mkdirs() }
            files.copy {
                from(roots.single())
                into(staged)
            }
            val cargoLock = staged.resolve("codex-rs/Cargo.lock")
            check(cargoLock.isFile && cargoLock.releaseDigest() == cargoLockSha256.get()) {
                "Codex iOS Cargo.lock SHA-256 mismatch"
            }

            val sqliteArchive = this.sqliteArchive.get().asFile
            check(sqliteArchive.releaseDigest() == sqliteArchiveSha256.get()) {
                "libsqlite3-sys archive SHA-256 mismatch"
            }
            val sqliteExtracted = temporary.resolve("sqlite-extracted").toFile().also { it.mkdirs() }
            files.copy {
                from(archives.tarTree(archives.gzip(sqliteArchive)))
                into(sqliteExtracted)
            }
            val sqliteRoots = sqliteExtracted.listFiles().orEmpty().filter(java.io.File::isDirectory)
            check(
                sqliteRoots.size == 1 &&
                    sqliteRoots.single().name == "libsqlite3-sys-$expectedSqliteVersion"
            ) {
                "libsqlite3-sys archive must contain the exact version root"
            }
            val sqliteRoot = sqliteRoots.single()
            val sqliteSource = sqliteRoot.resolve("sqlite3/sqlite3.c")
            check(sqliteSource.isFile && sqliteSource.releaseDigest() == sqliteSourceSha256.get()) {
                "libsqlite3-sys sqlite3.c SHA-256 mismatch"
            }
            exec.exec {
                workingDir(sqliteRoot)
                commandLine("patch", "-p1", "-N", "-i", sqlitePatch.get().asFile.absolutePath)
            }
            check(sqliteSource.releaseDigest() == patchedSqliteSourceSha256.get()) {
                "Patched libsqlite3-sys sqlite3.c SHA-256 mismatch"
            }
            files.copy {
                from(sqliteRoot)
                into(staged.resolve("codex-rs/third-party/libsqlite3-sys"))
            }

            patches.files.sortedBy(java.io.File::getName).forEach { patch ->
                exec.exec {
                    workingDir(staged)
                    commandLine("patch", "-p1", "-N", "-i", patch.absolutePath)
                }
            }
            check(cargoLock.releaseDigest() == preparedCargoLockSha256.get()) {
                "Prepared Codex iOS Cargo.lock SHA-256 mismatch: " +
                    "expected=${preparedCargoLockSha256.get()} actual=${cargoLock.releaseDigest()}"
            }
            files.copy {
                from(bridgeSource)
                into(staged.resolve("codex-rs/ios-bridge"))
            }
            check(staged.resolve("codex-rs/ios-bridge/Cargo.toml").isFile) {
                "staged iOS bridge is missing"
            }

            val output = outputDirectory.get().asFile
            files.delete { delete(output) }
            output.parentFile.mkdirs()
            try {
                Files.move(staged.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), output.toPath())
            }
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun requireHash(value: String) {
        check(value.matches(Regex("[0-9a-f]{64}"))) { "invalid Codex iOS source archive SHA-256" }
    }

}
