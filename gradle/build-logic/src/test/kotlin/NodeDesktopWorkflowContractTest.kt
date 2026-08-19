import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeDesktopWorkflowContractTest {
    private val workflows = ReleaseWorkflowFixture.workflows

    @Test
    fun `main packages portable runners once before the four-host workflow`() {
        val ci = workflows.getValue("ci.yml")
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        val combined = ci + desktop
        listOf(
            "packageJvmRuntimeEvidenceRunner",
            "packageNodeRuntimeEvidenceRunner",
            "packageNodeWasmRuntimeEvidenceRunner",
        ).forEach { task ->
            assertEquals(1, Regex(task).findAll(combined).count(), task)
            assertFalse(task in desktop)
        }
        assertTrue(":codex-agent-runtime-android:testDebugUnitTest" in ci)
        assertFalse(":codex-agent-runtime-android:testReleaseUnitTest" in ci)
        assertTrue("needs: [workflow-lint, android-jvm]" in ci)
        assertTrue("codex-agent-ci-portable-runtime-artifacts-${'$'}{{ github.sha }}" in ci)
        assertTrue("path: build/portable-runtime-artifacts" in desktop)
        assertFalse("setup-sccache" in desktop)
        assertEquals(1, Regex("(?m)^    strategy:$").findAll(desktop).count())
        listOf("macosArm64", "macosX64", "linuxX64", "mingwX64").forEach { target ->
            assertTrue("- target: $target" in desktop)
        }
    }

    @Test
    fun `each desktop host transports four evidence families and its exact classifier`() {
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        val runtime = desktop.substringAfter("\n  runtime:").substringBefore("\n  linux-arm64-supervisor:")
        listOf(
            "desktop-runtime-evidence/desktop-runtime-${'$'}{{ matrix.target }}.json",
            "jvm-runtime-evidence/jvm-runtime-${'$'}{{ matrix.target }}.json",
            "node-runtime-evidence/node-runtime-${'$'}{{ matrix.target }}.json",
            "node-runtime-evidence/node-wasm-runtime-${'$'}{{ matrix.target }}.json",
            "codex-agent-ci-desktop-classifier-${'$'}{{ matrix.target }}",
        ).forEach { assertTrue(it in runtime, it) }
        val arm = desktop.substringAfter("\n  linux-arm64-cross-build:")
        assertTrue("codex-agent-ci-desktop-classifier-linuxArm64" in arm)
        assertTrue("codex-agent-ci-runtime-evidence-linuxArm64" in arm)
    }

    @Test
    fun `Linux ARM stages and extracts one strict bundle`() {
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        val supervisor = desktop.substringAfter("\n  linux-arm64-supervisor:")
            .substringBefore("\n  linux-arm64-cross-build:")
        val cross = desktop.substringAfter("\n  linux-arm64-cross-build:")
            .substringBefore("\n  linux-arm64-runtime:")
        val runtime = desktop.substringAfter("\n  linux-arm64-runtime:")
        assertTrue("runs-on: ubuntu-24.04-arm" in supervisor)
        assertTrue(":codex-agent-runtime-desktop:compileDesktopProcessSupervisor" in supervisor)
        assertTrue("path: codex-agent-runtime-desktop/build/supervisor" in supervisor)
        assertFalse("cc -std=c11" in supervisor)
        assertTrue(":codex-agent-runtime-desktop:linkDebugTestLinuxArm64" in cross)
        assertTrue(":codex-agent-runtime-desktop:packageLinuxArm64AppServer" in cross)
        assertEquals(1, Regex("stageLinuxArm64RuntimeEvidenceBundle").findAll(desktop).count())
        assertEquals(1, Regex("executeLinuxArm64RuntimeEvidenceBundle").findAll(desktop).count())
        assertEquals(2, Regex("linuxArm64RuntimeEvidenceBundle").findAll(desktop).count())
        assertFalse("stageLinuxArm64DesktopEvidenceBundle" in desktop)
        assertTrue("RUNNER_OS: Linux" in runtime && "RUNNER_ARCH: ARM64" in runtime)
        assertEquals(1, Regex("./gradlew -p gradle/build-logic").findAll(runtime).count())
    }

    @Test
    fun `candidate consumes exact run-bound runtime imports`() {
        val candidate = workflows.getValue("release-candidate.yml")
        assertTrue("pattern: codex-agent-ci-runtime-evidence-*-${'$'}{{ needs.portable-gates.outputs.candidate_commit }}" in candidate)
        assertTrue("pattern: codex-agent-ci-desktop-classifier-*-${'$'}{{ needs.portable-gates.outputs.candidate_commit }}" in candidate)
        assertTrue("merge-multiple: true" in candidate)
        listOf(
            "portableRuntimeArtifactsDirectory", "desktopClassifierDirectory",
            "jvmEvidenceDirectory", "nodeWasmEvidenceDirectory",
        ).forEach { assertTrue("-PcodexAgent.$it=" in candidate, it) }
        assertFalse("desktopSupervisorDirectory" in candidate)
        assertFalse("uses: ./.github/workflows/desktop-runtime-evidence.yml" in candidate)
        assertFalse("windows-supervisor" in candidate)
    }
}
