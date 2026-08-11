import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
        assertEquals(2, manifest.releaseInt("schemaVersion"))
        assertTrue(manifest.releaseBoolean("protectedCandidate"))
        assertEquals(
            fixture.swiftPmAbProof.name,
            manifest.releaseObject("evidence").releaseObject("swiftPmAbProof").releaseString("fileName"),
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
    fun `missing SwiftPM AB proof fails generation`() = withFixture { fixture ->
        fixture.swiftPmAbProof.delete()
        val failure = assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        assertTrue(failure.message.orEmpty().contains("SwiftPM A/B proof"))
    }

    @Test
    fun `missing SwiftPM AB proof from transported payload is rejected`() = withFixture { fixture ->
        fixture.manifest.atomicWriteJson(buildCandidateManifest(fixture.inputs))
        fixture.copyPayloadFiles()
        fixture.payload.resolve(fixture.swiftPmAbProof.name).delete()
        assertFailsWith<IllegalStateException> {
            verifyCandidatePayload(fixture.manifest, fixture.payload, VERSION, "v$VERSION", COMMIT, fixture.policyFiles)
        }
    }

    @Test
    fun `tampered SwiftPM AB proof is rejected`() = withFixture { fixture ->
        fixture.manifest.atomicWriteJson(buildCandidateManifest(fixture.inputs))
        fixture.copyPayloadFiles()
        fixture.payload.resolve(fixture.swiftPmAbProof.name).appendText("tampered")
        assertFailsWith<IllegalStateException> {
            verifyCandidatePayload(fixture.manifest, fixture.payload, VERSION, "v$VERSION", COMMIT, fixture.policyFiles)
        }
    }

    @Test
    fun `missing artifact metrics fails generation`() = withFixture { fixture ->
        fixture.artifactMetrics.delete()
        val failure = assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        assertTrue(failure.message.orEmpty().contains("Artifact metrics"))
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
    fun `missing generated Android evidence fails generation`() = withFixture { fixture ->
        fixture.android.delete()
        val failure = assertFailsWith<IllegalStateException> { buildCandidateManifest(fixture.inputs) }
        assertTrue(failure.message.orEmpty().contains("Android runtime evidence is required"))
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

    private fun withFixture(block: (Fixture) -> Unit) {
        val directory = createTempDirectory("candidate-manifest").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }

    private class Fixture(val root: File) {
        val swiftZip = root.resolve("CodexAgent-0.2.0.xcframework.zip").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("CodexAgent.xcframework/file").apply {
                    setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
                })
                zip.write("swift".encodeToByteArray())
                zip.closeEntry()
            }
        }
        val swiftChecksum = root.resolve("swift.sha256").apply { writeText(swiftZip.releaseDigest()) }
        val swiftPmAbProof = root.resolve("swiftpm-ab-proof.json").apply { atomicWriteJson(buildJsonObject {
            put("result", JsonPrimitive("passed"))
        }) }
        val centralBundle = root.resolve("codex-agent-0.2.0-central.zip").apply { writeText("central") }
        val mavenInventory = root.resolve("maven-inventory.json").apply { atomicWriteJson(buildJsonObject {
            put("version", JsonPrimitive(VERSION)); put("primaryArtifactCount", JsonPrimitive(53))
        }) }
        val centralInventory = root.resolve("central-bundle.json").apply { atomicWriteJson(buildJsonObject {
            put("belowCentralPortalUploadLimit", JsonPrimitive(true))
            put("mavenInventorySha256", JsonPrimitive(mavenInventory.releaseDigest()))
            put("bundle", centralBundle.releaseRecord())
        }) }
        val consumer = root.resolve("kmp-consumer.json").apply { atomicWriteJson(buildJsonObject {
            put("result", JsonPrimitive("passed")); put("version", JsonPrimitive(VERSION))
            put("mavenInventorySha256", JsonPrimitive(mavenInventory.releaseDigest()))
        }) }
        val android = root.resolve("android-evidence.json").apply {
            atomicWriteJson(buildAndroidRuntimeEvidence(AndroidRuntimeEvidenceValues(
                COMMIT,
                "arm64-v8a",
                35,
                "a".repeat(64),
                "b".repeat(64),
                ANDROID_TEST_APPLICATION_ID,
                ANDROID_TEST_APPLICATION_ID,
                "codex-agent-runtime-android-release.aar",
                "c".repeat(64),
                "d".repeat(64),
                "d".repeat(64),
            )))
        }
        val resources = root.resolve("resources.json").apply { atomicWriteJson(buildJsonObject {
            put("exitCode", JsonPrimitive(0))
        }) }
        val artifactMetrics = root.resolve("artifact-metrics.json").apply { atomicWriteJson(buildJsonObject {
            put("compressedXcframeworkBytes", JsonPrimitive(1))
            put("deviceFrameworkBytes", JsonPrimitive(1))
            put("sampleAppInstallBytes", JsonPrimitive(1))
        }) }
        val approvals = root.resolve("publication-approvals.json").apply { writeText("{}") }
        val privacyManifest = root.resolve("PrivacyInfo.xcprivacy").apply { writeText("manifest") }
        val privacyReview = root.resolve("privacy-data-flow-review.json").apply { writeText("{}") }
        val requiredReasons = root.resolve("privacy-required-reason-reviews.json").apply { writeText("{}") }
        val privacyAudit = root.resolve("privacy-audit.json").apply { writePrivacyAudit(requiredReasons.releaseDigest()) }
        val packageSwift = root.resolve("Package.swift").apply { writeText("package") }
        val manifest = root.resolve("candidate-manifest.json")
        val payload = root.resolve("payload").apply { mkdirs() }
        val inputs get() = CandidateInputFiles(
            VERSION, "v$VERSION", COMMIT, swiftZip, swiftChecksum, swiftPmAbProof, centralBundle, centralInventory,
            mavenInventory, consumer, android, privacyAudit, artifactMetrics, listOf(resources), approvals, privacyManifest,
            privacyReview, requiredReasons.takeIf(File::isFile), packageSwift,
        )
        val policyFiles get() = buildMap {
            put("approvals", approvals)
            put("privacyManifest", privacyManifest)
            put("privacyDataFlowReview", privacyReview)
            requiredReasons.takeIf(File::isFile)?.let { put("privacyRequiredReasonReviews", it) }
            put("packageSwift", packageSwift)
        }
        fun removeRequiredReasonReview() {
            requiredReasons.delete()
            privacyAudit.writePrivacyAudit(null)
        }
        private fun File.writePrivacyAudit(reviewHash: String?) = atomicWriteJson(buildJsonObject {
            put("passed", JsonPrimitive(true))
            reviewHash?.let { put("reviewSha256", JsonPrimitive(it)) }
        })
        fun copyPayloadFiles() {
            listOf(
                swiftZip, swiftPmAbProof, centralBundle, centralInventory, mavenInventory, consumer, android,
                privacyAudit, artifactMetrics, resources,
            )
                .plus(policyFiles.values)
                .forEach { it.copyTo(payload.resolve(it.name), overwrite = true) }
        }
    }

    companion object {
        private const val VERSION = "0.2.0"
        private const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
