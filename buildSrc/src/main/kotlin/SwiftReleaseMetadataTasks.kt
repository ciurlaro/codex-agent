import java.io.File
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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

abstract class VerifyReleaseMetadataTask : DefaultTask() {
    @get:Input
    abstract val projectVersion: Property<String>

    @get:Input
    abstract val releaseTag: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val swiftPackageManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val remoteConsumerManifest: RegularFileProperty

    @TaskAction
    fun verify() {
        val version = projectVersion.get()
        check(releaseTag.get() == "v$version") {
            "GitHub release tag must equal v$version"
        }

        val swiftPackage = swiftPackageManifest.get().asFile.readText()
        val url = Regex("""url\s*:\s*\"([^\"]+)\"""")
            .find(swiftPackage)
            ?.groupValues
            ?.get(1)
            ?: error("SwiftPM binary URL is missing")
        val release = Regex("""/releases/download/v([^/]+)/([^/\"]+)$""")
            .find(url)
            ?: error("SwiftPM binary URL is not a versioned GitHub release asset")
        check(release.groupValues[1] == version) {
            "SwiftPM binary URL version must equal $version"
        }
        check(release.groupValues[2] == "CodexAgent-$version.xcframework.zip") {
            "SwiftPM binary filename version must equal $version"
        }

        val remoteConsumer = remoteConsumerManifest.get().asFile.readText()
        val exactVersion = Regex(
            """\.package\s*\(\s*url\s*:\s*\"https://github\.com/ciurlaro/codex-agent\.git\"\s*,\s*exact\s*:\s*\"([^\"]+)\"\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(remoteConsumer)?.groupValues?.get(1)
            ?: error("RemoteConsumer exact codex-agent dependency is missing")
        check(exactVersion == version) {
            "RemoteConsumer exact dependency version must equal $version"
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
