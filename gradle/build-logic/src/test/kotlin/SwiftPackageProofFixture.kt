import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.gradle.testfixtures.ProjectBuilder

internal class SwiftPackageFixture(root: File, checksumMatches: Boolean = true) {
    val repo = root.resolve("repo").apply { mkdirs() }
    val archive = repo.resolve("build/distributions/CodexAgent-0.2.0.xcframework.zip")
    val checksum = repo.resolve("build/distributions/CodexAgent-0.2.0.xcframework.zip.sha256")
    val manifest = repo.resolve("Package.swift")
    val provenance = repo.resolve("codex-agent-runtime-ios/native/provenance.json")
    private val xcode = repo.resolve("build/reports/ios-release/toolchain/xcode.txt")
    private val swiftVersion = repo.resolve("build/reports/ios-release/toolchain/swift.txt")
    private val fakeSwift = root.resolve("swift")
    private val project = ProjectBuilder.builder().withProjectDir(repo).build()
    val commit: String
    val proof get() = repo.resolve("build/protected-candidate/$commit/evidence/swiftpm-proof.json")

    init {
        writeArchive("stable archive bytes")
        manifest.writeText(packageSwift(if (checksumMatches) archive.releaseDigest() else "0".repeat(64)))
        provenance.apply { parentFile.mkdirs(); writeText("{\"source\":\"pinned\"}\n") }
        xcode.apply { parentFile.mkdirs(); writeText("Xcode 26.6\nBuild version 17F113\n") }
        swiftVersion.writeText("Apple Swift version 6.3.3\n")
        fakeSwift.writeText("#!/bin/sh\ncat \"\$3.swiftpm\"\n")
        fakeSwift.setExecutable(true)
        git("init", "-q")
        git("config", "user.name", "Codex Test")
        git("config", "user.email", "codex@example.invalid")
        repo.resolve(".gitignore").writeText("/.gradle/\n/build/\n/userHome/\n")
        git("add", ".gitignore", "Package.swift", "codex-agent-runtime-ios/native/provenance.json")
        git("commit", "-q", "-m", "Immutable candidate")
        commit = git("rev-parse", "HEAD")
    }

    fun packageSwift(value: String) = """
        // swift-tools-version: 5.9
        import PackageDescription
        let package = Package(
            name: "CodexAgent",
            platforms: [.iOS(.v15)],
            targets: [
                .binaryTarget(
                    name: "CodexAgent",
                    url: "$SWIFTPM_TEST_URL",
                    checksum: "$value"
                )
            ]
        )
    """.trimIndent() + "\n"

    fun writeArchive(contents: String) {
        archive.parentFile.mkdirs()
        archive.writeText(contents)
        val digest = archive.releaseDigest()
        checksum.writeText("$digest\n")
        archive.resolveSibling("${archive.name}.swiftpm").writeText("$digest\n")
    }

    fun writeUntracked(path: String) {
        repo.resolve(path).apply { parentFile.mkdirs(); writeText("untracked input\n") }
    }

    fun record(expected: String = commit, output: File = proof): File {
        task(expected, output).record()
        return output
    }

    fun task(expected: String = commit, output: File = proof) = project.tasks.register(
        "recordProof${project.tasks.names.size}", RecordSwiftPackageProofTask::class.java,
    ).get().apply {
        version.set("0.2.0")
        expectedUrl.set(SWIFTPM_TEST_URL)
        repositoryDirectory.set(repo)
        expectedCommit.set(expected)
        archiveFile.set(archive)
        checksumFile.set(checksum)
        manifestFile.set(manifest)
        provenanceFile.set(provenance)
        xcodeVersionFile.set(xcode)
        swiftVersionFile.set(swiftVersion)
        swiftExecutable.set(fakeSwift.absolutePath)
        proofFile.set(output)
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", *arguments)).directory(repo).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }
}

internal fun writeTestSwiftPackageProof(
    file: File,
    archive: File,
    checksum: File,
    packageSwift: File,
    commit: String,
    version: String,
    buildRoot: File,
) {
    val hash = archive.releaseDigest()
    file.atomicWriteJson(buildJsonObject {
        put("schemaVersion", JsonPrimitive(1)); put("protocol", JsonPrimitive("swiftpm-candidate-v1"))
        put("result", JsonPrimitive("passed")); put("version", JsonPrimitive(version))
        put("candidateCommit", JsonPrimitive(commit)); put("candidateTree", JsonPrimitive("1".repeat(40)))
        put("cleanCheckout", JsonPrimitive(true)); put("canonicalBuildRoot", JsonPrimitive(buildRoot.canonicalPath))
        put("archiveName", JsonPrimitive(archive.name)); put("archiveBytes", JsonPrimitive(archive.length()))
        put("swiftPmChecksum", JsonPrimitive(hash)); put("checksumFileSha256", JsonPrimitive(checksum.releaseDigest()))
        put("packageSwiftUrl", JsonPrimitive(
            "https://github.com/codex-agent-labs/codex-agent/releases/download/v$version/${archive.name}",
        ))
        put("packageSwiftSha256", JsonPrimitive(packageSwift.releaseDigest()))
        put("packageSwiftChecksum", JsonPrimitive(hash))
        put("nativeProvenanceSha256", JsonPrimitive("2".repeat(64)))
        put("xcodeVersionSha256", JsonPrimitive("3".repeat(64)))
        put("swiftVersionSha256", JsonPrimitive("4".repeat(64)))
        put("toolchainSha256", JsonPrimitive("5".repeat(64)))
    })
}

private const val SWIFTPM_TEST_URL =
    "https://github.com/codex-agent-labs/codex-agent/releases/download/v0.2.0/CodexAgent-0.2.0.xcframework.zip"
