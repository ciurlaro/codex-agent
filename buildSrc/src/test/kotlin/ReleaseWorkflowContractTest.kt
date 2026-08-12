import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseWorkflowContractTest {
    private val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { it.resolve(".github/workflows/release-candidate.yml").isFile }
    private val workflows = listOf("ci.yml", "android-runtime-evidence.yml", "release-candidate.yml", "publish.yml")
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
            ":codex-agent-runtime-ios:verifyAppleToolchain",
            ":codex-agent-runtime-android:recordAndroidRuntimeEvidence",
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
