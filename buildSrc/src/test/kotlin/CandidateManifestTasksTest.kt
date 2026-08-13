import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class CandidateManifestTasksTest {
    @Test
    fun `canonical manifest and transported payload bind every artifact evidence and policy`() = withFixture { fixture ->
        val manifest = buildCandidateManifest(fixture.inputs)
        fixture.manifest.atomicWriteJson(manifest)
        fixture.copyPayloadFiles()

        val result = verifyCandidatePayload(
            fixture.manifest,
            fixture.payload,
            VERSION,
            "v$VERSION",
            COMMIT,
            fixture.policyFiles,
        )

        assertEquals("passed", result.releaseString("result"))
        assertEquals(6, manifest.releaseInt("schemaVersion"))
        assertTrue(manifest.releaseBoolean("protectedCandidate"))
        assertEquals(
            fixture.swiftPmProof.name,
            manifest.releaseObject("evidence").releaseObject("swiftPmProof").releaseString("fileName"),
        )
        assertEquals(
            "releaseTag=v$VERSION\nswiftAsset=${fixture.swiftZip.name}\ncentralBundle=${fixture.centralBundle.name}\n",
            candidateGithubOutputs(result),
        )
    }

    @Test
    fun `payload byte tampering is rejected`() = withFixture { fixture ->
        fixture.manifest.atomicWriteJson(buildCandidateManifest(fixture.inputs))
        fixture.copyPayloadFiles()
        fixture.payload.resolve(fixture.swiftZip.name).appendText("tampered")
        assertFailsWith<IllegalStateException> {
            verifyCandidatePayload(fixture.manifest, fixture.payload, VERSION, "v$VERSION", COMMIT, fixture.policyFiles)
        }
    }

    @Test
    fun `payload task writes exact GitHub outputs after verification`() = withFixture { fixture ->
        fixture.manifest.atomicWriteJson(buildCandidateManifest(fixture.inputs))
        fixture.copyPayloadFiles()
        val githubOutput = fixture.root.resolve("github-output.txt")
        val task = ProjectBuilder.builder().withProjectDir(fixture.root).build().tasks.create(
            "verifyCandidatePayloadFixture",
            VerifyCandidatePayloadTask::class.java,
        ).apply {
            manifestFile.set(fixture.manifest)
            payloadDirectory.set(fixture.payload)
            expectedVersion.set(VERSION)
            expectedTag.set("v$VERSION")
            expectedCommit.set(COMMIT)
            approvalsFile.set(fixture.approvals)
            privacyManifest.set(fixture.privacyManifest)
            privacyDataFlowReview.set(fixture.privacyReview)
            privacyReviews.set(fixture.requiredReasons)
            packageSwift.set(fixture.packageSwift)
            desktopDistributionManifest.set(fixture.desktopManifest)
            desktopBundledLicense.set(fixture.desktopLicense)
            desktopBundledNotice.set(fixture.desktopNotice)
            outputFile.set(fixture.root.resolve("payload-result.json"))
            githubOutputFile.set(githubOutput)
        }

        task.verify()

        assertEquals(
            "releaseTag=v$VERSION\nswiftAsset=${fixture.swiftZip.name}\ncentralBundle=${fixture.centralBundle.name}\n",
            githubOutput.readText(),
        )
    }

    @Test
    fun `repository policy tampering is rejected`() = withFixture { fixture ->
        fixture.manifest.atomicWriteJson(buildCandidateManifest(fixture.inputs))
        fixture.copyPayloadFiles()
        fixture.approvals.appendText(" ")
        assertFailsWith<IllegalStateException> {
            verifyCandidatePayload(fixture.manifest, fixture.payload, VERSION, "v$VERSION", COMMIT, fixture.policyFiles)
        }
    }

    @Test
    fun `missing or tampered transported policy is rejected`() = withFixture { fixture ->
        fixture.manifest.atomicWriteJson(buildCandidateManifest(fixture.inputs))
        fixture.copyPayloadFiles()
        val transported = fixture.payload.resolve(fixture.approvals.name)
        transported.delete()
        assertFailsWith<IllegalStateException> {
            verifyCandidatePayload(fixture.manifest, fixture.payload, VERSION, "v$VERSION", COMMIT, fixture.policyFiles)
        }
        fixture.approvals.copyTo(transported)
        transported.appendText("tampered")
        assertFailsWith<IllegalStateException> {
            verifyCandidatePayload(fixture.manifest, fixture.payload, VERSION, "v$VERSION", COMMIT, fixture.policyFiles)
        }
    }

    @Test
    fun `additional payload file is rejected`() = withFixture { fixture ->
        fixture.manifest.atomicWriteJson(buildCandidateManifest(fixture.inputs))
        fixture.copyPayloadFiles()
        fixture.payload.resolve("unexpected.txt").writeText("unexpected")
        assertFailsWith<IllegalStateException> {
            verifyCandidatePayload(fixture.manifest, fixture.payload, VERSION, "v$VERSION", COMMIT, fixture.policyFiles)
        }
    }

    @Test
    fun `missing SwiftPM candidate proof fails generation`() = withFixture { fixture ->
        fixture.swiftPmProof.delete()
        val failure = assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        assertTrue(failure.message.orEmpty().contains("SwiftPM candidate proof"))
    }

    @Test
    fun `missing SwiftPM candidate proof from transported payload is rejected`() = withFixture { fixture ->
        fixture.manifest.atomicWriteJson(buildCandidateManifest(fixture.inputs))
        fixture.copyPayloadFiles()
        fixture.payload.resolve(fixture.swiftPmProof.name).delete()
        assertFailsWith<IllegalStateException> {
            verifyCandidatePayload(fixture.manifest, fixture.payload, VERSION, "v$VERSION", COMMIT, fixture.policyFiles)
        }
    }

    @Test
    fun `tampered SwiftPM candidate proof is rejected`() = withFixture { fixture ->
        fixture.manifest.atomicWriteJson(buildCandidateManifest(fixture.inputs))
        fixture.copyPayloadFiles()
        fixture.payload.resolve(fixture.swiftPmProof.name).appendText("tampered")
        assertFailsWith<IllegalStateException> {
            verifyCandidatePayload(fixture.manifest, fixture.payload, VERSION, "v$VERSION", COMMIT, fixture.policyFiles)
        }
    }

    @Test
    fun `SwiftPM proof identity mismatch fails generation`() = withFixture { fixture ->
        fixture.swiftPmProof.writeText(fixture.swiftPmProof.readText().replace(COMMIT, "f".repeat(40)))
        val failure = assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        assertTrue(failure.message.orEmpty().contains("identity mismatch"))
    }

    @Test
    fun `missing artifact metrics fails generation`() = withFixture { fixture ->
        fixture.artifactMetrics.delete()
        val failure = assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        assertTrue(failure.message.orEmpty().contains("Artifact metrics"))
    }

    @Test
    fun `missing tampered and unsupported iOS native evidence fails generation`() = withFixture { fixture ->
        val original = fixture.iosNative.readBytes()
        fixture.iosNative.delete()
        assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        fixture.iosNative.writeBytes(original)
        fixture.iosNative.writeText(fixture.iosNative.readText().replace(COMMIT, "f".repeat(40)))
        assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        fixture.iosNative.writeBytes(original)
        fixture.iosNative.writeText(fixture.iosNative.readText().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"))
        assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
    }

    @Test
    fun `tampered artifact metrics in payload is rejected`() = withFixture { fixture ->
        fixture.manifest.atomicWriteJson(buildCandidateManifest(fixture.inputs))
        fixture.copyPayloadFiles()
        fixture.payload.resolve(fixture.artifactMetrics.name).appendText("tampered")
        assertFailsWith<IllegalStateException> {
            verifyCandidatePayload(fixture.manifest, fixture.payload, VERSION, "v$VERSION", COMMIT, fixture.policyFiles)
        }
    }

    @Test
    fun `Swift checksum mismatch fails generation`() = withFixture { fixture ->
        fixture.swiftChecksum.writeText("0".repeat(64))
        val failure = assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        assertTrue(failure.message.orEmpty().contains("SwiftPM checksum"))
    }

    @Test
    fun `desktop evidence must match the exact runner target and classifier archive`() = withFixture { fixture ->
        fixture.desktop.first().writeText(fixture.desktop.first().readText().replace("\"ARM64\"", "\"X64\""))
        val failure = assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        assertTrue(failure.message.orEmpty().contains("Desktop runtime evidence is invalid"))
    }

    @Test
    fun `required reason review is omitted when no review was supplied`() = withFixture { fixture ->
        fixture.removeRequiredReasonReview()
        val manifest = buildCandidateManifest(fixture.inputs)
        assertFalse("privacyRequiredReasonReviews" in manifest.releaseObject("policies"))
    }

    @Test
    fun `privacy audit must bind the exact supplied required reason review`() = withFixture { fixture ->
        fixture.requiredReasons.appendText("tampered")
        val failure = assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        assertTrue(failure.message.orEmpty().contains("does not bind"))
    }

    private fun withFixture(block: (CandidateManifestFixture) -> Unit) {
        val directory = createTempDirectory("candidate-manifest").toFile()
        try { block(CandidateManifestFixture(directory, VERSION, COMMIT)) } finally { directory.deleteRecursively() }
    }

    companion object {
        private const val VERSION = "0.2.0"
        private const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
