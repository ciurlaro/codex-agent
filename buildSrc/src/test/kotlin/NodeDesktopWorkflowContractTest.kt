import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeDesktopWorkflowContractTest {
    private val workflows = ReleaseWorkflowFixture.workflows

    @Test
    fun `desktop evidence uses bash consistently on every hosted runner`() {
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        val smoke = desktop.substringAfter("Run and record the official app-server lifecycle smoke")
            .substringBefore("- uses: actions/upload-artifact")
        assertTrue("shell: bash" in smoke)
        assertTrue("-PcodexAgent.candidateCommit=${'$'}{{ inputs.candidateCommit }}" in smoke)
    }

    @Test
    fun `Node evidence reuses the desktop host matrix and one complete runner ZIP`() {
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        val supervisor = desktop.substringAfter("\n  windows-node-supervisor:")
            .substringBefore("\n  node-evidence-runner:")
        val runtime = desktop.substringAfter("\n  runtime:")
            .substringBefore("\n  linux-arm64-cross-build:")
        val producer = desktop.substringAfter("\n  node-evidence-runner:").substringBefore("\n  runtime:")
        assertTrue(desktop.indexOf("\n  windows-node-supervisor:") <
            desktop.indexOf("\n  node-evidence-runner:"))
        assertTrue("runs-on: windows-2025" in supervisor)
        assertTrue(":codex-agent-runtime-node:verifyWindowsNodeSupervisorPackage" in supervisor)
        assertTrue("name: codex-agent-node-windows-supervisor-identity" in supervisor)
        assertTrue("name: codex-agent-node-windows-supervisor-package" in supervisor)
        assertEquals(1, Regex("packageNodeRuntimeEvidenceRunner").findAll(desktop).count())
        assertTrue("needs: windows-node-supervisor" in producer)
        assertTrue("name: codex-agent-node-windows-supervisor-identity" in producer)
        assertTrue("-PcodexAgent.windowsNodeSupervisorIdentityFile=" in producer)
        assertTrue("node-version: \"24.18.0\"" in producer)
        assertTrue("codex-agent-node-runtime-evidence-runner.zip" in producer)
        assertTrue("compression-level: 0" in producer)
        assertTrue("needs: node-evidence-runner" in runtime)
        assertTrue("node-version: \"24.18.0\"" in runtime)
        assertTrue(":codex-agent-runtime-node:${'$'}{{ matrix.nodeTask }}" in runtime)
        assertTrue("-PcodexAgent.nodeRuntimeEvidenceRunnerArchive=" in runtime)
        assertTrue("-PcodexAgent.nodeClassifierArchive=" in runtime)
        assertTrue("codex-agent-node-runtime-evidence-runner.zip" in runtime)
        assertTrue("name: codex-agent-node-runtime-evidence-${'$'}{{ matrix.target }}" in runtime)
        assertTrue("if: matrix.target == 'mingwX64'" in runtime)
        assertTrue("unzip -q" in runtime)
        assertTrue("-PcodexAgent.nodeWindowsSupervisorExecutable=" in runtime)
        assertTrue("-PcodexAgent.windowsNodeSupervisorIdentityFile=" in runtime)
        assertTrue("-PcodexAgent.windowsNodeSupervisorPackage=" in runtime)
        assertEquals(1, Regex("verifyWindowsNodeSupervisorPackage").findAll(desktop).count())
        assertEquals(1, Regex("""(?m)^\s+strategy:$""").findAll(desktop).count())
        assertFalse("node-runtime-evidence-runner.js" in desktop)
    }

    @Test
    fun `Linux ARM64 evidence is cross-built then executed without root KMP configuration`() {
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        val crossBuild = desktop.substringAfter("\n  linux-arm64-cross-build:")
            .substringBefore("\n  linux-arm64-runtime:")
        val runtime = desktop.substringAfter("\n  linux-arm64-runtime:")
        assertTrue("runs-on: ubuntu-24.04" in crossBuild)
        assertTrue(":codex-agent-runtime-desktop:linkDebugTestLinuxArm64" in crossBuild)
        assertTrue(":codex-agent-runtime-desktop:packageLinuxArm64AppServer" in crossBuild)
        assertTrue("./gradlew -p buildSrc stageLinuxArm64DesktopEvidenceBundle" in crossBuild)
        assertTrue("-PcodexAgent.linuxArm64DistributionsDirectory=" in crossBuild)
        assertFalse("-PcodexAgent.linuxArm64ClassifierArchive=" in crossBuild)
        assertFalse("0.2.0" in crossBuild)
        assertTrue("name: codex-agent-linux-arm64-execution-bundle" in crossBuild)
        assertTrue("./gradlew -p buildSrc stageLinuxArm64NodeRuntimeEvidenceBundle" in crossBuild)
        assertTrue("-PcodexAgent.nodeRuntimeEvidenceRunnerArchive=" in crossBuild)
        assertTrue("name: codex-agent-linux-arm64-node-execution-bundle" in crossBuild)

        assertTrue("needs: linux-arm64-cross-build" in runtime)
        assertTrue("runs-on: ubuntu-24.04-arm" in runtime)
        assertTrue("RUNNER_OS: Linux" in runtime && "RUNNER_ARCH: ARM64" in runtime)
        assertTrue("name: codex-agent-linux-arm64-execution-bundle" in runtime)
        assertTrue("./gradlew -p buildSrc executeLinuxArm64DesktopEvidenceBundle" in runtime)
        assertFalse(Regex("""\./gradlew\s+:(?!test\b)""").containsMatchIn(runtime))
        assertTrue("name: codex-agent-desktop-runtime-evidence-linuxArm64" in runtime)
        assertTrue("node-version: \"24.18.0\"" in runtime)
        assertTrue("./gradlew -p buildSrc executeLinuxArm64NodeRuntimeEvidenceBundle" in runtime)
        assertTrue("name: codex-agent-node-runtime-evidence-linuxArm64" in runtime)
        val finalArtifact = runtime.substringAfter("name: codex-agent-desktop-runtime-evidence-linuxArm64")
        assertTrue("path: build/reports/desktop-runtime-evidence/desktop-runtime-linuxArm64.json" in finalArtifact)
        assertFalse(".xml" in finalArtifact)
    }

    @Test
    fun `candidate transports exact Node and versionless supervisor evidence`() {
        val candidate = workflows.getValue("release-candidate.yml")
        val apple = candidate.substringAfter("\n  apple-candidate:")
        assertTrue("pattern: codex-agent-node-runtime-evidence-*" in apple)
        assertTrue("merge-multiple: true" in apple)
        assertTrue("name: codex-agent-node-evidence-runner" in apple)
        assertTrue("path: codex-agent-runtime-node/build/distributions" in apple)
        assertTrue("name: codex-agent-node-windows-supervisor-identity" in apple)
        assertTrue("name: codex-agent-node-windows-supervisor-package" in apple)
        assertTrue("packages=(\"\$PACKAGE_DIRECTORY/\"*-windows-supervisor.zip)" in apple)
        assertTrue("test \"\${#packages[@]}\" -eq 1" in apple)
        assertFalse("codex-agent-runtime-node-0.2.0-windows-supervisor.zip" in candidate)
    }
}
