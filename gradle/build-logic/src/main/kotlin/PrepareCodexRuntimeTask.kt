import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class PrepareCodexRuntimeTask @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:Input
    abstract val codexVersion: Property<String>

    @get:Input
    abstract val archiveSha256: Property<String>

    @get:Input
    abstract val binarySha256: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localArchive: org.gradle.api.file.RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun prepare() {
        val version = codexVersion.get()
        val url = URI("https://github.com/openai/codex/releases/download/rust-v$version/$ASSET.tar.gz")
        requireHash(archiveSha256.get(), "archive")
        requireHash(binarySha256.get(), "binary")

        val temporary = Files.createTempDirectory(temporaryDir.toPath(), "download-")
        try {
            val archive = temporary.resolve("runtime.tar.gz")
            if (localArchive.isPresent) {
                Files.copy(localArchive.get().asFile.toPath(), archive, StandardCopyOption.REPLACE_EXISTING)
            } else {
                downloadHttps(url, archive)
            }
            check(archive.toFile().sha256() == archiveSha256.get()) {
                "Codex runtime archive SHA-256 mismatch"
            }
            val extracted = temporary.resolve("extracted").toFile().also { it.mkdirs() }
            files.copy {
                from(archives.tarTree(archives.gzip(archive.toFile())))
                into(extracted)
            }
            val entries = extracted.walkTopDown().filter { it.isFile }.toList()
            check(entries.size == 1 && entries.single().relativeTo(extracted).invariantSeparatorsPath == ASSET) {
                "Codex runtime archive must contain exactly the root executable '$ASSET'"
            }
            val runtime = entries.single()
            check(runtime.sha256() == binarySha256.get()) { "Codex runtime binary SHA-256 mismatch" }
            installAtomically(runtime)
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun installAtomically(source: java.io.File) {
        val output = outputDirectory.get().asFile.resolve(
            "${CodexAgentBuild.ABI}/${CodexAgentBuild.RUNTIME_LIBRARY}",
        )
        output.parentFile.mkdirs()
        val staged = output.toPath().resolveSibling(".${output.name}.${System.nanoTime()}.tmp")
        try {
            Files.copy(source.toPath(), staged, StandardCopyOption.REPLACE_EXISTING)
            staged.toFile().setExecutable(true, false)
            check(staged.toFile().canExecute()) { "could not make Codex runtime executable" }
            try {
                Files.move(staged, output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staged, output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun requireHash(value: String, label: String) {
        check(value.matches(Regex("[0-9a-f]{64}"))) { "invalid Codex runtime $label SHA-256" }
    }

    private fun java.io.File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val ASSET = "codex-app-server-aarch64-unknown-linux-musl"
    }
}
