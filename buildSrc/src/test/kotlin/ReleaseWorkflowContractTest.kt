import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal object ReleaseWorkflowFixture {
    val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { it.resolve(".github/workflows/release-candidate.yml").isFile }
    val workflows = listOf(
        "ci.yml",
        "android-runtime-evidence.yml",
        "desktop-runtime-evidence.yml",
        "release-candidate.yml",
        "publish.yml",
    ).associateWith { repository.resolve(".github/workflows/$it").readText() }
}

class ReleaseWorkflowContractTest {
    private val repository = ReleaseWorkflowFixture.repository
    private val workflows = ReleaseWorkflowFixture.workflows

    @Test
    fun `external actions are immutable and transport is rerun safe`() {
        val combined = workflows.values.joinToString("\n")
        val uses = combined.lineSequence().map { it.trim().removePrefix("- ") }
            .filter { it.startsWith("uses: ") && !it.startsWith("uses: ./") }.toList()
        assertTrue(uses.isNotEmpty())
        uses.forEach { use ->
            assertTrue(Regex("[0-9a-f]{40}").matches(use.substringAfter('@').substringBefore(' ')), use)
            assertTrue(" # " in use, use)
        }
        val checkouts = uses.count { it.startsWith("uses: actions/checkout@") }
        val uploads = uses.count { it.startsWith("uses: actions/upload-artifact@") }
        assertEquals(checkouts, Regex("(?m)^\\s+persist-credentials: false$").findAll(combined).count())
        assertEquals(uploads, Regex("(?m)^\\s+if-no-files-found: error$").findAll(combined).count())
        assertEquals(uploads, Regex("(?m)^\\s+overwrite: (?:true|false)$").findAll(combined).count())
    }

    @Test
    fun `Firebase evidence accepts only protected candidates before OIDC and uses default result storage`() {
        val android = workflows.getValue("android-runtime-evidence.yml")
        val validation = android.indexOf("Validate the protected candidate identity before running code")
        val checkout = android.indexOf("uses: actions/checkout@")
        val build = android.indexOf("Build the exact application APK, test APK, and release AAR")
        val authentication = android.indexOf("uses: google-github-actions/auth@")

        assertFalse("workflow_dispatch:" in android)
        assertTrue(validation in 0 until checkout)
        assertTrue(build in (checkout + 1) until authentication)
        assertTrue("test \"${'$'}GITHUB_SHA\" = \"${'$'}CANDIDATE_COMMIT\"" in android)
        assertTrue("test \"${'$'}GITHUB_REF_PROTECTED\" = true" in android)
        assertFalse("FIREBASE_TEST_RESULTS_BUCKET" in android)
        assertFalse("--results-bucket" in android)
        assertFalse("--results-dir" in android)
        assertTrue("gcsPath" in android)
        assertTrue("gcloud storage cp --recursive \"${'$'}results_uri\"" in android)
        assertFalse("-PcodexAgent.candidateCommit=${'$'}{{" in android)
    }

    @Test
    fun `ordinary CI cancels stale work and gates expensive Apple slices`() {
        val ci = workflows.getValue("ci.yml")
        assertTrue("group: ci-${'$'}{{ github.workflow }}-${'$'}{{ github.event.pull_request.number || github.ref }}" in ci)
        assertTrue("cancel-in-progress: true" in ci)
        assertEquals(2, Regex("needs: \\[workflow-lint, android-jvm]").findAll(ci).count())
        assertTrue("./gradlew verifyRepository" in ci)
        assertTrue(":codex-agent-client:compileKotlinWasmJs" in repository.resolve(
            "buildSrc/src/main/kotlin/RepositoryVerificationTasks.kt",
        ).readText())
        assertFalse("browser" in ci.lowercase())
        assertFalse("wasi" in ci.lowercase())
    }

