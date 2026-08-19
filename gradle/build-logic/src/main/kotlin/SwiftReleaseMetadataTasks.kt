import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.LocalState
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

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
            writeText("${inputFile.get().asFile.releaseDigest()}\n")
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
        check(contents.contains("checksum: \"$checksum\"")) {
            "SwiftPM binary checksum mismatch: Package.swift must use $checksum"
        }
    }
}

abstract class VerifyReleaseMetadataTask : DefaultTask() {
    @get:Input
    abstract val projectVersion: Property<String>

    @get:Input
    abstract val releaseTag: Property<String>

    init {
        releaseTag.convention(projectVersion.map { "v$it" })
    }

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

internal fun publicSwiftResolutionArguments(derivedData: File, packages: File): List<String> = listOf(
    "-scheme", "CodexAgentRemoteConsumer",
    "-configuration", "Release",
    "-destination", "generic/platform=iOS Simulator",
    "-derivedDataPath", derivedData.absolutePath,
    "-clonedSourcePackagesDirPath", packages.absolutePath,
    "-disablePackageRepositoryCache",
    "CODE_SIGNING_ALLOWED=NO",
    "clean", "build",
)

@DisableCachingByDefault(because = "This task verifies an already-public release asset and package resolution")
abstract class VerifyPublicSwiftResolutionTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val assetUrl: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val candidateManifest: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val consumerDirectory: DirectoryProperty
    @get:LocalState abstract val derivedDataDirectory: DirectoryProperty
    @get:LocalState abstract val packagesDirectory: DirectoryProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun verifyPublicResolution() {
        val manifest = candidateManifest.get().asFile.readReleaseObject()
        verifyCandidateManifestStructure(manifest)
        val expected = manifest.releaseObject("artifacts").releaseObject("swiftPackage").releaseString("sha256")
        val uri = URI(assetUrl.get())
        check(uri.scheme == "https") { "Public Swift asset URL must use HTTPS" }
        val temporary = Files.createTempFile("codex-agent-public-swift-", ".zip")
        try {
            val response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(60)).build().send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(temporary),
                )
            check(response.statusCode() in 200..299 && response.uri().scheme == "https") {
                "Public Swift asset download failed: HTTP ${response.statusCode()}"
            }
            check(temporary.toFile().releaseDigest() == expected) { "Public Swift asset SHA-256 mismatch" }
            derivedDataDirectory.get().asFile.deleteRecursively()
            packagesDirectory.get().asFile.deleteRecursively()
            exec.exec {
                workingDir(consumerDirectory)
                executable("xcodebuild")
                args(publicSwiftResolutionArguments(derivedDataDirectory.get().asFile, packagesDirectory.get().asFile))
            }.assertNormalExitValue()
            outputFile.get().asFile.atomicWriteJson(buildJsonObject {
                put("schemaVersion", JsonPrimitive(1))
                put("result", JsonPrimitive("passed"))
                put("assetUrl", JsonPrimitive(uri.toString()))
                put("assetSha256", JsonPrimitive(expected))
                put("swiftPackageResolution", JsonPrimitive("passed"))
            })
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
