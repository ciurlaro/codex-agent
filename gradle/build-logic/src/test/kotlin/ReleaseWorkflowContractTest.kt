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
        "promote.yml",
        "android-runtime-evidence.yml",
        "apple-runtime-evidence.yml",
        "desktop-runtime-evidence.yml",
        "release-candidate.yml",
        "publish.yml",
    ).associateWith { repository.resolve(".github/workflows/$it").readText() }
    val actions = listOf("run-ci-lane", "setup-kmp", "setup-msvc", "setup-sccache")
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
            .filter { it.startsWith("uses: ") && !it.startsWith("uses: ./") }
            .filterNot { "/.github/workflows/" in it }
            .toList()
        assertTrue(uses.isNotEmpty())
        uses.forEach { use ->
            assertTrue(Regex("[0-9a-f]{40}").matches(use.substringAfter('@').substringBefore(' ')), use)
            assertTrue(" # " in use, use)
        }
        val checkouts = uses.count { it.startsWith("uses: actions/checkout@") }
        val uploads = uses.count { it.startsWith("uses: actions/upload-artifact@") }
        val optionalDiagnostics = Regex("(?m)^\\s+if-no-files-found: ignore$").findAll(combined).count()
        assertEquals(checkouts, Regex("(?m)^\\s+persist-credentials: false$").findAll(combined).count())
        assertEquals(
            uploads,
            Regex("(?m)^\\s+if-no-files-found: error$").findAll(combined).count() + optionalDiagnostics,
        )
        assertEquals(2, optionalDiagnostics)
        assertTrue("name: codex-agent-diagnostics-" in actions.getValue("run-ci-lane"))
        assertTrue("name: codex-agent-protected-candidate-diagnostics-" in workflows.getValue("release-candidate.yml"))
        assertEquals(uploads, Regex("(?m)^\\s+overwrite: (?:true|false)$").findAll(combined).count())
    }

    @Test
    fun `merge validation is promoted on main without rebuilding it`() {
        val ci = workflows.getValue("ci.yml")
        val promote = workflows.getValue("promote.yml")
        assertTrue("pull_request:" in ci && "merge_group:" in ci)
        assertTrue("  merge-gate:\n    name: CI / merge-gate\n" in ci)
        assertFalse(Regex("(?m)^  push:$").containsMatchIn(ci))
        assertTrue("python3 ci/receipt.py aggregate" in ci)
        assertTrue("name: codex-agent-ci-validation-${'$'}{{ needs.plan.outputs.validation_tree }}" in ci)
        assertTrue("branches: [main]" in promote)
        assertTrue("python3 ci/promote.py discover" in promote)
        assertTrue("python3 ci/promote.py validate-lane" in promote)
        assertTrue("python3 ci/promote.py receipt" in promote)
        assertTrue("name: ${'$'}{{ matrix.promotedArtifactName }}" in promote)
        assertTrue("name: ${'$'}{{ needs.discover.outputs.promoted_aggregate }}" in promote)
        assertFalse("\nconcurrency:\n" in promote)
        val android = workflows.getValue("android-runtime-evidence.yml")
        assertTrue("MERGE_READY: ${'$'}{{ !github.event.pull_request.draft && contains(github.event.pull_request.labels.*.name, 'merge-ready') }}" in android)
        assertTrue("[[ \"${'$'}GITHUB_EVENT_NAME\" = merge_group || \"${'$'}MERGE_READY\" = true ]]" in android)
        listOf("./gradlew", "setup-kmp", "setup-android", "cargo ", "xcodebuild", "firebase").forEach {
            assertFalse(it.lowercase() in promote.lowercase(), it)
        }
    }

    @Test
    fun `full Android lane reuse does not suppress trusted evidence`() {
        val job = workflows.getValue("ci.yml")
            .substringAfter("\n  android-runtime-evidence:")
            .substringBefore("\n\n  desktop:")
        assertTrue("needs.plan.outputs.validation_reused != 'true'" in job)
        assertTrue("needs.plan.outputs.android_evidence_required == 'true'" in job)
        assertFalse("needs.android.outputs.reused" in job)
        assertTrue("needs: [plan, android]" in job)
    }

    @Test
    fun `promotion bootstrap skips work until the merge queue is enabled`() {
        val promote = workflows.getValue("promote.yml")
        val bootstrap = promote.substringAfter("\n  bootstrap:").substringBefore("\n\n  discover:")
        val discover = promote.substringAfter("\n  discover:").substringBefore("\n\n  lanes:")
        val aggregate = promote.substringAfter("\n  aggregate:")
        assertTrue("vars.CI_MERGE_QUEUE_ENABLED != 'true'" in bootstrap)
        assertTrue("GITHUB_STEP_SUMMARY" in bootstrap)
        assertFalse("ci/promote.py" in bootstrap || "uses:" in bootstrap)
        assertTrue("vars.CI_MERGE_QUEUE_ENABLED == 'true'" in discover)
        assertTrue("needs.discover.result == 'success'" in aggregate)
    }

    @Test
    fun `candidate resolves one exact promotion and assembles only promoted bytes`() {
        val candidate = workflows.getValue("release-candidate.yml")
        val resolver = candidate.substringAfter("Resolve the successful exact-commit promotion")
            .substringBefore("\n\n  stage-candidate:")
        assertTrue("--workflow=promote.yml" in resolver)
        assertTrue("--branch=main --event=push --commit=\"${'$'}CANDIDATE_COMMIT\" --status=success" in resolver)
        listOf(".path", ".event", ".head_branch", ".head_sha", ".head_repository.full_name", ".conclusion", ".run_attempt")
            .forEach { assertTrue(it in resolver, it) }
        assertTrue("name: codex-agent-promoted-validation-${'$'}{{ needs.identity.outputs.candidate_commit }}" in candidate)
        assertFalse("pattern: codex-agent-promoted-*" in candidate)
        assertFalse("promoted-inventories" in candidate)
        listOf(
            "contracts", "portable", "android", "node-js", "node-wasm",
            "desktop-macos-arm64", "desktop-macos-x64", "desktop-linux-arm64", "desktop-linux-x64",
            "desktop-windows-x64", "ios-native-tests", "ios-rust-device", "ios-rust-simulator",
            "ios-framework-device", "ios-framework-simulator", "ios-kotlin-tests", "ios-swift-build",
            "ios-swift-tests", "ios-package", "ios-privacy-metrics", "consumer-common", "consumer-android",
            "consumer-desktop", "consumer-ios-device", "consumer-ios-simulator", "consumer-node-js",
            "consumer-node-wasm",
        ).forEach { assertTrue(Regex("(?:^|\\s)${Regex.escape(it)}(?:\\s|$)").containsMatchIn(candidate), it) }
        assertTrue("artifact=\"codex-agent-promoted-${'$'}lane-${'$'}CANDIDATE_COMMIT\"" in candidate)
        assertTrue("codex-agent-promoted-consumer-${'$'}target-${'$'}CANDIDATE_COMMIT/payload/maven" in candidate)
        assertTrue("for target in common android desktop ios-device ios-simulator node-js node-wasm" in candidate)
        assertFalse(Regex("(?m)\\bcmp(?:\\s|$)").containsMatchIn(candidate))
        assertEquals(1, candidate.lineSequence().count {
            "java -jar \"${'$'}RELEASE_TOOL\" assemble-promoted-candidate" in it
        })
        assertEquals(1, Regex("(?m)^    environment: release-candidate$").findAll(candidate).count())
        assertTrue("name: codex-agent-candidate-identity-${'$'}{{ github.run_attempt }}" in candidate)
        assertTrue("name: codex-agent-protected-candidate-${'$'}{{ github.run_attempt }}" in candidate)
        assertFalse("uses: ./.github/actions/setup-kmp" in candidate)
    }

    @Test
    fun `candidate validates trust before checking out repository code`() {
        val candidate = workflows.getValue("release-candidate.yml")
        val validation = candidate.indexOf("Validate the protected candidate ref before checkout")
        val checkout = candidate.indexOf("uses: actions/checkout@")
        val ancestry = candidate.indexOf("Bind the candidate tag to an immutable commit on main")
        assertTrue(validation in 0 until checkout)
        assertTrue(checkout < ancestry)
        assertTrue("GITHUB_REF_PROTECTED" in candidate)
        assertTrue("git merge-base --is-ancestor \"${'$'}candidate_commit\" origin/main" in candidate)
        assertTrue("git rev-parse \"${'$'}candidate_commit^{tree}\"" in candidate)
    }

    @Test
    fun `candidate and publication task graph has no source build path`() {
        val candidate = workflows.getValue("release-candidate.yml")
        val publish = workflows.getValue("publish.yml")
        assertFalse("./gradlew" in candidate || "./gradlew" in publish)
        listOf("stage-promoted-maven", "assemble-promoted-candidate").forEach {
            assertEquals(1, candidate.lineSequence().count { line ->
                "java -jar \"${'$'}RELEASE_TOOL\" $it" in line
            }, it)
        }
        listOf("verify-candidate", "central-prepare", "central-await", "central-release").forEach {
            assertEquals(1, publish.lineSequence().count { line ->
                "java -jar \"${'$'}RELEASE_TOOL\" $it" in line
            }, it)
        }
        val release = "$candidate\n$publish"
        listOf(
            "assembleProtectedCandidate", "stageCentralRepository", "verifyStagedKmpConsumer",
            "compileKotlin", "linkDebug", "assembleDebug", "setup-kmp", "setup-android",
            "cargo ", "xcodebuild", "firebase",
        ).forEach { assertFalse(it.lowercase() in release.lowercase(), it) }
        assertTrue("swift package --package-path" in publish && " resolve" in publish)
        assertFalse("swift build" in publish || "swift test" in publish)
    }

    @Test
    fun `candidate transports and revalidates semantic runtime evidence`() {
        val assembler = repository.resolve(
            "gradle/build-logic/src/main/kotlin/PromotedCandidateTasks.kt",
        ).readText()
        val verifier = repository.resolve(
            "gradle/build-logic/src/main/kotlin/CandidatePayloadTasks.kt",
        ).readText()
        listOf(
            "runtime-evidence", "jvm-runtime-evidence", "node-runtime-evidence",
            "node-wasm-runtime-evidence", "firebase-runtime-evidence",
        ).forEach {
            assertTrue(
                "one(\"$it\")" in assembler || "files(\"$it\")" in assembler || "desktop(\"$it\")" in assembler,
                it,
            )
        }
        assertTrue("lane.runtimeProducerCommit()" in assembler)
        assertTrue("files(\"transport-provenance\")" in assembler)
        assertTrue("source.releaseString(\"validationCommit\")" in assembler)
        listOf(
            "validateDesktopRuntimeEvidence", "validateJvmRuntimeEvidence", "validateNodeRuntimeEvidence",
            "verifyCandidateFirebaseAndroidEvidence", "verifyCandidateCentralAndroidRuntimeBinding",
            "extractCandidateDesktopClassifiers", "validateIosRuntimeMetrics", "verifyAppleArtifactBudgets",
            "codex-agent-ios-rust-slice-v2", "codex-agent-ios-native-tests-v2", "promoted-swift-package-v1",
        ).forEach { assertTrue(it in verifier, it) }
        listOf(
            "native-test-proof", "rust-proof", "ios-package-metrics-input", "runtime-metrics-evidence",
        ).forEach { assertTrue("one(\"$it\")" in assembler, it) }
        assertFalse("plan.releaseBoolean(\"full\")" in verifier)
    }

    @Test
    fun `full reuse and carried runtime evidence retain the origin producer commit`() {
        val lane = "desktop-linux-x64"
        val origin = "1".repeat(40)
        val reissued = "2".repeat(40)
        val current = "3".repeat(40)
        val provenance = kotlin.io.path.createTempDirectory("transport-provenance-").toFile()
            .resolve("transport-provenance.json")
        try {
            provenance.writeText(
                """{
                    |  "schemaVersion": 1,
                    |  "source": {
                    |    "event": "merge_group", "runId": 22, "runAttempt": 2, "pullRequest": 7,
                    |    "validationCommit": "$reissued", "validationTree": "${"4".repeat(40)}",
                    |    "artifactName": "codex-agent-ci-$lane-${"4".repeat(40)}"
                    |  },
                    |  "sourceTransportArtifactName": "codex-agent-promoted-$lane-$reissued",
                    |  "previous": {
                    |    "schemaVersion": 1,
                    |    "source": {
                    |      "event": "pull_request", "runId": 11, "runAttempt": 1, "pullRequest": 7,
                    |      "validationCommit": "$origin", "validationTree": "${"5".repeat(40)}",
                    |      "artifactName": "codex-agent-ci-$lane-${"5".repeat(40)}"
                    |    },
                    |    "sourceTransportArtifactName": "codex-agent-ci-$lane-${"5".repeat(40)}",
                    |    "previous": null
                    |  }
                    |}
                    |""".trimMargin(),
            )
            assertEquals(origin, resolveRuntimeProducerCommit(current, provenance, lane))
            assertEquals(
                TransportProducerIdentity(origin, "5".repeat(40)),
                resolveTransportProducerIdentity(current, "6".repeat(40), provenance, lane),
            )
            assertEquals(current, resolveRuntimeProducerCommit(current, null, lane))
        } finally {
            provenance.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `target consumers stage only their target publications`() {
        val plugin = repository.resolve(
            "gradle/build-logic/src/main/kotlin/codexagent.root-release.gradle.kts",
        ).readText()
        val targetTasks = plugin.substringAfter("val stagedConsumerTasks =")
            .substringBefore("val cleanKmpConsumerResult =")
        assertTrue("dependsOn(inventoryTask)" in targetTasks)
        assertFalse("stageCentralRepository" in targetTasks)
        assertTrue("if (!importedMavenRepository.isPresent)" in targetTasks)
        assertTrue("dependsOn(stagedConsumerPublicationTasks.getValue(target))" in targetTasks)
        assertTrue("maven-inventory-${'$'}target.json" in targetTasks)
        assertTrue("root.dir(\"payload/maven\")" in plugin)
        assertTrue("generateConsumerCommonMavenRelocationPoms" in plugin)
        val commonPublications = plugin.substringAfter("\"common\" to listOf(")
            .substringBefore("\n    ),")
        assertTrue("publicationTask(\"codex-agent-client\", \"KotlinMultiplatform\", \"common\")" in commonPublications)
        assertTrue("publicationTask(\"codex-agent-client\", \"Jvm\", \"common\")" in commonPublications)
        assertFalse("allPublicationTask" in commonPublications)
        listOf(
            "verifyStagedKmpConsumerCommon", "verifyStagedKmpConsumerAndroid",
            "verifyStagedKmpConsumerDesktop", "verifyStagedKmpConsumerIosDevice",
            "verifyStagedKmpConsumerIosSimulator", "verifyStagedKmpConsumerNodeJs",
            "verifyStagedKmpConsumerNodeWasm",
        ).forEach { assertTrue(it in targetTasks, it) }
        val aggregate = plugin.substringAfter(
            "tasks.register<AggregateStagedKmpConsumerTask>(\"verifyStagedKmpConsumer\")",
        ).substringBefore("val centralBundleFile")
        assertTrue("dependsOn(verifyCentralStaging, stagedConsumerTasks.values)" in aggregate)
        val promoted = plugin.substringAfter(
            "tasks.register<AssemblePromotedCandidateTask>(\"assemblePromotedCandidate\")",
        ).substringBefore("tasks.register<VerifyPublicationReadinessTask>")
        assertFalse("dependsOn(" in promoted)
    }

    @Test
    fun `publication revalidates exact candidate bytes before protected mutation`() {
        val publish = workflows.getValue("publish.yml")
        val core = publish.substringAfter("\n  publish-core:").substringBefore("\n  swift-resolution:")
        assertTrue("github.event.workflow_run.conclusion == 'success'" in core)
        assertTrue("github.event.workflow_run.head_repository.full_name == github.repository" in core)
        assertTrue("environment: release-publication" in core)
        assertTrue("test \"${'$'}(git rev-parse 'HEAD^{tree}')\" = \"${'$'}candidate_tree\"" in core)
        val verification = core.indexOf("Revalidate every transported candidate byte and policy")
        val central = core.indexOf("Prepare or recover the exact Central deployment")
        val github = core.indexOf("Create or reuse the exact GitHub release after Central")
        assertTrue(verification in 0 until central && central < github)
        assertFalse("gh release download" in publish)
        assertEquals(1, Regex("(?m)^    environment: release-publication$").findAll(publish).count())
    }

    @Test
    fun `published release assets are checked through official API digests`() {
        val publish = workflows.getValue("publish.yml")
        listOf(
            "${'$'}SWIFT_ASSET|${'$'}PAYLOAD/${'$'}SWIFT_ASSET",
            "${'$'}SWIFT_ASSET.sha256|${'$'}PAYLOAD/${'$'}SWIFT_ASSET.sha256",
            "candidate-manifest.json|${'$'}PAYLOAD/candidate-manifest.json",
            "central-deployment.json|${'$'}RECORD",
        ).forEach { asset -> assertTrue(asset in publish, asset) }
        assertTrue("release_json=${'$'}(gh api" in publish)
        assertTrue("test \"${'$'}(jq '.assets | length' <<<\"${'$'}release_json\")\" -eq 4" in publish)
        assertTrue("test \"${'$'}asset_count\" -eq 1" in publish)
        assertTrue("test \"${'$'}api_digest\" = \"${'$'}expected_digest\"" in publish)
    }

    @Test
    fun `release workflows stay bounded and have one publication path`() {
        val candidate = workflows.getValue("release-candidate.yml")
        val publish = workflows.getValue("publish.yml")
        assertEquals(1, Regex("central-prepare").findAll(publish).count())
        assertEquals(1, Regex("central-release").findAll(publish).count())
        assertEquals(
            "self-hosted-runner:\n  labels:\n    - android\n",
            repository.resolve(".github/actionlint.yaml").readText(),
        )
    }
}