    @Test
    fun `candidate tag and CI identity are exact without rebuilding portable checks`() {
        val candidate = workflows.getValue("release-candidate.yml")
        val gate = candidate.substringAfter("\n  portable-gates:").substringBefore("\n  desktop-runtime-evidence:")
        val resolver = candidate.substringAfter("\n  resolve-ci-evidence:").substringBefore("\n  apple-candidate:")
        assertFalse("release_tag:" in candidate.substringAfter("workflow_dispatch:").substringBefore("  push:"))
        assertFalse("default: v" in candidate)
        assertFalse("0.2.0" in candidate)
        assertTrue("^candidate/v([0-9]+\\.[0-9]+\\.[0-9]+)-rc\\.([1-9][0-9]*)$" in gate)
        assertTrue("fetch-depth: 0" in gate)
        assertTrue("git merge-base --is-ancestor" in gate && "origin/main" in gate)
        assertTrue("test \"${'$'}GITHUB_REF_PROTECTED\" = true" in gate)
        val appleCandidate = candidate.substringAfter("  apple-candidate:")
        assertFalse("codex-agent-candidate-identity" in gate)
        assertTrue("name: codex-agent-candidate-identity-${'$'}{{ github.run_attempt }}" in appleCandidate)
        assertTrue("candidateRunAttempt=%s\\n' \\" in appleCandidate)
        assertTrue("\"${'$'}GITHUB_RUN_ID\" \"${'$'}GITHUB_RUN_ATTEMPT\" > build/candidate-identity.txt" in appleCandidate)
        assertFalse("verifyRepository" in gate)
        assertFalse("-p buildSrc test" in gate)
        assertTrue("--branch=main --event=push" in resolver)
        assertTrue("--commit=\"${'$'}CANDIDATE_COMMIT\" --status=success" in resolver)
        listOf(".path", ".event", ".head_branch", ".head_sha", ".head_repository.full_name", ".conclusion", ".run_attempt")
            .forEach { assertTrue(it in resolver) }
        assertTrue("test \"${'$'}repository\" = \"${'$'}GITHUB_REPOSITORY\"" in resolver)
        assertTrue("ci-provenance.json" in resolver)
    }

    @Test
    fun `candidate imports every mandatory runtime family and assembles once`() {
        val candidate = workflows.getValue("release-candidate.yml")
        assertTrue("uses: ./.github/workflows/desktop-runtime-evidence.yml" in candidate)
        assertTrue("uses: ./.github/workflows/android-runtime-evidence.yml" in candidate)
        assertTrue("id-token: write" in candidate)
        assertTrue("pattern: codex-agent-runtime-evidence-*" in candidate)
        assertTrue("name: codex-agent-portable-runtime-evidence-runners" in candidate)
        assertTrue("name: codex-agent-android-runtime-evidence-${'$'}{{ needs.portable-gates.outputs.candidate_commit }}" in candidate)
        listOf(
            "desktopEvidenceDirectory", "jvmEvidenceDirectory", "nodeEvidenceDirectory",
            "nodeWasmEvidenceDirectory", "androidRuntimeEvidenceDirectory", "desktopSupervisorDirectory",
            "iosNativeEvidenceDirectory", "ciProvenance",
        ).forEach { assertTrue("-PcodexAgent.$it=" in candidate, it) }
        assertEquals(1, Regex("./gradlew assembleProtectedCandidate").findAll(candidate).count())
        assertTrue("--no-parallel" in candidate)
        assertFalse("windowsNodeSupervisor" in candidate)
        val assembly = candidate.substringAfter("Assemble and sign the exact protected candidate once")
            .substringBefore("- name: Upload the exact technical candidate")
        assertTrue("SIGNING_IN_MEMORY_KEY" in assembly)
        assertFalse("SIGNING_IN_MEMORY_KEY" in candidate.substringBefore("Assemble and sign"))
        assertEquals(1, Regex("(?m)^    environment: release-candidate$").findAll(candidate).count())
        assertTrue("name: codex-agent-protected-candidate-${'$'}{{ github.run_attempt }}" in candidate)
        assertTrue("overwrite: false" in candidate)
        val desktop = workflows.getValue("desktop-runtime-evidence.yml") +
            repository.resolve(".github/actions/setup-msvc/action.yml").readText()
        assertTrue("VsDevCmd.bat" in desktop && "Microsoft.VisualStudio.Component.VC.Tools.x86.x64" in desktop)
    }

