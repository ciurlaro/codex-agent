import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.Task
import org.gradle.testfixtures.ProjectBuilder

class SwiftPackageReproducibilityTaskTest {
    @Test
    fun `records exact clean Commit A proof`() = withFixture { fixture ->
        val proof = fixture.record().readReleaseObject()
        assertEquals(fixture.commitA, proof.releaseString("commitA"))
        assertEquals(fixture.repo.canonicalPath, proof.releaseString("canonicalBuildRoot"))
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
        fixture.preflight()
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
    fun `Commit B preflight rejects a different canonical build root`() = withFixture { fixture ->
        val proof = fixture.record()
        fixture.commitChecksumB()
        proof.writeText(proof.readText().replace(fixture.repo.canonicalPath, fixture.repo.resolve("other").canonicalPath))
        val failure = assertFailsWith<IllegalStateException> { fixture.preflight() }
        assertTrue(failure.message.orEmpty().contains("canonical build roots differ"))
    }

    @Test
    fun `Commit B preflight and verifier have no build dependency path`() = withFixture { fixture ->
        listOf(fixture.preflightTask(), fixture.verifyTask()).forEach { task ->
            assertTrue(task.taskDependencies.getDependencies(task).isEmpty())
        }
    }

    @Test
    fun `direct baseline graph retains producers without root or iOS clean`() {
        val root = ProjectBuilder.builder().withName("root").build()
        val ios = ProjectBuilder.builder().withName("codex-agent-runtime-ios").withParent(root).build()
        root.tasks.register("clean")
        ios.tasks.register("clean")
        val packageBinary = ios.tasks.register("packageCodexAgentSwiftPackageBinary")
        val checksum = ios.tasks.register("generateCodexAgentSwiftPackageChecksum") {
            dependsOn(packageBinary)
        }
        val toolchain = ios.tasks.register("verifyAppleToolchain")
        val baseline = ios.tasks.register("recordCodexAgentSwiftPackageBaseline") {
            dependsOnSwiftPackageBaselineProducers(toolchain, checksum)
        }

        val graph = linkedSetOf<Task>()
        fun visit(task: Task) {
            task.taskDependencies.getDependencies(task).forEach { if (graph.add(it)) visit(it) }
        }
        visit(baseline.get())

        assertEquals(
            setOf(
                ":codex-agent-runtime-ios:packageCodexAgentSwiftPackageBinary",
                ":codex-agent-runtime-ios:generateCodexAgentSwiftPackageChecksum",
                ":codex-agent-runtime-ios:verifyAppleToolchain",
            ),
            graph.map(Task::getPath).toSet(),
        )
        assertFalse(graph.any { it.name == "clean" })
    }

    @Test
    fun `root wiring gates candidate and B clean without making verifier build`() {
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { it.resolve("build.gradle.kts").isFile && it.resolve("codex-agent-runtime-ios").isDirectory }
        val rootBuild = repository.resolve("build.gradle.kts").readText()
        val verifierWiring = rootBuild.substringAfter("ios.named<VerifySwiftPackageABTask>")
            .substringBefore("stageSwiftZip.configure")
        assertTrue("mustRunAfter(\"generateCodexAgentSwiftPackageChecksum\", swiftPackageBPreflight)" in verifierWiring)
        assertFalse("dependsOn(\"generateCodexAgentSwiftPackageChecksum\")" in verifierWiring)
        assertTrue("prepareProtectedCandidate.configure { dependsOn(swiftPackageBPreflight) }" in rootBuild)
        val iosRegistration = repository.resolve(
            "buildSrc/src/main/kotlin/IosAppleReleaseVerificationTasks.kt",
        ).readText()
        assertTrue("clean.configure { dependsOn(preflightCodexAgentSwiftPackageB) }" in iosRegistration)
        assertTrue(
            "packageCodexAgentSwiftPackageBinary.configure { dependsOn(preflightCodexAgentSwiftPackageB) }" in
                iosRegistration,
        )
    }

    private fun withFixture(initialChecksumMatches: Boolean = false, test: (SwiftPackageFixture) -> Unit) {
        val directory = createTempDirectory("swiftpm-ab").toFile()
        try {
            test(SwiftPackageFixture(directory, initialChecksumMatches))
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
}
