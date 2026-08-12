import java.io.File
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
import org.gradle.api.Task
import org.gradle.testfixtures.ProjectBuilder

class ProtectedCandidateLifecycleTest {
    private val commit = "a".repeat(40)
    private val sha = "b".repeat(64)

    @Test
    fun `preflight creates only fresh isolated proof directories`() {
        val fixture = PreflightFixture(commit, sha)
        try {
            val invalid = fixture.input.copy(parallel = true)
            assertFailsWith<IllegalStateException> { prepareProtectedCandidateDirectory(invalid) }

            prepareProtectedCandidateDirectory(fixture.input)
            assertEquals(
                setOf("artifacts", "evidence", "maven-repository", "clean-consumer", "reports"),
                fixture.candidate.listFiles().orEmpty().map(File::getName).toSet(),
            )
            assertTrue(fixture.evidence.isFile)
        } finally { fixture.close() }
    }

    @Test
    fun `preflight rejects mutable dirty missing mismatched and nested inputs without cleanup`() {
        val fixture = PreflightFixture(commit, sha)
        try {
            assertEquals(
                listOf("status", "--porcelain=v1", "--untracked-files=normal"),
                protectedCandidateStatusArguments,
            )
            val sentinel = fixture.candidate.resolve("sentinel").apply { parentFile.mkdirs(); writeText("present") }
            val wrongEvidence = fixture.external.resolve("wrong.json").also { writeAndroidEvidence(it, "c".repeat(40), sha) }
            val nestedEvidence = fixture.candidate.resolve(ANDROID_EVIDENCE_FILE)
                .also { it.parentFile.mkdirs(); fixture.evidence.copyTo(it, overwrite = true) }
            listOf(
                fixture.input.copy(commit = "main"),
                fixture.input.copy(releaseTag = "v0.2.1"),
                fixture.input.copy(head = "d".repeat(40)),
                fixture.input.copy(trackedStatus = "M build.gradle.kts"),
                fixture.input.copy(trackedStatus = "?? src/new-source.kt"),
                fixture.input.copy(trackedStatus = "?? release/new-config.json"),
                fixture.input.copy(trackedStatus = "?? native/new.patch"),
                fixture.input.copy(androidEvidence = wrongEvidence),
                fixture.input.copy(androidEvidence = nestedEvidence),
            ).forEach { invalid ->
                assertFailsWith<IllegalStateException> { prepareProtectedCandidateDirectory(invalid) }
                assertTrue(sentinel.isFile)
            }
            assertFailsWith<IllegalStateException> { prepareProtectedCandidateDirectory(fixture.input) }
            assertTrue(sentinel.isFile)
        } finally { fixture.close() }
    }

    @Test
    fun `candidate phases are ordered once while ordinary gates remain isolated`() {
        val project = ProjectBuilder.builder().build()
        val prepare = project.tasks.register("prepare")
        val native = project.tasks.register("native")
        val ios = project.tasks.register("ios")
        val manifest = project.tasks.register("manifest")
        val payload = project.tasks.register("payload")
        val nativeGate = project.tasks.register("nativeGate")
        val sharedGate = project.tasks.register("sharedGate")
        val iosGate = project.tasks.register("iosGate")
        val simulatorGate = project.tasks.register("iosSimulatorArm64Test")
        val manifestGate = project.tasks.register("manifestGate")
        project.tasks.register("verifyPublicationReadiness")
        project.tasks.register("recordCodexAgentSwiftPackageProof")

        wireProtectedCandidatePhase(native, prepare, listOf(nativeGate, sharedGate))
        wireProtectedCandidatePhase(ios, native, listOf(sharedGate, iosGate, simulatorGate))
        wireProtectedCandidatePhase(manifest, ios, listOf(manifestGate))
        wireProtectedCandidatePhase(payload, manifest, emptyList())

        assertTrue(prepare.get() in dependencies(native.get()))
        assertTrue(native.get() in dependencies(ios.get()))
        assertTrue(ios.get() in dependencies(manifest.get()))
        assertFalse(native.get() in dependencies(iosGate.get()))
        assertTrue(native.get() in orderingDependencies(iosGate.get()))
        assertFalse(prepare.get() in transitiveDependencies(iosGate.get()))
        assertTrue(manifest.get() in dependencies(payload.get()))
        val graph = transitiveDependencies(payload.get())
        assertEquals(1, graph.count { it.name == "sharedGate" })
        assertEquals(1, graph.count { it.name == "iosSimulatorArm64Test" })
        assertFalse(graph.any { it.name == "verifyPublicationReadiness" })
        assertFalse(graph.any { it.name == "recordCodexAgentSwiftPackageProof" })
        assertFalse(dependencies(manifestGate.get()).contains(ios.get()))
        assertTrue(orderingDependencies(manifestGate.get()).contains(ios.get()))
    }