    @Test
    fun `candidate reuses exact CI Apple slices without rebuilding them`() {
        val ci = workflows.getValue("ci.yml")
        val candidate = workflows.getValue("release-candidate.yml")
        assertEquals(1, Regex("exportCodexAgentIosArm64RustSlice").findAll(ci + candidate).count())
        assertEquals(1, Regex("exportCodexAgentIosSimulatorArm64RustSlice").findAll(ci + candidate).count())
        assertFalse("exportCodexAgentIosArm64RustSlice" in candidate)
        assertFalse("exportCodexAgentIosSimulatorArm64RustSlice" in candidate)
        assertEquals(2, candidate.lineSequence().count {
            "run-id: ${'$'}{{ needs.resolve-ci-evidence.outputs.ci_run_id }}" in it
        })
        assertTrue("-PcodexAgent.iosNativeEvidenceDirectory=" in candidate)
    }

    @Test
    fun `publication derives identity then publishes Central before GitHub`() {
        val publish = workflows.getValue("publish.yml")
        assertFalse("0.2.0" in publish)
        assertTrue("name: codex-agent-candidate-identity-${'$'}{{ github.event.workflow_run.run_attempt }}" in publish)
        assertTrue("name: codex-agent-protected-candidate-${'$'}{{ github.event.workflow_run.run_attempt }}" in publish)
        assertTrue("candidate_run_attempt\" = \"${'$'}WORKFLOW_RUN_ATTEMPT" in publish)
        assertTrue("^candidate/v([0-9]+\\.[0-9]+\\.[0-9]+)-rc\\.([1-9][0-9]*)$" in publish)
        assertTrue("environment: release-publication" in publish)
        val jobHeader = publish.substringAfter("\n  publish:").substringBefore("    steps:")
        assertFalse("MAVEN_CENTRAL_" in jobHeader)
        assertEquals(3, Regex("MAVEN_CENTRAL_USERNAME:").findAll(publish).count())
        assertEquals(3, Regex("MAVEN_CENTRAL_PASSWORD:").findAll(publish).count())
        val prepare = publish.indexOf("prepareCentralDeployment")
        val await = publish.indexOf("awaitCentralValidation")
        val central = publish.indexOf("releaseCentralDeployment")
        val github = publish.indexOf("Create or reuse the exact GitHub release after Central")
        val swift = publish.indexOf("verifyPublicSwiftResolution")
        assertTrue(prepare in 0 until await && await < central && central < github && github < swift)
        assertTrue("-PcodexAgent.allowCentralUpload=true" in publish)
        assertFalse(Regex("""\./gradlew\s+(?:assemble|build|stage|publish)""").containsMatchIn(publish))
    }

    @Test
    fun `workflows reject retired duplicate and unsafe release paths`() {
        val combined = workflows.values.joinToString("\n")
        listOf(
            "windows-node-supervisor", "windowsNodeSupervisor", "commit_a", "commit_b",
            "byte-parity", "browser", "wasi", "release/scripts/", "python3", "curl ", "shasum",
        ).forEach { assertFalse(it.lowercase() in combined.lowercase(), it) }
        assertFalse(Regex("(?m)^\\s*jq\\s").containsMatchIn(combined))
        assertFalse("${'$'}{{ runner.temp }}" in combined)
        assertTrue("github.com/rhysd/actionlint/cmd/actionlint@v1.7.12" in workflows.getValue("ci.yml"))
        assertEquals("self-hosted-runner:\n  labels:\n    - android\n", repository.resolve(
            ".github/actionlint.yaml",
        ).readText())
    }
}
