import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.Task
import org.gradle.testfixtures.ProjectBuilder

class ProtectedCandidateLifecycleTest {
    private val commit = "a".repeat(40)
    private val sha = "b".repeat(64)

    @Test
    fun `preflight creates only fresh isolated proof directories`() {
        val fixture = PreflightFixture(commit, sha)
        try {
            prepareProtectedCandidateDirectory(fixture.input.copy(parallel = true))
            assertEquals(
                setOf("artifacts", "evidence", "maven-repository", "clean-consumer", "reports"),
                fixture.candidate.listFiles().orEmpty().map(File::getName).toSet(),
            )
            assertEquals(desktopRuntimeEvidenceTargets.size, fixture.desktop.size)
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
            listOf(
                fixture.input.copy(commit = "main"),
                fixture.input.copy(releaseTag = "v0.2.1"),
                fixture.input.copy(head = "d".repeat(40)),
                fixture.input.copy(trackedStatus = "M build.gradle.kts"),
                fixture.input.copy(trackedStatus = "?? src/new-source.kt"),
                fixture.input.copy(trackedStatus = "?? gradle/release/new-config.json"),
                fixture.input.copy(trackedStatus = "?? native/new.patch"),
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
        assertFalse(gates.any { "recordFirebaseAndroidRuntimeEvidence" in it })
        assertTrue(gates.containsAll(listOf(
            ":codex-agent-runtime-ios:validateImportedCodexAgentIosNativeEvidence",
            ":codex-agent-runtime-ios:prepareCodexAgentIosArm64RustSlice",
            ":codex-agent-runtime-ios:prepareCodexAgentIosSimulatorArm64RustSlice",
            ":stageProtectedIosNativeEvidence",
            ":codex-agent-runtime-ios:iosSimulatorArm64Test",
            ":codex-agent-runtime-ios:verifyCodexAgentSwiftAuthenticationTests",
            ":codex-agent-runtime-ios:recordCodexAgentSwiftPackageProof",
            ":codex-agent-runtime-ios:verifyIosPrivacyManifest",
            ":stageCentralRepository",
            ":verifyImportedJvmRuntimeEvidence",
            ":stageProtectedJvmRuntimeEvidence",
            ":verifyImportedNodeRuntimeEvidence",
            ":stageProtectedNodeRuntimeEvidence",
            ":verifyImportedNodeWasmRuntimeEvidence",
            ":stageProtectedNodeWasmRuntimeEvidence",
            FIREBASE_ANDROID_VERIFY_TASK_PATH,
            ":stageProtectedFirebaseAndroidRuntimeEvidence",
            ":verifyStagedKmpConsumer",
            ":packageCentralBundle",
            ":verifyCandidateManifest",
        )))
        assertFalse(gates.any {
            Regex("(?:node|nodeWasm|jvm)Runtime(?:Macos|Linux|Mingw).+Test").containsMatchIn(it)
        })
        assertFalse(gates.any { gate -> listOf(
            "preparePinned", "prepareCodexIosSource", "testCodexIos", "buildCodexIos",
        ).any(gate::contains) })
    }

    @Test
    fun `verified Apple import replaces semantic reruns but keeps fresh release proofs`() {
        val phases = protectedCandidateGatePaths(reuseVerifiedApple = true)
        assertEquals(8, phases.size)
        assertEquals(
            listOf(":codex-agent-runtime-ios:validateImportedCodexAgentIosVerifiedDistribution"),
            phases[1],
        )
        val gates = phases.flatten()
        assertTrue(gates.containsAll(listOf(
            ":codex-agent-runtime-ios:generateCodexAgentSwiftPackageChecksum",
            ":codex-agent-runtime-ios:verifyCodexAgentRemoteSwiftPackage",
            ":codex-agent-runtime-ios:recordCodexAgentSwiftPackageProof",
            ":stageProtectedSwiftPackage",
            ":stageProtectedSwiftChecksum",
            ":stageProtectedPrivacyAudit",
        )))
        assertFalse(gates.any { gate -> listOf(
            "compileKotlinIosArm64", "iosSimulatorArm64Test", "verifyCodexAgentSwiftPackage",
            "verifyCodexAgentSwiftAuthenticationTests", "packageCodexAgentSwiftPackageBinary",
            "verifyIosDeploymentTargets", "verifyIosLicensePackaging", "verifyIosPrivacyManifest",
            "verifyIosReleaseBudgets",
        ).any(gate::contains) })
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

}
