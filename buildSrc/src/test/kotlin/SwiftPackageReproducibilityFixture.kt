import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.testfixtures.ProjectBuilder

internal class SwiftPackageFixture(root: File, initialChecksumMatches: Boolean) {
    val repo = root.resolve("repo").apply { mkdirs() }
    val archive = repo.resolve("build/distributions/CodexAgent-0.2.0.xcframework.zip")
    val checksum = repo.resolve("build/distributions/CodexAgent-0.2.0.xcframework.zip.sha256")
    val manifest = repo.resolve("Package.swift")
    val provenance = repo.resolve("native/provenance.json")
    private val xcode = repo.resolve("build/reports/ios-release/toolchain/xcode.txt")
    private val swiftVersion = repo.resolve("build/reports/ios-release/toolchain/swift.txt")
    private val fakeSwift = root.resolve("swift")
    private val baselineProof = root.resolve("evidence/swiftpm-baseline.json")
    val finalProof = root.resolve("evidence/swiftpm-ab.json")
    private val project = ProjectBuilder.builder().withProjectDir(repo).build()
    val commitA: String
    var commitB = ""

    init {
        writeArchive("stable archive bytes")
        manifest.writeText(packageSwift(if (initialChecksumMatches) archive.releaseDigest() else "0".repeat(64)))
        provenance.apply { parentFile.mkdirs(); writeText("{\"source\":\"pinned\"}\n") }
        xcode.apply { parentFile.mkdirs(); writeText("Xcode 26.6\nBuild version 17F113\n") }
        swiftVersion.writeText("Apple Swift version 6.3.3\n")
        fakeSwift.writeText("#!/bin/sh\ncat \"\$3.swiftpm\"\n")
        fakeSwift.setExecutable(true)
        git("init", "-q")
        git("config", "user.name", "Codex Test")
        git("config", "user.email", "codex@example.invalid")
        repo.resolve(".gitignore").writeText("/.gradle/\n/build/\n/userHome/\n")
        git("add", ".gitignore", "Package.swift", "native/provenance.json")
        git("commit", "-q", "-m", "Commit A")
        commitA = git("rev-parse", "HEAD")
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

    fun record(expected: String = commitA): File {
        recordTask(expected).record()
        return baselineProof
    }

    fun commitChecksumB() {
        manifest.writeText(packageSwift(archive.releaseDigest()))
        commitFiles("Package.swift")
    }

    fun commitEmptyB() = commitEmpty("Commit B")

    fun commitEmpty(message: String) {
        git("commit", "-q", "--allow-empty", "-m", message)
        commitB = git("rev-parse", "HEAD")
    }

    fun commitFiles(vararg paths: String) {
        git("add", *paths)
        git("commit", "-q", "-m", "Commit B")
        commitB = git("rev-parse", "HEAD")
    }

    fun verify() = verifyTask().verify()

    fun preflight() = preflightTask().preflight()

    fun preflightTask() = project.tasks.register(
        "preflightB${project.tasks.names.size}", PreflightSwiftPackageBTask::class.java,
    ).get().apply {
        version.set("0.2.0"); expectedUrl.set(SWIFTPM_TEST_URL); repositoryDirectory.set(repo)
        expectedCommit.set(commitB.ifBlank { commitA }); baselineProof.set(this@SwiftPackageFixture.baselineProof)
        manifestFile.set(manifest)
    }

    private fun recordTask(expected: String) = project.tasks.register(
        "recordBaseline${project.tasks.names.size}", RecordSwiftPackageBaselineTask::class.java,
    ).get().apply {
        common(this)
        expectedCommit.set(expected)
        proofFile.set(baselineProof)
    }

    fun verifyTask() = project.tasks.register(
        "verifyAB${project.tasks.names.size}", VerifySwiftPackageABTask::class.java,
    ).get().apply {
        common(this)
        expectedCommit.set(commitB.ifBlank { commitA })
        baselineProof.set(this@SwiftPackageFixture.baselineProof)
        proofFile.set(finalProof)
    }

    private fun common(task: DefaultTask) {
        when (task) {
            is RecordSwiftPackageBaselineTask -> task.configureCommon()
            is VerifySwiftPackageABTask -> task.configureCommon()
        }
    }

    private fun RecordSwiftPackageBaselineTask.configureCommon() {
        version.set("0.2.0"); expectedUrl.set(SWIFTPM_TEST_URL); repositoryDirectory.set(repo)
        archiveFile.set(archive); checksumFile.set(checksum); manifestFile.set(manifest)
        provenanceFile.set(provenance); xcodeVersionFile.set(xcode); swiftVersionFile.set(swiftVersion)
        swiftExecutable.set(fakeSwift.absolutePath)
    }

    private fun VerifySwiftPackageABTask.configureCommon() {
        version.set("0.2.0"); expectedUrl.set(SWIFTPM_TEST_URL); repositoryDirectory.set(repo)
        archiveFile.set(archive); checksumFile.set(checksum); manifestFile.set(manifest)
        provenanceFile.set(provenance); xcodeVersionFile.set(xcode); swiftVersionFile.set(swiftVersion)
        swiftExecutable.set(fakeSwift.absolutePath)
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", *arguments)).directory(repo).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output
    }
}

private const val SWIFTPM_TEST_URL =
    "https://github.com/ciurlaro/codex-agent/releases/download/v0.2.0/CodexAgent-0.2.0.xcframework.zip"
