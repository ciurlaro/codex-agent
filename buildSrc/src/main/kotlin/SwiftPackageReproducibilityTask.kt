import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
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

@DisableCachingByDefault(because = "The baseline records one fresh immutable build")
abstract class RecordSwiftPackageBaselineTask @Inject constructor(
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
        description = "Records Commit A and its single clean SwiftPM binary build."
        gitExecutable.convention("git")
        swiftExecutable.convention("swift")
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun record() {
        val repository = repositoryDirectory.get().asFile.canonicalFile
        val proof = proofFile.get().asFile.canonicalFile
        check(!proof.toPath().startsWith(repository.toPath())) {
            "SwiftPM baseline proof must be outside the repository so Commit B cleanup cannot remove it"
        }
        requireRootManifest(repository, manifestFile.get().asFile)
        val commit = verifyCleanCommit(exec, repository, gitExecutable.get(), expectedCommit.get())
        val archive = archiveFile.get().asFile
        val checksum = swiftChecksum(exec, swiftExecutable.get(), archive)
        check(checksumFile.get().asFile.readText().trim() == checksum) {
            "Generated SwiftPM checksum does not match Commit A archive"
        }
        val manifest = parseSwiftManifest(manifestFile.get().asFile.readText(), expectedUrl.get())
        val packageHash = manifestFile.get().asFile.releaseDigest()
        val committedPackage = exec.gitBytes(repository, gitExecutable.get(), "show", "$commit:Package.swift")
        check(ByteArrayInputStream(committedPackage).releaseDigest() == packageHash) {
            "Commit A Package.swift does not match the checked-out manifest"
        }
        proof.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("protocol", JsonPrimitive(SWIFTPM_AB_PROTOCOL))
            put("version", JsonPrimitive(version.get()))
            put("commitA", JsonPrimitive(commit))
            put("treeA", JsonPrimitive(exec.gitText(repository, gitExecutable.get(), "rev-parse", "$commit^{tree}")))
            put("archiveName", JsonPrimitive(archive.name))
            put("archiveBytes", JsonPrimitive(archive.length()))
            put("swiftPmChecksum", JsonPrimitive(checksum))
            put("packageSwiftSha256", JsonPrimitive(packageHash))
            put("packageSwiftChecksum", JsonPrimitive(manifest.checksum))
            put("nativeProvenanceSha256", JsonPrimitive(provenanceFile.get().asFile.releaseDigest()))
            put("toolchainSha256", JsonPrimitive(toolchainDigest(xcodeVersionFile.get().asFile, swiftVersionFile.get().asFile)))
        })
    }
}

