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
        "apple-runtime-evidence.yml",
        "desktop-runtime-evidence.yml",
        "release-candidate.yml",
        "publish.yml",
    ).associateWith { repository.resolve(".github/workflows/$it").readText() }
    val actions = listOf("setup-kmp", "setup-msvc", "setup-sccache")
        .associateWith { repository.resolve(".github/actions/$it/action.yml").readText() }
}

class ReleaseWorkflowContractTest {
    private val repository = ReleaseWorkflowFixture.repository
    private val workflows = ReleaseWorkflowFixture.workflows
    private val actions = ReleaseWorkflowFixture.actions

    @Test
    fun `external actions are immutable and artifact transport is strict`() {
        val combined = (workflows.values + actions.values).joinToString("\n")
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
    fun `ordinary CI is PR plus main and starts Apple after lint`() {
        val ci = workflows.getValue("ci.yml")
        assertTrue("branches: [main]" in ci)
        assertTrue("pull_request:" in ci)
        assertTrue("group: ci-${'$'}{{ github.workflow }}-${'$'}{{ github.event.pull_request.number || github.ref }}" in ci)
        assertTrue("cancel-in-progress: true" in ci)
        assertTrue("apple-runtime-evidence:" in ci)
        assertTrue("needs: workflow-lint" in ci.substringAfter("apple-runtime-evidence:"))
        assertFalse("needs: [workflow-lint, android-jvm]" in ci.substringAfter("apple-runtime-evidence:").substringBefore("desktop-runtime-evidence:"))
        assertTrue("./gradlew verifyRepository --parallel" in ci)
        assertTrue("cache: false" in ci)
        assertTrue("actionlint-v1-${'$'}{{ runner.os }}-${'$'}{{ runner.arch }}-v1.7.12" in ci)
        assertTrue("github.event_name == 'push' && github.ref == 'refs/heads/main'" in ci)
    }

    @Test
    fun `shared caches are encrypted pinned and read-only outside trusted main writers`() {
        val setup = actions.getValue("setup-kmp")
        val sccache = actions.getValue("setup-sccache")
        val combined = workflows.values.joinToString("\n")
        assertTrue("cache-encryption-key:" in setup)
        assertTrue("actions/cache/restore@0057852bfaa89a56745cba8c7296529d2fc39830" in setup)
        assertTrue("path: ~/.konan" in setup)
        assertTrue("xcodebuild -version" in setup)
        assertTrue("version: v0.17.0" in sccache)
        assertTrue("SCCACHE_GHA_VERSION=codex-agent-rust-v1" in sccache)
        assertTrue("READ_ONLY" in sccache && "READ_WRITE" in sccache)
        assertTrue("af205fcb0fbca2b051dd293f8a96d3177cfb74b06324a948d7ee7dbadf367b78" in sccache)
        assertTrue("066c5a84c85044c8f48b3ab571ac114293ea717c3d36985db022af8206e21e63" in sccache)
        assertFalse("uses: actions/cache@" in combined)
        assertFalse("--no-daemon" in combined)
    }

    @Test
    fun `Firebase uses exact main binaries and downloads only XML evidence`() {
        val android = workflows.getValue("android-runtime-evidence.yml")
        val validation = android.indexOf("Validate protected identity and Google configuration")
        val checkout = android.indexOf("uses: actions/checkout@")
        val authentication = android.indexOf("uses: google-github-actions/auth@")
        assertFalse("workflow_dispatch:" in android)
        assertTrue(validation in 0 until checkout)
        assertTrue("actions: read" in android)
        assertTrue("run-id: ${'$'}{{ inputs.ciRunId }}" in android)
        assertTrue("codex-agent-ci-android-binaries-" in android)
        listOf("firebaseApplicationApk", "firebaseTestApk", "firebaseReleaseAar")
            .forEach { assertTrue("-PcodexAgent.$it=" in android, it) }
        assertFalse("assembleDebug" in android)
        assertTrue(authentication > checkout)
        assertTrue("--no-performance-metrics" in android)
        assertTrue("--no-record-video" in android)
        assertTrue("gcloud storage cp \"${'$'}results_uri/**/*.xml\"" in android)
        assertFalse("gcloud storage cp --recursive" in android)
    }

    @Test
    fun `candidate resolves one exact successful main attempt and only imports expensive work`() {
        val candidate = workflows.getValue("release-candidate.yml")
        val resolver = candidate.substringAfter("\n  resolve-ci-evidence:")
            .substringBefore("\n  android-runtime-evidence:")
        assertTrue("--branch=main --event=push" in resolver)
        assertTrue("--commit=\"${'$'}CANDIDATE_COMMIT\" --status=success" in resolver)
        listOf(".path", ".event", ".head_branch", ".head_sha", ".head_repository.full_name", ".conclusion", ".run_attempt")
            .forEach { assertTrue(it in resolver) }
        assertTrue("ci_run_attempt" in resolver)
        assertTrue("actions: read" in candidate)
        assertFalse("uses: ./.github/workflows/desktop-runtime-evidence.yml" in candidate)
        assertTrue("uses: ./.github/workflows/android-runtime-evidence.yml" in candidate)
        assertTrue("id-token: write" in candidate)
        listOf(
            "portableRuntimeArtifactsDirectory", "desktopClassifierDirectory", "androidRuntimeEvidenceDirectory",
            "importedAndroidReleaseAar", "iosNativeEvidenceDirectory", "iosVerifiedDistributionDirectory", "ciProvenance",
        ).forEach { assertTrue("-PcodexAgent.$it=" in candidate, it) }
        assertEquals(1, Regex("./gradlew assembleProtectedCandidate").findAll(candidate).count())
        assertTrue("--parallel" in candidate)
        assertFalse("--no-parallel" in candidate)
        assertEquals(1, Regex("(?m)^    environment: release-candidate$").findAll(candidate).count())
        assertTrue("name: codex-agent-candidate-identity-${'$'}{{ github.run_attempt }}" in candidate)
        assertTrue("name: codex-agent-protected-candidate-${'$'}{{ github.run_attempt }}" in candidate)
    }

    @Test
    fun `intermediate artifacts survive partial reruns within their validated run`() {
        val combined = workflows.values.joinToString("\n")
        assertFalse("inputs.ciRunAttempt" in combined)
        assertFalse("inputs.ci_run_attempt" in combined)
        listOf(
            "codex-agent-ci-portable-runtime-artifacts-${'$'}{{ github.sha }}",
            "codex-agent-ci-android-binaries-${'$'}{{ github.sha }}",
            "codex-agent-android-runtime-evidence-${'$'}{{ inputs.candidateCommit }}",
            "codex-agent-ci-ios-verified-distribution-${'$'}{{ inputs.candidateCommit }}",
            "codex-agent-publication-core-${'$'}{{ github.event.workflow_run.head_sha }}",
        ).forEach { assertTrue(it in combined, it) }
        assertEquals(2, Regex("name: codex-agent-(?:candidate-identity|protected-candidate)-\\$\\{\\{ github.run_attempt }}")
            .findAll(combined).count())
    }

    @Test
    fun `candidate validates trust before local code receives cache secrets`() {
        val candidate = workflows.getValue("release-candidate.yml")
        val validation = candidate.indexOf("Validate the protected candidate ref before checkout")
        val checkout = candidate.indexOf("uses: actions/checkout@")
        val ancestry = candidate.indexOf("Bind the candidate commit to main")
        val setup = candidate.indexOf("uses: ./.github/actions/setup-kmp")
        assertTrue(validation in 0 until checkout)
        assertTrue(checkout < ancestry && ancestry < setup)
        assertTrue("GITHUB_REF_PROTECTED" in candidate)
    }

    @Test
    fun `Apple host proof is independent and candidate imports the whole verified distribution`() {
        val apple = workflows.getValue("apple-runtime-evidence.yml")
        val candidate = workflows.getValue("release-candidate.yml")
        val host = apple.substringAfter("\n  native-tests:").substringBefore("\n  slice:")
        val aggregate = apple.substringAfter("\n  verified-distribution:")
        assertTrue("runs-on: ubuntu-24.04" in host)
        assertFalse("verifyAppleToolchain" in host)
        assertEquals(1, Regex("exportCodexAgentIosNativeTestsProof").findAll(apple).count())
        assertEquals(1, Regex("exportCodexAgentIosArm64RustSlice").findAll(apple).count())
        assertEquals(1, Regex("exportCodexAgentIosSimulatorArm64RustSlice").findAll(apple).count())
        assertEquals(1, Regex("exportCodexAgentIosVerifiedDistribution").findAll(apple).count())
        assertTrue("cache-read-only: ${'$'}{{ !inputs.trustedCacheWriter }}" in apple)
        assertEquals(1, apple.lineSequence().count {
            it.trim() == "cache-read-only: ${'$'}{{ !inputs.trustedCacheWriter }}"
        })
        assertTrue("cache-read-only: true" in aggregate)
        assertFalse("exportCodexAgentIos" in candidate)
        assertTrue("codex-agent-ci-ios-verified-distribution-" in candidate)
        assertTrue("-PcodexAgent.iosVerifiedDistributionDirectory=" in candidate)
    }

    @Test
    fun `sccache runs only where workflows invoke Rust`() {
        val apple = workflows.getValue("apple-runtime-evidence.yml")
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        assertEquals(2, Regex("uses: ./\\.github/actions/setup-sccache").findAll(apple).count())
        assertFalse("setup-sccache" in desktop)
    }

    @Test
    fun `unified Linux ARM execution writes the evidence paths uploaded by CI`() {
        val desktop = workflows.getValue("desktop-runtime-evidence.yml")
        mapOf(
            "desktopEvidenceOutput" to "codex-agent-runtime-desktop/build/reports/desktop-runtime-evidence/desktop-runtime-linuxArm64.json",
            "jvmEvidenceOutput" to "codex-agent-runtime-desktop/build/reports/jvm-runtime-evidence/jvm-runtime-linuxArm64.json",
            "nodeEvidenceOutput" to "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-runtime-linuxArm64.json",
            "nodeWasmEvidenceOutput" to "codex-agent-runtime-node/build/reports/node-runtime-evidence/node-wasm-runtime-linuxArm64.json",
        ).forEach { (property, path) ->
            assertTrue("-PcodexAgent.$property=\"${'$'}PWD/$path\"" in desktop, property)
        }
    }

    @Test
    fun `published release verification covers every release asset through API metadata`() {
        val publish = workflows.getValue("publish.yml")
        listOf(
            "${'$'}SWIFT_ASSET|${'$'}PAYLOAD/${'$'}SWIFT_ASSET",
            "candidate-manifest.json|${'$'}PAYLOAD/candidate-manifest.json",
            "central-deployment.json|${'$'}RECORD",
        ).forEach { asset -> assertTrue(asset in publish, asset) }
        assertTrue("release_json=${'$'}(gh api" in publish)
        assertTrue("test \"${'$'}asset_count\" -eq 1" in publish)
        assertTrue("test \"${'$'}api_digest\" = \"${'$'}expected_digest\"" in publish)
    }

    @Test
    fun `publication is Central-first on Ubuntu and Swift-only on downstream macOS`() {
        val publish = workflows.getValue("publish.yml")
        val core = publish.substringAfter("\n  publish-core:").substringBefore("\n  swift-verification:")
        val swift = publish.substringAfter("\n  swift-verification:")
        assertTrue("runs-on: ubuntu-24.04" in core)
        assertTrue("environment: release-publication" in core)
        assertTrue("runs-on: macos-26" in swift)
        assertFalse("MAVEN_CENTRAL_" in swift)
        val prepare = publish.indexOf("prepareCentralDeployment")
        val await = publish.indexOf("awaitCentralValidation")
        val central = publish.indexOf("releaseCentralDeployment")
        val github = publish.indexOf("Create or reuse the exact GitHub release after Central")
        val swiftCheck = publish.indexOf("verifyPublicSwiftResolution")
        assertTrue(prepare in 0 until await && await < central && central < github && github < swiftCheck)
        assertTrue(".digest" in core)
        assertTrue("compare their official digests" in core)
        assertFalse("gh release download" in publish)
        assertEquals(1, Regex("verifyPublicSwiftResolution").findAll(publish).count())
    }

    @Test
    fun `workflow sources remain bounded and reject duplicate release paths`() {
        val combined = workflows.values.joinToString("\n")
        (workflows + actions).forEach { (name, source) ->
            assertTrue(source.lineSequence().count() <= 300, "$name exceeds 300 lines")
        }
        listOf(
            "windows-node-supervisor", "windowsNodeSupervisor", "commit_a", "commit_b",
            "byte-parity", "browser", "wasi", "release/scripts/", "python3", "curl ", "shasum",
        ).forEach { assertFalse(it.lowercase() in combined.lowercase(), it) }
        assertFalse(Regex("(?m)^\\s*jq\\s").containsMatchIn(combined))
        assertTrue("github.com/rhysd/actionlint/cmd/actionlint@v1.7.12" in workflows.getValue("ci.yml"))
        assertEquals("self-hosted-runner:\n  labels:\n    - android\n", repository.resolve(
            ".github/actionlint.yaml",
        ).readText())
    }
}
