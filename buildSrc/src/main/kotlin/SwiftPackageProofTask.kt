import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The proof records one fresh immutable candidate build")
abstract class RecordSwiftPackageProofTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val expectedCommit: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Input abstract val expectedUrl: Property<String>
    @get:Input abstract val gitExecutable: Property<String>
    @get:Input abstract val swiftExecutable: Property<String>
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archiveFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val checksumFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifestFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val provenanceFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val xcodeVersionFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftVersionFile: RegularFileProperty
    @get:OutputFile abstract val proofFile: RegularFileProperty

    init {
        group = "release"
        description = "Records the single clean immutable SwiftPM candidate proof."
        gitExecutable.convention("git")
        swiftExecutable.convention("swift")
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun record() {
        val repository = repositoryDirectory.get().asFile.canonicalFile
        requireRootManifest(repository, manifestFile.get().asFile)
        val commit = verifyCleanCommit(exec, repository, gitExecutable.get(), expectedCommit.get())
        val proof = proofFile.get().asFile.canonicalFile
        val expectedProofParent = repository.resolve("build/protected-candidate/$commit/evidence").canonicalFile
        check(proof.name == "swiftpm-proof.json" && proof.parentFile == expectedProofParent) {
            "SwiftPM proof must use the canonical commit-isolated candidate evidence path"
        }

        val archive = archiveFile.get().asFile
        val checksumFile = checksumFile.get().asFile
        val checksum = swiftChecksum(exec, swiftExecutable.get(), archive)
        check(checksumFile.readText().trim() == checksum) {
            "Generated SwiftPM checksum does not match the candidate archive"
        }
        val manifestFile = manifestFile.get().asFile
        val manifest = parseSwiftManifest(manifestFile.readText(), expectedUrl.get())
        check(manifest.checksum == checksum) { "Package.swift checksum does not match the candidate ZIP" }
        val packageHash = manifestFile.releaseDigest()
        val committedPackage = exec.gitBytes(repository, gitExecutable.get(), "show", "$commit:Package.swift")
        check(ByteArrayInputStream(committedPackage).releaseDigest() == packageHash) {
            "Committed Package.swift does not match the checked-out manifest"
        }
        val provenanceHash = provenanceFile.get().asFile.releaseDigest()
        val xcodeHash = xcodeVersionFile.get().asFile.releaseDigest()
        val swiftHash = swiftVersionFile.get().asFile.releaseDigest()

        proof.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("protocol", JsonPrimitive("swiftpm-candidate-v1"))
            put("result", JsonPrimitive("passed"))
            put("version", JsonPrimitive(version.get()))
            put("candidateCommit", JsonPrimitive(commit))
            put("candidateTree", JsonPrimitive(exec.gitText(repository, gitExecutable.get(), "rev-parse", "$commit^{tree}")))
            put("cleanCheckout", JsonPrimitive(true))
            put("canonicalBuildRoot", JsonPrimitive(repository.path))
            put("archiveName", JsonPrimitive(archive.name))
            put("archiveBytes", JsonPrimitive(archive.length()))
            put("swiftPmChecksum", JsonPrimitive(checksum))
            put("checksumFileSha256", JsonPrimitive(checksumFile.releaseDigest()))
            put("packageSwiftUrl", JsonPrimitive(expectedUrl.get()))
            put("packageSwiftSha256", JsonPrimitive(packageHash))
            put("packageSwiftChecksum", JsonPrimitive(manifest.checksum))
            put("nativeProvenanceSha256", JsonPrimitive(provenanceHash))
            put("xcodeVersionSha256", JsonPrimitive(xcodeHash))
            put("swiftVersionSha256", JsonPrimitive(swiftHash))
            put("toolchainSha256", JsonPrimitive(toolchainDigest(xcodeHash, swiftHash)))
        })
    }
}

private val COMMIT = Regex("[0-9a-f]{40}")
private val CODEX_BINARY_TARGET = Regex(
    """(?s)(\.binaryTarget\s*\(\s*name\s*:\s*"CodexAgent"\s*,\s*url\s*:\s*")([^"]+)("\s*,\s*checksum\s*:\s*")([0-9a-f]{64})("\s*\))""",
)

private data class SwiftManifest(val checksum: String)

private fun parseSwiftManifest(contents: String, expectedUrl: String): SwiftManifest {
    val matches = CODEX_BINARY_TARGET.findAll(contents).toList()
    check(matches.size == 1) { "Package.swift must contain exactly one canonical CodexAgent binary target" }
    val match = matches.single()
    check(match.groupValues[2] == expectedUrl) { "SwiftPM release URL mismatch" }
    return SwiftManifest(match.groupValues[4])
}

private fun requireRootManifest(repository: File, manifest: File) {
    check(manifest.canonicalFile == repository.resolve("Package.swift").canonicalFile) {
        "SwiftPM candidate proof must use the root Package.swift"
    }
}

private fun requireCommit(value: String) {
    check(COMMIT.matches(value)) { "Immutable commit must be 40 lowercase hexadecimal characters: $value" }
}

private fun verifyCleanCommit(exec: ExecOperations, repository: File, git: String, expected: String): String {
    requireCommit(expected)
    val head = exec.gitText(repository, git, "rev-parse", "HEAD^{commit}")
    check(head == expected) { "Checked-out commit $head does not match expected immutable commit $expected" }
    val status = exec.gitText(repository, git, "status", "--porcelain=v1", "--untracked-files=normal")
    check(status.isBlank()) {
        "SwiftPM candidate proof requires a clean checkout with no non-ignored untracked files:\n$status"
    }
    return head
}

private fun swiftChecksum(exec: ExecOperations, swift: String, archive: File): String {
    check(archive.isFile) { "SwiftPM binary archive is missing: $archive" }
    val output = ByteArrayOutputStream()
    exec.exec {
        commandLine(swift, "package", "compute-checksum", archive.absolutePath)
        standardOutput = output
    }.assertNormalExitValue()
    val checksum = output.toString(UTF_8).trim()
    check(Regex("[0-9a-f]{64}").matches(checksum)) { "SwiftPM checksum is malformed: $checksum" }
    check(checksum == archive.releaseDigest()) { "SwiftPM and JDK SHA-256 checksums differ" }
    return checksum
}

private fun toolchainDigest(xcodeHash: String, swiftHash: String): String =
    "xcode.txt=$xcodeHash\nswift.txt=$swiftHash\n".byteInputStream().releaseDigest()

private fun ExecOperations.gitBytes(repository: File, git: String, vararg arguments: String): ByteArray {
    val output = ByteArrayOutputStream()
    exec {
        workingDir(repository)
        commandLine(git, *arguments)
        standardOutput = output
    }.assertNormalExitValue()
    return output.toByteArray()
}

private fun ExecOperations.gitText(repository: File, git: String, vararg arguments: String): String =
    gitBytes(repository, git, *arguments).toString(UTF_8).trim()