    @Test
    fun `protected candidate has eight complete phases and no destructive clean gate`() {
        assertEquals(8, protectedCandidatePhaseGatePaths.size)
        val gates = protectedCandidatePhaseGatePaths.flatten()
        assertFalse(gates.any { it == ":clean" || it.endsWith(":clean") })
        assertTrue(gates.containsAll(listOf(
            ":codex-agent-runtime-ios:testCodexIosBridge",
            ":codex-agent-runtime-ios:testCodexIosDirectToolMode",
            ":codex-agent-runtime-ios:iosSimulatorArm64Test",
            ":codex-agent-runtime-ios:verifyCodexAgentSwiftAuthenticationTests",
            ":codex-agent-runtime-ios:recordCodexAgentSwiftPackageProof",
            ":codex-agent-runtime-ios:verifyIosPrivacyManifest",
            ":stageCentralRepository",
            ":verifyStagedKmpConsumer",
            ":packageCentralBundle",
            ":verifyCandidateManifest",
        )))
    }

    @Test
    fun `full verifier recomputes hashes and rejects tampering`() {
        withPayloadFixture { fixture ->
            verifyProtectedCandidateManifest(fixture.manifest, fixture.inputs)
            assertEquals(
                fixture.swiftPmProof.releaseDigest(),
                fixture.manifest.readReleaseObject().releaseObject("evidence")
                    .releaseObject("swiftPmProof").releaseString("sha256"),
            )
            fixture.centralBundle.appendText("tampered")
            assertFailsWith<IllegalStateException> {
                verifyProtectedCandidateManifest(fixture.manifest, fixture.inputs)
            }
        }
    }

    @Test
    fun `payload staging is flat byte exact binds SwiftPM proof and invokes no shell`() = withPayloadFixture { fixture ->
        fixture.stage()
        val expected = (listOf(fixture.manifest) + fixture.sources).associateBy(File::getName)
        assertEquals(expected.keys, fixture.payload.listFiles().orEmpty().map(File::getName).toSet())
        assertTrue(fixture.payload.listFiles().orEmpty().all(File::isFile))
        expected.forEach { (name, source) ->
            assertTrue(source.readBytes().contentEquals(fixture.payload.resolve(name).readBytes()))
        }
        assertEquals("passed", fixture.verification.readReleaseObject().releaseString("result"))
        assertTrue(StageProtectedCandidatePayloadTask::class.java.declaredConstructors.all { it.parameterCount == 0 })
    }

    @Test
    fun `payload staging rejects duplicate and unsafe basenames before cleanup`() = withPayloadFixture { fixture ->
        val sentinel = fixture.payload.resolve("sentinel").apply { parentFile.mkdirs(); writeText("keep") }
        val duplicate = fixture.root.resolve("duplicate/${fixture.swiftPmProof.name}").apply {
            parentFile.mkdirs(); fixture.swiftPmProof.copyTo(this)
        }
        assertFailsWith<IllegalStateException> { fixture.stage(fixture.sources + duplicate) }
        assertTrue(sentinel.isFile)
        val unsafe = fixture.root.resolve("unsafe\\name.json").apply { writeText("unsafe") }
        assertFailsWith<IllegalStateException> { fixture.stage(fixture.sources + unsafe) }
        assertTrue(sentinel.isFile)
    }

    @Test
    fun `payload staging rejects tampered and missing canonical files`() = withPayloadFixture { fixture ->
        val originalProof = fixture.swiftPmProof.readBytes()
        fixture.swiftPmProof.appendText("tampered")
        assertFailsWith<IllegalStateException> { fixture.stage() }
        fixture.swiftPmProof.writeBytes(originalProof)
        fixture.resources.delete()
        assertFailsWith<IllegalStateException> { fixture.stage() }
    }

    @Test
    fun `required reason review is optional but supplied review remains hash bound`() {
        withPayloadFixture(includeReview = false) { fixture ->
            verifyProtectedCandidateManifest(fixture.manifest, fixture.inputs)
            fixture.stage()
            assertFalse(fixture.manifest.readReleaseObject().releaseObject("policies")
                .containsKey("privacyRequiredReasonReviews"))
        }
        withPayloadFixture { fixture ->
            checkNotNull(fixture.reviews).appendText("tampered")
            assertFailsWith<IllegalStateException> {
                verifyProtectedCandidateManifest(fixture.manifest, fixture.inputs)
            }
        }
    }

    private fun dependencies(task: Task): Set<Task> = task.taskDependencies.getDependencies(task)

    private fun orderingDependencies(task: Task): Set<Task> = task.mustRunAfter.getDependencies(task)

    private fun transitiveDependencies(task: Task): Set<Task> {
        val result = linkedSetOf<Task>()
        fun visit(current: Task) {
            dependencies(current).forEach { if (result.add(it)) visit(it) }
        }
        visit(task)
        return result
    }

    private fun writeZip(file: File) = ZipOutputStream(file.outputStream()).use {
        it.putNextEntry(ZipEntry("member")); it.write("contents".encodeToByteArray()); it.closeEntry()
    }

    private fun writeAndroidEvidence(file: File, candidate: String, hash: String) {
        file.atomicWriteJson(buildAndroidRuntimeEvidence(AndroidRuntimeEvidenceValues(
            candidate, "arm64-v8a", 35, hash, hash, ANDROID_TEST_APPLICATION_ID, ANDROID_TEST_APPLICATION_ID,
            "codex-agent-runtime-android-release.aar", hash, hash, hash,
        )))
    }

