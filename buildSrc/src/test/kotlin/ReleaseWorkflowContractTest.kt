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
    fun `candidate consumes one immutable commit and Android evidence before one assembly`() {
        val candidate = workflows.getValue("release-candidate.yml")
        assertTrue("name: codex-agent-android-runtime-evidence" in candidate)
        assertTrue("name: codex-agent-protected-candidate" in candidate)
        assertTrue("candidate_commit" in candidate)
        assertTrue("recordAndroidRuntimeEvidence" in workflows.getValue("android-runtime-evidence.yml"))
        assertFalse("swiftpm-baseline" in candidate)
        assertFalse("swiftPmBaselineProof" in candidate)
        assertFalse("commit_a" in candidate || "commit_b" in candidate)
        assertTrue(candidate.indexOf("Upload the exact technical candidate") < candidate.indexOf("Require external publication approvals"))
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
        val ciIos = workflows.getValue("ci.yml").substringAfter("\n  ios:")
        assertTrue("timeout-minutes: 240" in ciIos)
        val candidate = workflows.getValue("release-candidate.yml")
        val appleCandidate = candidate.substringAfter("\n  apple-candidate:")
        val jobs = listOf(
            ciIos to "./gradlew verifyIosRuntime",
            appleCandidate to "assembleProtectedCandidate",
        )

        jobs.forEach { (job, privacyReachableTask) ->
            val setup = job.indexOf("uses: dtolnay/rust-toolchain@1.95.0")
            assertTrue(setup >= 0 && setup < job.indexOf(privacyReachableTask), privacyReachableTask)
            assertTrue("targets: aarch64-apple-ios,aarch64-apple-ios-sim" in job, privacyReachableTask)
            assertTrue("components: llvm-tools-preview" in job, privacyReachableTask)
        }
        assertEquals(1, Regex("(?m)^  apple-candidate:$").findAll(candidate).count())
        assertFalse("rustup toolchain install" in workflows.values.joinToString("\n"))
    }
}
