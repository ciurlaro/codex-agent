import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
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
import org.gradle.api.tasks.Optional
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

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localArchive: RegularFileProperty

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
        val temporary = Files.createTempDirectory(temporaryDir.toPath(), "source-")
        try {
            val archive = temporary.resolve("codex.tar.gz")
            if (localArchive.isPresent) {
                Files.copy(localArchive.get().asFile.toPath(), archive, StandardCopyOption.REPLACE_EXISTING)
            } else {
                download(
                    URI("https://github.com/openai/codex/archive/$expectedRevision.tar.gz"),
                    archive,
                )
            }
            check(archive.toFile().sha256() == archiveSha256.get()) {
                "Codex iOS source archive SHA-256 mismatch"
            }

            val extracted = temporary.resolve("extracted").toFile().also { it.mkdirs() }
            files.copy {
                from(archives.tarTree(archives.gzip(archive.toFile())))
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
            check(cargoLock.isFile && cargoLock.sha256() == cargoLockSha256.get()) {
                "Codex iOS Cargo.lock SHA-256 mismatch"
            }
            patches.files.sortedBy(java.io.File::getName).forEach { patch ->
                exec.exec {
                    workingDir(staged)
                    commandLine("patch", "-p1", "-N", "-i", patch.absolutePath)
                }
            }
            check(cargoLock.sha256() == preparedCargoLockSha256.get()) {
                "Prepared Codex iOS Cargo.lock SHA-256 mismatch"
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

    private fun download(url: URI, target: java.nio.file.Path) {
        check(url.scheme == "https") { "Codex source download must use HTTPS" }
        val request = HttpRequest.newBuilder(url).timeout(REQUEST_TIMEOUT).GET().build()
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target))
        check(response.statusCode() in 200..299) {
            "Codex source download failed with HTTP ${response.statusCode()}"
        }
        check(response.uri().scheme == "https") { "Codex source redirected outside HTTPS" }
    }

    private fun requireHash(value: String) {
        check(value.matches(Regex("[0-9a-f]{64}"))) { "invalid Codex iOS source archive SHA-256" }
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
        private val CONNECT_TIMEOUT = Duration.ofSeconds(60)
        private val REQUEST_TIMEOUT = Duration.ofMinutes(5)
    }
}