    private fun withPayloadFixture(includeReview: Boolean = true, block: (PayloadFixture) -> Unit) {
        val fixture = PayloadFixture(includeReview)
        try { block(fixture) } finally { fixture.close() }
    }

    private inner class PayloadFixture(includeReview: Boolean) : AutoCloseable {
        val root = createTempDirectory("candidate-payload").toFile()
        val swiftZip = root.resolve("CodexAgent-0.2.0.xcframework.zip").also(::writeZip)
        val swiftChecksum = root.resolve("swift.sha256").apply { writeText(swiftZip.releaseDigest()) }
        val centralBundle = root.resolve("central.zip").apply { writeText("central") }
        val maven = root.resolve("maven.json").apply { atomicWriteJson(buildJsonObject {
            put("version", JsonPrimitive("0.2.0")); put("primaryArtifactCount", JsonPrimitive(53))
        }) }
        val central = root.resolve("central.json").apply { atomicWriteJson(buildJsonObject {
            put("belowCentralPortalUploadLimit", JsonPrimitive(true)); put("bundle", centralBundle.releaseRecord())
            put("mavenInventorySha256", JsonPrimitive(maven.releaseDigest()))
        }) }
        val consumer = root.resolve("consumer.json").apply { atomicWriteJson(buildJsonObject {
            put("result", JsonPrimitive("passed")); put("version", JsonPrimitive("0.2.0"))
            put("mavenInventorySha256", JsonPrimitive(maven.releaseDigest()))
        }) }
        val android = root.resolve("android.json").also { writeAndroidEvidence(it, commit, sha) }
        val reviews = root.resolve("reviews.json").takeIf { includeReview }?.apply { writeText("reviews.json") }
        val privacy = root.resolve("privacy.json").apply {
            atomicWriteJson(buildJsonObject {
                put("passed", JsonPrimitive(true))
                reviews?.let { put("reviewSha256", JsonPrimitive(it.releaseDigest())) }
            })
        }
        val resources = root.resolve("resources.json").apply {
            atomicWriteJson(buildJsonObject { put("exitCode", JsonPrimitive(0)) })
        }
        val artifactMetrics = root.resolve("artifact-metrics.json").apply { atomicWriteJson(buildJsonObject {
            put("compressedXcframeworkBytes", JsonPrimitive(1)); put("deviceFrameworkBytes", JsonPrimitive(1))
            put("sampleAppInstallBytes", JsonPrimitive(1))
        }) }
        private fun policy(name: String) = root.resolve(name).apply { writeText(name) }
        val approvals = policy("approvals.json")
        val privacyManifest = policy("PrivacyInfo.xcprivacy")
        val dataFlow = policy("data-flow.json")
        val packageSwift = policy("Package.swift")
        val swiftPmProof = root.resolve("swiftpm-proof.json").also {
            writeTestSwiftPackageProof(it, swiftZip, swiftChecksum, packageSwift, commit, "0.2.0", root)
        }
        val inputs = CandidateInputFiles(
            version = "0.2.0", releaseTag = "v0.2.0", commit = commit,
            swiftZip = swiftZip, swiftChecksum = swiftChecksum, swiftPmProof = swiftPmProof,
            centralBundle = centralBundle, centralInventory = central, mavenInventory = maven,
            kmpConsumer = consumer, androidEvidence = android, privacyAudit = privacy,
            artifactMetrics = artifactMetrics, resourceReports = listOf(resources), approvals = approvals,
            privacyManifest = privacyManifest,
            privacyDataFlowReview = dataFlow, privacyRequiredReasonReviews = reviews, packageSwift = packageSwift,
        )
        val manifest = root.resolve("candidate-manifest.json").apply {
            atomicWriteJson(buildCandidateManifest(inputs))
        }
        val sources = listOf(
            swiftZip, swiftPmProof, centralBundle, central, maven, consumer, android, privacy, artifactMetrics, resources,
            approvals, privacyManifest, dataFlow, packageSwift,
        ) + listOfNotNull(reviews)
        val payload = root.resolve("payload")
        val verification = root.resolve("reports/payload-verification.json")

        fun stage(files: Collection<File> = sources) = stageProtectedCandidatePayload(
            manifest, files, root, payload, verification, "0.2.0", "v0.2.0", commit,
        )

        override fun close() { root.deleteRecursively() }
    }

    private inner class PreflightFixture(candidateCommit: String, hash: String) : AutoCloseable {
        val repository = createTempDirectory("candidate-repository").toFile()
        val external = createTempDirectory("candidate-inputs").toFile()
        val candidate = repository.resolve("build/protected-candidate/$candidateCommit")
        val evidence = external.resolve(ANDROID_EVIDENCE_FILE).also { writeAndroidEvidence(it, candidateCommit, hash) }
        val input = ProtectedCandidatePreflight(
            "0.2.0", "v0.2.0", candidateCommit, candidateCommit, "", false,
            repository, candidate, evidence,
        )

        override fun close() { repository.deleteRecursively(); external.deleteRecursively() }
    }
}
