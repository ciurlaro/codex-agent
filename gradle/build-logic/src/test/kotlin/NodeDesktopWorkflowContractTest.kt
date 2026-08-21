import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeDesktopWorkflowContractTest {
    private val repository = ReleaseWorkflowFixture.repository
    private val workflows = ReleaseWorkflowFixture.workflows
    private val driver = repository.resolve("ci/run-lane.sh").readText()

    @Test
    fun `portable runners are transported into each selected desktop host`() {
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        assertTrue("codex-agent-ci-portable-${'$'}{{ inputs.validationTree }}" in desktop)
        assertTrue("cp -R build/ci/portable/payload/. ." in desktop)
        listOf(
            "packageJvmRuntimeEvidenceRunner",
            "packageNodeRuntimeEvidenceRunner",
            "packageNodeWasmRuntimeEvidenceRunner",
        ).forEach { assertTrue(it in driver, it) }
        assertFalse("setup-sccache" in desktop)
        assertEquals(1, Regex("(?m)^    strategy:$").findAll(desktop).count())
    }

    @Test
    fun `desktop lanes publish strict receipts and consumers import all classifiers`() {
        val ci = workflows.getValue("ci.yml")
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        assertTrue("uses: ./.github/actions/run-ci-lane" in desktop)
        assertTrue("pattern: codex-agent-ci-desktop-*-" in ci)
        assertTrue("DESKTOP_CLASSIFIERS=" in ci)
        assertTrue("-PcodexAgent.desktopClassifierDirectory" in driver)
        assertFalse("codex-agent-ci-runtime-evidence-" in desktop)
        assertFalse("codex-agent-ci-desktop-classifier-" in desktop)
    }

    @Test
    fun `every direct desktop Gradle invocation receives its evidence target`() {
        val directDesktop = driver.substringAfter("run_desktop() {").substringBefore("\n}\n\ncase ")
        val targetArgument = "args+=(-PcodexAgent.desktopEvidenceTarget=\"${'$'}target\")"

        assertEquals(1, Regex(Regex.escape(targetArgument)).findAll(directDesktop).count())
        assertTrue(directDesktop.indexOf(targetArgument) < directDesktop.indexOf("./gradlew"))
    }

    @Test
    fun `Linux ARM stages and executes one strict bundle`() {
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        val combined = desktop + driver
        assertTrue("runs-on: ubuntu-24.04-arm" in desktop)
        assertTrue(":codex-agent-runtime-desktop:compileDesktopProcessSupervisor" in desktop)
        assertTrue(":codex-agent-runtime-desktop:linkDebugTestLinuxArm64" in desktop)
        assertTrue(":codex-agent-runtime-desktop:packageLinuxArm64AppServer" in desktop)
        assertEquals(1, Regex("stageLinuxArm64RuntimeEvidenceBundle").findAll(combined).count())
        assertEquals(1, Regex("executeLinuxArm64RuntimeEvidenceBundle").findAll(combined).count())
        assertTrue("RUNNER_OS: Linux" in desktop && "RUNNER_ARCH: ARM64" in desktop)
        assertFalse("recordJvmRuntimeLinuxArm64Evidence" in combined)
    }

    @Test
    fun `Linux ARM exact reuse gates every expensive job and preserves miss paths`() {
        val ci = workflows.getValue("ci.yml")
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        val lane = repository.resolve(".github/actions/run-ci-lane/action.yml").readText()
        val stage = repository.resolve("ci/stage.py").readText()
        val identity = desktop.substring(
            desktop.indexOf("  linux-arm64-cross-builder-identity:"),
            desktop.indexOf("  linux-arm64-reuse:"),
        )
        val reuse = desktop.substring(
            desktop.indexOf("  linux-arm64-reuse:"),
            desktop.indexOf("  linux-arm64-supervisor:"),
        )
        val supervisor = desktop.substring(
            desktop.indexOf("  linux-arm64-supervisor:"),
            desktop.indexOf("  linux-arm64-cross-build:"),
        )
        val cross = desktop.substring(
            desktop.indexOf("  linux-arm64-cross-build:"),
            desktop.indexOf("  linux-arm64-runtime:"),
        )
        val runtime = desktop.substring(desktop.indexOf("  linux-arm64-runtime:"))

        assertTrue("needs.plan.outputs.any_desktop == 'true'" in ci)
        assertFalse("needs.plan.outputs.desktop_matrix != '[]'" in ci)
        assertTrue("if: inputs.matrix != '[]'" in desktop)
        assertTrue("runs-on: ubuntu-24.04" in identity)
        assertTrue("runner_identity.py capture" in identity)
        assertFalse("compileDesktopProcessSupervisor" in identity)
        assertFalse("packageLinuxArm64AppServer" in identity)
        assertTrue("needs: linux-arm64-cross-builder-identity" in reuse)
        assertTrue("runs-on: ubuntu-24.04-arm" in reuse)
        assertTrue("uses: ./.github/actions/run-ci-lane" in reuse)
        assertTrue("reuse-only: \"true\"" in reuse)
        assertTrue("production_reused:" in reuse)
        assertTrue("record-producer-role: linux-arm64-supervisor" in reuse)
        assertTrue("needs: linux-arm64-reuse" in supervisor)
        assertFalse("inputs.linuxArm64Build || inputs.linuxArm64Test" in supervisor)
        assertTrue("needs.linux-arm64-reuse.outputs.reused != 'true'" in supervisor)
        assertTrue("needs.linux-arm64-reuse.outputs.production_reused != 'true'" in supervisor)
        assertTrue("runner_identity.py verify-one --role linux-arm64-supervisor" in supervisor)
        assertTrue(supervisor.indexOf("verify-one") < supervisor.indexOf("compileDesktopProcessSupervisor"))
        assertTrue("needs.linux-arm64-reuse.outputs.production_reused == 'true' ||" in cross)
        assertFalse("inputs.linuxArm64 && (inputs.linuxArm64Build || inputs.linuxArm64Test)" in cross)
        assertTrue("needs.linux-arm64-supervisor.result == 'success'" in cross)
        assertTrue("runner_identity.py verify-one --role linux-x64-cross-builder" in cross)
        assertTrue(cross.indexOf("verify-one") < cross.indexOf("packageLinuxArm64AppServer"))
        assertTrue("name: codex-agent-arm64-production-transport-" in lane)
        assertFalse("name: codex-agent-ci-arm64-production-transport-" in lane)
        assertTrue("Restore only the verified production classifier" in cross)
        assertFalse("receipt.py validate" in cross)
        assertFalse("cp -R build/ci/linux-arm64-production/payload/. ." in cross)
        assertTrue("Cross-build one classifier\n        if: needs.linux-arm64-reuse.outputs.production_reused != 'true'" in cross)
        assertTrue("Cross-build the native test executable\n        if: inputs.linuxArm64Test" in cross)
        assertTrue("Stage one strict ARM execution bundle\n        if: inputs.linuxArm64Test" in cross)
        assertTrue("needs: [linux-arm64-reuse, linux-arm64-cross-build]" in runtime)
        assertTrue("needs.linux-arm64-reuse.outputs.reused != 'true'" in runtime)
        assertTrue("needs.linux-arm64-cross-build.result == 'success'" in runtime)
        assertFalse("!inputs.linuxArm64Build && !inputs.linuxArm64Test" in runtime)
        assertTrue("reuse-disabled: \"true\"" in runtime)
        assertTrue("production-prepared: \"true\"" in runtime)
        assertTrue("producer-identities: build/ci/linux-arm64-producer-identities" in runtime)
        assertFalse("- if: inputs.linuxArm64Build || inputs.linuxArm64Test\n        uses: actions/download-artifact" in runtime)
        assertTrue("reuse-only:" in lane)
        assertTrue("steps.production.outputs.reused" in lane)
        assertTrue("producerLinuxArm64Supervisor" in lane)
        assertTrue("producerLinuxX64CrossBuilder" in lane)
        assertTrue("runner_identity.py verify --expected" in lane)
        assertTrue("steps.reuse.outputs.reused == 'true' ||" in lane)
        assertTrue("forced=(--force-production)" in lane)
        assertTrue("reason=prepared" in lane)
        assertTrue("inputs.production-prepared != 'true'" in lane)
        assertTrue("Reject incompatible reuse controls" in lane)
        assertTrue("test \"${'$'}LANE\" = desktop-linux-arm64" in lane)
        assertTrue("CI_LANE_TEST: ${'$'}{{ inputs.force-build == 'true' || steps.selection.outputs.test == 'true' }}" in lane)
        assertTrue("arm-supervisor-identity" in stage)
        assertTrue("x64-cross-builder-identity" in stage)
        assertTrue("*producer_toolchain" in stage)
        assertTrue("--force-production" in stage)
        assertTrue("Linux ARM64 staging requires exact producer identities" in stage)
    }

    @Test
    fun `candidate downloads exact promoted lanes and never invokes desktop evidence`() {
        val candidate = workflows.getValue("release-candidate.yml")
        assertTrue("codex-agent-promoted-validation-${'$'}{{ needs.identity.outputs.candidate_commit }}" in candidate)
        assertTrue("codex-agent-promoted-${'$'}lane-${'$'}CANDIDATE_COMMIT" in candidate)
        assertTrue("java -jar \"${'$'}RELEASE_TOOL\" assemble-promoted-candidate" in candidate)
        assertFalse("./gradlew" in candidate)
        assertFalse("assemblePromotedCandidate" in candidate)
        assertFalse("uses: ./.github/workflows/desktop-runtime-evidence.yml" in candidate)
        assertFalse("recordJvmRuntime" in candidate)
    }
}
