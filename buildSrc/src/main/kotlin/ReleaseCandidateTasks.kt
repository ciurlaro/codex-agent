import groovy.json.JsonOutput
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class PreparePinnedArchiveTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localArchive: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val expected = expectedSha256.get()
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid pinned archive SHA-256" }
        val output = outputFile.get().asFile
        if (output.isFile && output.sha256() == expected) return
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
        check(temporary.sha256() == expected) { "Pinned archive SHA-256 mismatch" }
        Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

@CacheableTask
abstract class VerifyMavenStagingTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val groupId: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val expectedArtifactIds: ListProperty<String>

    @get:Input
    abstract val rootMetadataArtifactIds: ListProperty<String>

    @get:Input
    abstract val requireSignatures: Property<Boolean>

    @get:OutputFile
    abstract val inventoryFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val repository = repositoryDirectory.get().asFile
        val groupRoot = repository.resolve(groupId.get().replace('.', '/'))
        val expected = expectedArtifactIds.get().toSortedSet()
        val actual = groupRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.resolve(version.get()).isDirectory }
            .map(File::getName)
            .toSortedSet()
        check(actual == expected) { "Maven publication set mismatch: expected=$expected actual=$actual" }

        expected.forEach { artifactId ->
            val directory = groupRoot.resolve("$artifactId/${version.get()}")
            val prefix = "$artifactId-${version.get()}"
            check(directory.resolve("$prefix.pom").isFile) { "$artifactId POM is missing" }
            if (artifactId in rootMetadataArtifactIds.get()) {
                check(directory.resolve("$prefix.module").isFile) { "$artifactId Gradle metadata is missing" }
            }
            val publishable = directory.listFiles().orEmpty().filter { file ->
                file.isFile && file.extension in setOf("pom", "module", "jar", "aar", "klib") &&
                    !file.name.contains("-sources") && !file.name.contains("-javadoc")
            }
            check(publishable.any { it.extension in setOf("jar", "aar", "klib") }) {
                "$artifactId target binary is missing"
            }
            publishable.forEach { file ->
                check(directory.resolve("${file.name}.md5").isFile) { "${file.name}.md5 is missing" }
                check(directory.resolve("${file.name}.sha1").isFile) { "${file.name}.sha1 is missing" }
                if (requireSignatures.get()) {
                    check(directory.resolve("${file.name}.asc").isFile) { "${file.name}.asc is missing" }
                }
            }
        }

        val files = repository.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(repository).path }
            .map { file ->
                linkedMapOf(
                    "path" to file.relativeTo(repository).invariantSeparatorsPath,
                    "bytes" to file.length(),
                    "sha256" to file.sha256(),
                )
            }.toList()
        inventoryFile.get().asFile.writeJson(
            linkedMapOf(
                "schemaVersion" to 1,
                "groupId" to groupId.get(),
                "version" to version.get(),
                "artifactIds" to expected,
                "signaturesRequired" to requireSignatures.get(),
                "files" to files,
            ),
        )
    }
}

@CacheableTask
abstract class GenerateBundleInventoryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundleFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mavenInventory: RegularFileProperty

    @get:Input
    abstract val maximumBytes: Property<Long>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val bundle = bundleFile.get().asFile
        check(bundle.length() <= maximumBytes.get()) {
            "Central bundle exceeds Portal limit: ${bundle.length()} > ${maximumBytes.get()}"
        }
        outputFile.get().asFile.writeJson(
            linkedMapOf(
                "schemaVersion" to 1,
                "bundleFile" to bundle.name,
                "bundleBytes" to bundle.length(),
                "portalMaximumBytes" to maximumBytes.get(),
                "bundleSha256" to bundle.sha256(),
                "mavenInventorySha256" to mavenInventory.get().asFile.sha256(),
            ),
        )
    }
}

private fun File.writeJson(value: Any?) {
    parentFile.mkdirs()
    writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n")
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
