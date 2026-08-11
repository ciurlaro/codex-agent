import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.api.DefaultTask
import org.gradle.testfixtures.ProjectBuilder

class SwiftPackageReproducibilityTaskTest {
    @Test
    fun `records exact clean Commit A proof`() = withFixture { fixture ->
        val proof = fixture.record().readReleaseObject()
        assertEquals(fixture.commitA, proof.releaseString("commitA"))
        assertEquals(fixture.archive.releaseDigest(), proof.releaseString("swiftPmChecksum"))
        assertEquals(fixture.archive.length(), proof.releaseLong("archiveBytes"))
    }

    @Test
    fun `baseline rejects a different checked out commit`() = withFixture { fixture ->
        val failure = assertFailsWith<IllegalStateException> { fixture.record("0".repeat(40)) }
        assertTrue(failure.message.orEmpty().contains("does not match"))
    }

    @Test
    fun `baseline rejects a dirty tracked checkout`() = withFixture { fixture ->
        fixture.manifest.appendText("\n// dirty\n")
        val failure = assertFailsWith<IllegalStateException> { fixture.record() }
        assertTrue(failure.message.orEmpty().contains("clean checkout"))
    }

    @Test
    fun `baseline rejects an untracked Swift input`() =
        assertUntrackedRejected("codex-agent-runtime-ios/apple/Sources/CodexAgentAuthentication/New.swift", false)

    @Test
    fun `baseline rejects an untracked Rust input`() =
        assertUntrackedRejected("codex-agent-runtime-ios/native/bridge/src/new.rs", false)

    @Test
    fun `Commit B rejects an untracked native patch input`() =
        assertUntrackedRejected("codex-agent-runtime-ios/native/patches/0004-untracked.patch", true)

    @Test
    fun `Commit B rejects an untracked Gradle configuration input`() = assertUntrackedRejected("gradle.properties", true)

    @Test
    fun `ignored build outputs are accepted for both commits`() = withFixture { fixture ->
        fixture.writeUntracked("build/generated/commit-a.bin")
        fixture.record()
        fixture.commitChecksumB()
        fixture.writeUntracked("build/generated/commit-b.bin")
        fixture.verify()
    }

    @Test
    fun `baseline rejects a checksum output mismatch`() = withFixture { fixture ->
        fixture.checksum.writeText("0".repeat(64))
        val failure = assertFailsWith<IllegalStateException> { fixture.record() }
        assertTrue(failure.message.orEmpty().contains("Generated SwiftPM checksum"))
    }

    @Test
    fun `accepts direct checksum-only Commit B`() = withFixture { fixture ->
        fixture.record()
        fixture.commitChecksumB()
        fixture.verify()
        assertEquals(fixture.commitB, fixture.finalProof.readReleaseObject().releaseString("commitB"))
    }

    @Test
    fun `accepts empty Commit B when checksum is already correct`() = withFixture(initialChecksumMatches = true) { fixture ->
        fixture.record()
        fixture.commitEmptyB()
        fixture.verify()
        assertEquals(0, fixture.finalProof.readReleaseObject().releaseArray("changedPaths").size)
    }

    @Test
    fun `rejects Commit B that is not the direct child of A`() = withFixture { fixture ->
        fixture.record()
        fixture.commitEmpty("intermediate")
        fixture.commitChecksumB()
        val failure = assertFailsWith<IllegalStateException> { fixture.verify() }
        assertTrue(failure.message.orEmpty().contains("direct parent"))
    }

    @Test
    fun `rejects any additional changed path`() = withFixture { fixture ->
        fixture.record()
        fixture.repo.resolve("extra.txt").writeText("not metadata")
        fixture.manifest.writeText(fixture.packageSwift(fixture.archive.releaseDigest()))
        fixture.commitFiles("Package.swift", "extra.txt")
        val failure = assertFailsWith<IllegalStateException> { fixture.verify() }
        assertTrue(failure.message.orEmpty().contains("only the root Package.swift"))
    }

