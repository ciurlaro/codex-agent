import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseWorkflowContractTest {
    private val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { it.resolve(".github/workflows/release-candidate.yml").isFile }
    private val workflows = listOf(
        "ci.yml",
        "android-runtime-evidence.yml",
        "desktop-runtime-evidence.yml",
        "release-candidate.yml",
        "publish.yml",
    )
        .associateWith { repository.resolve(".github/workflows/$it").readText() }

    @Test
    fun `workflows use only live direct Gradle entry points`() {
        val calls = workflows.values.flatMap { workflow ->
            Regex("""\./gradlew(?:\s+-p\s+buildSrc)?\s+([:\w-]+)""")
                .findAll(workflow)
                .map { it.groupValues[1] }
                .toList()
        }
        val allowed = setOf(
            "test",
            "verifyRepository",
            "verifyIosRuntime",
            "verifyReleaseMetadata",
            "verifyPublicationReadiness",
            "verifyCandidatePayload",
            "verifyPublicSwiftResolution",
            "prepareCentralDeployment",
            "awaitCentralValidation",
            "releaseCentralDeployment",
            "assembleProtectedCandidate",
            "stageLinuxArm64DesktopEvidenceBundle",
            "executeLinuxArm64DesktopEvidenceBundle",
            ":codex-agent-runtime-ios:verifyAppleToolchain",
            ":codex-agent-runtime-ios:exportCodexAgentIosArm64RustSlice",
            ":codex-agent-runtime-ios:exportCodexAgentIosSimulatorArm64RustSlice",
            ":codex-agent-runtime-android:recordAndroidRuntimeEvidence",
            ":codex-agent-runtime-desktop:",
            ":codex-agent-runtime-desktop:linkDebugTestLinuxArm64",
        )
        assertTrue(calls.isNotEmpty())
        assertEquals(emptySet(), calls.toSet() - allowed)
        assertEquals(1, calls.count { it == "assembleProtectedCandidate" })
        assertTrue("--no-parallel" in workflows.getValue("release-candidate.yml"))
    }

    @Test
    fun `candidate consumes one immutable commit and desktop evidence before one assembly`() {
        val candidate = workflows.getValue("release-candidate.yml")
        assertTrue("name: codex-agent-protected-candidate" in candidate)
        assertTrue("candidate_commit" in candidate)
        assertTrue("recordAndroidRuntimeEvidence" in workflows.getValue("android-runtime-evidence.yml"))
        assertFalse("android-runtime-evidence" in candidate)
        assertFalse("androidEvidenceFile" in candidate)
        assertFalse("swiftpm-baseline" in candidate)
        assertFalse("swiftPmBaselineProof" in candidate)
        assertFalse("commit_a" in candidate || "commit_b" in candidate)
        assertTrue("-PcodexAgent.iosNativeEvidenceDirectory=" in candidate)
        assertTrue(candidate.indexOf("Upload the exact technical candidate") < candidate.indexOf("Require external publication approvals"))
    }

    @Test
    fun `iOS native slices run independently before one imported aggregate`() {
        val ci = workflows.getValue("ci.yml")
        val candidate = workflows.getValue("release-candidate.yml")
        assertTrue("group: ci-${'$'}{{ github.workflow }}-${'$'}{{ github.event.pull_request.number || github.ref }}" in ci)
        assertTrue("cancel-in-progress: true" in ci)
        assertTrue("cancel-in-progress: false" in candidate)

        fun section(workflow: String, job: String, next: String) =
            workflow.substringAfter("\n  $job:").substringBefore("\n  $next:")
        val ciDevice = section(ci, "ios-device-slice", "ios-simulator-slice")
        val ciSimulator = section(ci, "ios-simulator-slice", "ios")
        val ciAggregate = ci.substringAfter("\n  ios:")
        val candidateAggregate = candidate.substringAfter("\n  apple-candidate:")

        listOf(ciDevice, ciSimulator).forEach {
            assertTrue("needs: [workflow-lint, android-jvm]" in it)
            assertTrue("uses: actions/cache@v4" in it)
            assertTrue("~/.cargo/registry" in it && "~/.cargo/git" in it)
            assertTrue("native/provenance.json" in it && "native/patches/**" in it)
            assertFalse("build/rust" in it.substringAfter("Restore pinned Cargo downloads").substringBefore("Install pinned Rust toolchain"))
        }
        assertTrue("needs: [ios-device-slice, ios-simulator-slice]" in ciAggregate)
        assertTrue("resolve-ios-evidence" in candidateAggregate)

        listOf(ciDevice).forEach {
            assertTrue("exportCodexAgentIosArm64RustSlice" in it)
            assertTrue("build/apple-slice-exports/" in it)
            assertTrue("codex-agent-ios-arm64.a" in it)
            assertTrue("codex-agent-ios-arm64-proof.json" in it)
            assertTrue("native-tests-proof.json" in it)
        }
        listOf(ciSimulator).forEach {
            assertTrue("exportCodexAgentIosSimulatorArm64RustSlice" in it)
            assertTrue("build/apple-slice-exports/" in it)
            assertTrue("codex-agent-ios-simulator-arm64.a" in it)
            assertTrue("codex-agent-ios-simulator-arm64-proof.json" in it)
            assertFalse("native-tests-proof.json" in it)
        }
        listOf(ciDevice, ciSimulator).forEach {
            assertTrue("if-no-files-found: error" in it)
            assertTrue("compression-level: 0" in it)
            assertTrue("retention-days: 30" in it)
        }
        listOf(ciAggregate, candidateAggregate).forEach {
            assertTrue("-PcodexAgent.iosNativeEvidenceDirectory=" in it)
            assertFalse("exportCodexAgentIosArm64RustSlice" in it)
            assertFalse("exportCodexAgentIosSimulatorArm64RustSlice" in it)
            assertFalse("./gradlew clean" in it)
        }
        assertTrue("./gradlew verifyIosRuntime" in ciAggregate)
        assertTrue("./gradlew assembleProtectedCandidate" in candidateAggregate)
        assertEquals(1, Regex("exportCodexAgentIosArm64RustSlice").findAll(ci + candidate).count())
        assertEquals(1, Regex("exportCodexAgentIosSimulatorArm64RustSlice").findAll(ci + candidate).count())
        assertFalse("exportCodexAgentIosArm64RustSlice" in candidate)
        assertFalse("exportCodexAgentIosSimulatorArm64RustSlice" in candidate)
        assertTrue("run-id: ${'$'}{{ needs.resolve-ios-evidence.outputs.ci_run_id }}" in candidateAggregate)
        assertTrue("name: codex-agent-ci-ios-arm64-${'$'}{{ needs.portable-gates.outputs.candidate_commit }}" in candidateAggregate)
        assertTrue("name: codex-agent-ci-ios-simulator-arm64-${'$'}{{ needs.portable-gates.outputs.candidate_commit }}" in candidateAggregate)
    }

    @Test
    fun `candidate resolves a successful exact-commit CI run without rebuilding native slices`() {
        val candidate = workflows.getValue("release-candidate.yml")
        val resolver = candidate.substringAfter("\n  resolve-ios-evidence:").substringBefore("\n  apple-candidate:")
        assertTrue("ci_run_id:" in candidate.substringBefore("\npermissions:"))
        assertTrue("gh run list" in resolver && "--workflow=ci.yml" in resolver)
        assertTrue("--commit=\"\$CANDIDATE_COMMIT\"" in resolver)
        assertTrue("--status=success" in resolver)
        assertTrue("test \"\$matches\" -ge 1" in resolver)
        assertTrue("test \"\$matches\" -eq 1" in resolver)
        assertTrue("actions/runs/\$ci_run_id" in resolver && "--jq .path" in resolver)
        assertTrue(".github/workflows/ci.yml" in resolver && "headSha" in resolver && "conclusion" in resolver)
        assertTrue("ci_run_id=%s" in resolver)
    }

    @Test
    fun `desktop evidence uses bash consistently on every hosted runner`() {
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        val smoke = desktop.substringAfter("Run and record the official app-server lifecycle smoke")
            .substringBefore("- uses: actions/upload-artifact")
        assertTrue("shell: bash" in smoke)
        assertTrue("-PcodexAgent.candidateCommit=${'$'}{{ inputs.candidateCommit }}" in smoke)
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

        assertTrue("needs: linux-arm64-cross-build" in runtime)
        assertTrue("runs-on: ubuntu-24.04-arm" in runtime)
        assertTrue("RUNNER_OS: Linux" in runtime && "RUNNER_ARCH: ARM64" in runtime)
        assertTrue("name: codex-agent-linux-arm64-execution-bundle" in runtime)
        assertTrue("./gradlew -p buildSrc executeLinuxArm64DesktopEvidenceBundle" in runtime)
        assertFalse(Regex("""\./gradlew\s+:(?!test\b)""").containsMatchIn(runtime))
        assertTrue("name: codex-agent-desktop-runtime-evidence-linuxArm64" in runtime)
        val finalArtifact = runtime.substringAfter("name: codex-agent-desktop-runtime-evidence-linuxArm64")
        assertTrue("path: build/reports/desktop-runtime-evidence/desktop-runtime-linuxArm64.json" in finalArtifact)
        assertFalse(".xml" in finalArtifact)
    }

    @Test
    fun `publication persists the deployment before polling and never rebuilds`() {
        val publish = workflows.getValue("publish.yml")
        val prepare = publish.indexOf("prepareCentralDeployment")
        val persist = publish.indexOf("Persist the Central deployment ID immediately")
        val await = publish.indexOf("awaitCentralValidation")
        val publicSwift = publish.indexOf("verifyPublicSwiftResolution")
        val centralRelease = publish.indexOf("releaseCentralDeployment")
        assertTrue(prepare >= 0 && prepare < persist && persist < await)
        assertTrue(await < publicSwift && publicSwift < centralRelease)
        assertTrue("-PcodexAgent.allowCentralUpload=" in publish)
        assertTrue("test \"\$deployment_records\" -le 1" in publish)
        assertTrue("if [ \"\$deployment_records\" -eq 1 ]" in publish)
        assertTrue("allow_upload=false" in publish)
        assertTrue("allow_upload=true" in publish)
        assertFalse(Regex("""\./gradlew\s+(?:assemble|build|stage|publish)""").containsMatchIn(publish))
        assertFalse("pattern: codex-agent-*-candidate" in publish)
    }

    @Test
    fun `release workflows contain no retired implementations or illegal runner contexts`() {
        val combined = workflows.values.joinToString("\n")
        listOf("release/scripts/", "python3", "curl ", "find ", "awk ", "shasum").forEach { retired ->
            assertFalse(retired in combined, retired)
        }
        assertFalse(Regex("""(?m)^\s*jq\s""").containsMatchIn(combined))
        assertFalse("${'$'}{{ runner.temp }}" in combined)
        assertFalse("validateCentralDeployment" in combined)
        assertTrue("github.com/rhysd/actionlint/cmd/actionlint@v1.7.12" in workflows.getValue("ci.yml"))
        assertEquals(
            "self-hosted-runner:\n  labels:\n    - android\n",
            repository.resolve(".github/actionlint.yaml").readText(),
        )
    }

    @Test
    fun `privacy reachable Apple jobs install pinned LLVM tools`() {
        val ci = workflows.getValue("ci.yml")
        val ciIos = ci.substringAfter("\n  ios:")
        assertTrue("timeout-minutes: 240" in ciIos)
        val candidate = workflows.getValue("release-candidate.yml")
        val appleCandidate = candidate.substringAfter("\n  apple-candidate:")
        val jobs = listOf(
            ci.substringAfter("\n  ios-device-slice:").substringBefore("\n  ios-simulator-slice:") to
                "exportCodexAgentIosArm64RustSlice",
            ci.substringAfter("\n  ios-simulator-slice:").substringBefore("\n  ios:") to
                "exportCodexAgentIosSimulatorArm64RustSlice",
            ciIos to "./gradlew verifyIosRuntime",
            appleCandidate to "assembleProtectedCandidate",
        )

        jobs.forEach { (job, privacyReachableTask) ->
            val setup = job.indexOf("uses: dtolnay/rust-toolchain@1.95.0")
            assertTrue(setup >= 0 && setup < job.indexOf(privacyReachableTask), privacyReachableTask)
            assertTrue("targets: aarch64-apple-ios" in job, privacyReachableTask)
            assertTrue("components: llvm-tools-preview,rust-src" in job, privacyReachableTask)
        }
        assertEquals(1, Regex("(?m)^  apple-candidate:$").findAll(candidate).count())
        assertFalse("rustup toolchain install" in workflows.values.joinToString("\n"))
    }
}
