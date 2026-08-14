import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeDesktopWorkflowContractTest {
    private val workflows = ReleaseWorkflowFixture.workflows

    @Test
    fun `one portable build feeds one four-host matrix for every standalone runtime`() {
        val workflow = workflows.getValue("desktop-runtime-evidence.yml")
        val producer = workflow.substringAfter("\n  portable-runners:").substringBefore("\n  runtime:")
        val runtime = workflow.substringAfter("\n  runtime:").substringBefore("\n  linux-arm64-supervisor:")
        assertEquals(1, Regex("packageJvmRuntimeEvidenceRunner").findAll(workflow).count())
        assertEquals(1, Regex("packageNodeRuntimeEvidenceRunner").findAll(workflow).count())
        assertEquals(1, Regex("packageNodeWasmRuntimeEvidenceRunner").findAll(workflow).count())
        assertTrue("codex-agent-portable-runtime-evidence-runners" in producer)
        assertEquals(1, Regex("(?m)^    strategy:$").findAll(workflow).count())
        listOf("macosArm64", "macosX64", "linuxX64", "mingwX64").forEach { target ->
            assertTrue("- target: $target" in runtime)
        }
        listOf("matrix.nativeTask", "matrix.jvmTask", "matrix.nodeTask", "matrix.wasmTask")
            .forEach { assertTrue(it in runtime) }
        listOf(
            "jvmClassifierArchive", "jvmRuntimeEvidenceRunner", "nodeClassifierArchive",
            "nodeRuntimeEvidenceRunnerArchive", "nodeWasmRuntimeEvidenceRunnerArchive",
            "desktopDistributionManifest",
        ).forEach { assertTrue("-PcodexAgent.$it=" in runtime, it) }
    }

    @Test
    fun `each target transports four evidence families and its embedded supervisor`() {
        val workflow = workflows.getValue("desktop-runtime-evidence.yml")
        val runtime = workflow.substringAfter("\n  runtime:").substringBefore("\n  linux-arm64-supervisor:")
        listOf(
            "desktop-runtime-evidence/desktop-runtime-${'$'}{{ matrix.target }}.json",
            "jvm-runtime-evidence/jvm-runtime-${'$'}{{ matrix.target }}.json",
            "node-runtime-evidence/node-runtime-${'$'}{{ matrix.target }}.json",
            "node-runtime-evidence/node-wasm-runtime-${'$'}{{ matrix.target }}.json",
            "build/desktop-supervisors/${'$'}{{ matrix.target }}",
        ).forEach { assertTrue(it in runtime, it) }
        assertTrue("name: codex-agent-runtime-evidence-${'$'}{{ matrix.target }}" in runtime)
        val arm = workflow.substringAfter("\n  linux-arm64-runtime:")
        listOf(
            "desktop-runtime-linuxArm64.json", "jvm-runtime-linuxArm64.json",
            "node-runtime-linuxArm64.json", "node-wasm-runtime-linuxArm64.json",
            "build/desktop-supervisors/linuxArm64",
        ).forEach { assertTrue(it in arm, it) }
        assertTrue("name: codex-agent-runtime-evidence-linuxArm64" in arm)
    }

    @Test
    fun `Linux ARM supervisor and execution avoid root KMP on the ARM runner`() {
        val workflow = workflows.getValue("desktop-runtime-evidence.yml")
        val supervisor = workflow.substringAfter("\n  linux-arm64-supervisor:")
            .substringBefore("\n  linux-arm64-cross-build:")
        val cross = workflow.substringAfter("\n  linux-arm64-cross-build:")
            .substringBefore("\n  linux-arm64-runtime:")
        val runtime = workflow.substringAfter("\n  linux-arm64-runtime:")
        assertTrue("runs-on: ubuntu-24.04-arm" in supervisor)
        assertTrue("cc -std=c11" in supervisor)
        assertFalse("./gradlew" in supervisor)
        assertTrue(":codex-agent-runtime-desktop:linkDebugTestLinuxArm64" in cross)
        assertTrue(":codex-agent-runtime-desktop:packageLinuxArm64AppServer" in cross)
        listOf(
            "stageLinuxArm64DesktopEvidenceBundle", "stageLinuxArm64JvmEvidenceBundle",
            "stageLinuxArm64NodeRuntimeEvidenceBundle",
        ).forEach { assertTrue(it in cross) }
        listOf(
            "executeLinuxArm64DesktopEvidenceBundle", "executeLinuxArm64JvmEvidenceBundle",
            "executeLinuxArm64NodeRuntimeEvidenceBundle",
        ).forEach { assertTrue(it in runtime) }
        assertTrue("RUNNER_OS: Linux" in runtime && "RUNNER_ARCH: ARM64" in runtime)
        assertEquals(1, Regex("./gradlew -p buildSrc").findAll(runtime).count())
        assertFalse(Regex("""\./gradlew\s+:(?!test\b)""").containsMatchIn(runtime))
    }

    @Test
    fun `candidate consumes the merged evidence tree and exact supervisor tree`() {
        val candidate = workflows.getValue("release-candidate.yml")
        assertTrue("pattern: codex-agent-runtime-evidence-*" in candidate)
        assertTrue("merge-multiple: true" in candidate)
        assertTrue("-PcodexAgent.desktopSupervisorDirectory=\"${'$'}RUNTIME/build/desktop-supervisors\"" in candidate)
        assertTrue("-PcodexAgent.jvmEvidenceDirectory=" in candidate)
        assertTrue("-PcodexAgent.nodeWasmEvidenceDirectory=" in candidate)
        assertFalse("windows-supervisor" in candidate)
    }
}