    @Test
    fun `rejects normalized SwiftPM metadata changes`() = withFixture { fixture ->
        fixture.record()
        fixture.manifest.writeText(
            fixture.packageSwift(fixture.archive.releaseDigest()).replace(".iOS(.v15)", ".iOS(.v16)"),
        )
        fixture.commitFiles("Package.swift")
        val failure = assertFailsWith<IllegalStateException> { fixture.verify() }
        assertTrue(failure.message.orEmpty().contains("metadata changes"))
    }

    @Test
    fun `rejects archive and committed checksum mismatches`() {
        withFixture { fixture ->
            fixture.record()
            fixture.commitChecksumB()
            fixture.writeArchive("different B bytes")
            val failure = assertFailsWith<IllegalStateException> { fixture.verify() }
            assertTrue(failure.message.orEmpty().contains("checksums differ"))
        }
        withFixture { fixture ->
            fixture.record()
            fixture.manifest.writeText(fixture.packageSwift("f".repeat(64)))
            fixture.commitFiles("Package.swift")
            val failure = assertFailsWith<IllegalStateException> { fixture.verify() }
            assertTrue(failure.message.orEmpty().contains("does not match its ZIP"))
        }
    }

    @Test
    fun `rejects tampered Commit A proof`() = withFixture { fixture ->
        val proof = fixture.record()
        proof.writeText(proof.readText().replace(fixture.provenance.releaseDigest(), "0".repeat(64)))
        fixture.commitChecksumB()
        val failure = assertFailsWith<IllegalStateException> { fixture.verify() }
        assertTrue(failure.message.orEmpty().contains("provenance"))
    }

    @Test
    fun `Commit B verifier has no build dependency path`() = withFixture { fixture ->
        val task = fixture.verifyTask()
        assertTrue(task.taskDependencies.getDependencies(task).isEmpty())
    }

    private fun withFixture(initialChecksumMatches: Boolean = false, test: (Fixture) -> Unit) {
        val directory = createTempDirectory("swiftpm-ab").toFile()
        try {
            test(Fixture(directory, initialChecksumMatches))
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun assertUntrackedRejected(path: String, verifyCommitB: Boolean) = withFixture { fixture ->
        if (verifyCommitB) {
            fixture.record()
            fixture.commitChecksumB()
        }
        fixture.writeUntracked(path)
        val failure = assertFailsWith<IllegalStateException> {
            if (verifyCommitB) fixture.verify() else fixture.record()
        }
        assertTrue(failure.message.orEmpty().contains("non-ignored untracked"))
    }

    private class Fixture(root: File, initialChecksumMatches: Boolean) {
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
                        url: "$URL",
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
            baselineProof.set(this@Fixture.baselineProof)
            proofFile.set(finalProof)
        }

        private fun common(task: DefaultTask) {
            when (task) {
                is RecordSwiftPackageBaselineTask -> task.configureCommon()
                is VerifySwiftPackageABTask -> task.configureCommon()
            }
        }

        private fun RecordSwiftPackageBaselineTask.configureCommon() {
            version.set("0.2.0"); expectedUrl.set(URL); repositoryDirectory.set(repo)
            archiveFile.set(archive); checksumFile.set(checksum); manifestFile.set(manifest)
            provenanceFile.set(provenance); xcodeVersionFile.set(xcode); swiftVersionFile.set(swiftVersion)
            swiftExecutable.set(fakeSwift.absolutePath)
        }

        private fun VerifySwiftPackageABTask.configureCommon() {
            version.set("0.2.0"); expectedUrl.set(URL); repositoryDirectory.set(repo)
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

    private companion object {
        const val URL = "https://github.com/ciurlaro/codex-agent/releases/download/v0.2.0/CodexAgent-0.2.0.xcframework.zip"
    }
}