@DisableCachingByDefault(because = "The final proof binds an immutable Commit B and release asset")
abstract class VerifySwiftPackageABTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val expectedCommit: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Input abstract val expectedUrl: Property<String>
    @get:Input abstract val gitExecutable: Property<String>
    @get:Input abstract val swiftExecutable: Property<String>
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val baselineProof: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archiveFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val checksumFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifestFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val provenanceFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val xcodeVersionFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftVersionFile: RegularFileProperty
    @get:OutputFile abstract val proofFile: RegularFileProperty

    init {
        group = "verification"
        description = "Verifies Commit B against Commit A without building native code."
        gitExecutable.convention("git")
        swiftExecutable.convention("swift")
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val repository = repositoryDirectory.get().asFile.canonicalFile
        requireRootManifest(repository, manifestFile.get().asFile)
        val commitB = verifyCleanCommit(exec, repository, gitExecutable.get(), expectedCommit.get())
        val baselineFile = baselineProof.get().asFile
        val baseline = baselineFile.readReleaseObject()
        check(baseline.releaseInt("schemaVersion") == 1 && baseline.releaseString("protocol") == SWIFTPM_AB_PROTOCOL) {
            "Unsupported SwiftPM A/B baseline proof"
        }
        check(baseline.releaseString("version") == version.get()) { "SwiftPM A/B version mismatch" }
        val commitA = baseline.releaseString("commitA").also(::requireCommit)
        check(
            exec.gitText(repository, gitExecutable.get(), "rev-parse", "$commitA^{tree}") ==
                baseline.releaseString("treeA"),
        ) { "Commit A tree does not match its baseline proof" }
        val parents = exec.gitText(repository, gitExecutable.get(), "rev-list", "--parents", "-n", "1", commitB)
            .split(Regex("\\s+"))
        check(parents.size == 2 && parents[1] == commitA) { "Commit B must have Commit A as its only direct parent" }

        val changedPaths = exec.gitText(
            repository, gitExecutable.get(), "diff", "--name-only", "--no-renames", commitA, commitB, "--",
        ).lines().filter(String::isNotBlank)
        check(changedPaths.isEmpty() || changedPaths == listOf("Package.swift")) {
            "Commit B may change only the root Package.swift checksum: $changedPaths"
        }
        check(exec.gitMode(repository, gitExecutable.get(), commitA) == exec.gitMode(repository, gitExecutable.get(), commitB)) {
            "Commit B must not change Package.swift mode or type"
        }

        val packageABytes = exec.gitBytes(repository, gitExecutable.get(), "show", "$commitA:Package.swift")
        check(ByteArrayInputStream(packageABytes).releaseDigest() == baseline.releaseString("packageSwiftSha256")) {
            "Commit A Package.swift hash does not match its baseline proof"
        }
        val packageA = parseSwiftManifest(packageABytes.toString(UTF_8), expectedUrl.get())
        val packageB = parseSwiftManifest(manifestFile.get().asFile.readText(), expectedUrl.get())
        check(packageA.checksum == baseline.releaseString("packageSwiftChecksum")) {
            "Commit A checksum metadata does not match its baseline proof"
        }
        check(packageA.normalized == packageB.normalized) {
            "Commit B contains SwiftPM metadata changes other than the CodexAgent checksum"
        }

        val archive = archiveFile.get().asFile
        val checksum = swiftChecksum(exec, swiftExecutable.get(), archive)
        check(checksum == baseline.releaseString("swiftPmChecksum")) { "Commit A/B SwiftPM checksums differ" }
        check(archive.name == baseline.releaseString("archiveName") && archive.length() == baseline.releaseLong("archiveBytes")) {
            "Commit A/B SwiftPM archive identity differs"
        }
        check(checksumFile.get().asFile.readText().trim() == checksum) { "Commit B checksum file mismatch" }
        check(packageB.checksum == checksum) { "Commit B Package.swift checksum does not match its ZIP" }
        check(provenanceFile.get().asFile.releaseDigest() == baseline.releaseString("nativeProvenanceSha256")) {
            "Commit A/B native provenance differs"
        }
        check(toolchainDigest(xcodeVersionFile.get().asFile, swiftVersionFile.get().asFile) == baseline.releaseString("toolchainSha256")) {
            "Commit A/B Apple toolchain evidence differs"
        }

        proofFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("protocol", JsonPrimitive(SWIFTPM_AB_PROTOCOL))
            put("version", JsonPrimitive(version.get()))
            put("commitA", JsonPrimitive(commitA))
            put("treeA", baseline.getValue("treeA"))
            put("commitB", JsonPrimitive(commitB))
            put("treeB", JsonPrimitive(exec.gitText(repository, gitExecutable.get(), "rev-parse", "$commitB^{tree}")))
            put("changedPaths", buildJsonArray { changedPaths.forEach { add(JsonPrimitive(it)) } })
            put("baselineProofSha256", JsonPrimitive(baselineFile.releaseDigest()))
            put("archiveName", JsonPrimitive(archive.name))
            put("archiveBytes", JsonPrimitive(archive.length()))
            put("baselineChecksum", JsonPrimitive(baseline.releaseString("swiftPmChecksum")))
            put("finalChecksum", JsonPrimitive(checksum))
            put("packageSwiftSha256", JsonPrimitive(manifestFile.get().asFile.releaseDigest()))
            put("nativeProvenanceSha256", JsonPrimitive(provenanceFile.get().asFile.releaseDigest()))
            put("toolchainSha256", JsonPrimitive(baseline.releaseString("toolchainSha256")))
        })
    }
}

private const val SWIFTPM_AB_PROTOCOL = "swiftpm-ab-v1"
private val COMMIT = Regex("[0-9a-f]{40}")
private val CODEX_BINARY_TARGET = Regex(
    """(?s)(\.binaryTarget\s*\(\s*name\s*:\s*"CodexAgent"\s*,\s*url\s*:\s*")([^"]+)("\s*,\s*checksum\s*:\s*")([0-9a-f]{64})("\s*\))""",
)

private data class SwiftManifest(val checksum: String, val normalized: String)

private fun parseSwiftManifest(contents: String, expectedUrl: String): SwiftManifest {
    val matches = CODEX_BINARY_TARGET.findAll(contents).toList()
    check(matches.size == 1) { "Package.swift must contain exactly one canonical CodexAgent binary target" }
    val match = matches.single()
    check(match.groupValues[2] == expectedUrl) { "SwiftPM release URL mismatch" }
    val checksumGroup = checkNotNull(match.groups[4])
    return SwiftManifest(match.groupValues[4], contents.replaceRange(checksumGroup.range, "<SWIFTPM-CHECKSUM>"))
}

private fun requireRootManifest(repository: File, manifest: File) {
    check(manifest.canonicalFile == repository.resolve("Package.swift").canonicalFile) {
        "SwiftPM A/B proof must use the root Package.swift"
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
        "SwiftPM A/B proof requires a clean checkout with no non-ignored untracked files:\n$status"
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

private fun toolchainDigest(xcode: File, swift: File): String {
    val record = "xcode.txt=${xcode.releaseDigest()}\nswift.txt=${swift.releaseDigest()}\n"
    return record.byteInputStream().releaseDigest()
}

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

private fun ExecOperations.gitMode(repository: File, git: String, commit: String): String =
    gitText(repository, git, "ls-tree", commit, "--", "Package.swift").substringBefore('\t').substringBeforeLast(' ')
