import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class PreparePinnedArchiveTask : DefaultTask() {
    @get:Input abstract val sourceUrl: Property<String>
    @get:Input abstract val expectedSha256: Property<String>
    @get:InputFile @get:Optional @get:PathSensitive(PathSensitivity.NONE)
    abstract val localArchive: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val expected = expectedSha256.get()
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid pinned archive SHA-256" }
        val output = outputFile.get().asFile
        if (output.isFile && output.releaseDigest() == expected) return
        output.parentFile.mkdirs()
        val temporary = temporaryDir.resolve(output.name)
        if (localArchive.isPresent) {
            Files.copy(localArchive.get().asFile.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            val uri = URI(sourceUrl.get())
            check(uri.scheme == "https") { "Pinned archive URL must use HTTPS" }
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build()
            val response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(60))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofFile(temporary.toPath()))
            check(response.statusCode() in 200..299 && response.uri().scheme == "https") {
                "Pinned archive download failed"
            }
        }
        check(temporary.releaseDigest() == expected) { "Pinned archive SHA-256 mismatch" }
        Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}
