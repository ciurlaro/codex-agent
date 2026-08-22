import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

internal const val PROMOTED_CANDIDATE_SCHEMA = 11
internal const val RELEASE_TOOLING_FILE_NAME = "codex-agent-release-tooling.jar"

private val promotedReceiptKeys = setOf(
    "schemaVersion", "repository", "finalCommit", "finalTree", "validatedCommit", "validatedTree",
    "validationRunId", "validationRunAttempt", "sourcePlanArtifactName", "sourceAggregateArtifactName",
    "promotedAggregateArtifactName", "promotedInventoryArtifactName", "lanes", "workflowPath",
    "promotionRunId", "promotionRunAttempt", "result",
)
private val promotedLaneKeys = setOf(
    "sourceKind", "sourceRunId", "sourceRunAttempt", "sourceArtifactName",
    "sourcePromotionRunId", "sourcePromotionCommit", "promotedArtifactName",
)
private val laneReceiptKeys = setOf(
    "schemaVersion", "repository", "workflowPath", "event", "runId", "runAttempt", "pullRequest",
    "baseCommit", "headCommit", "validationCommit", "validationTree", "lane", "artifactName", "runner",
    "toolchain", "inputFiles", "artifacts", "evidence", "result",
)
private val promotedInputFiles = linkedMapOf(
    "production" to "production-inputs.git-tree",
    "test" to "test-inputs.git-tree",
    "metadata" to "metadata-inputs.git-tree",
)
private val promotedSidecarSuffixes = listOf(".asc", ".md5", ".sha1", ".sha256", ".sha512")
internal val promotedMavenArtifactOwnership = linkedMapOf(
    "common" to setOf(
        "codex-agent-client", "codex-agent-client-jvm",
    ),
    "android" to setOf(
        "codex-agent-client-android", "codex-agent-runtime-android",
    ),
    "desktop" to setOf(
        "codex-agent-client-linuxarm64", "codex-agent-client-linuxx64",
        "codex-agent-client-macosarm64", "codex-agent-client-macosx64", "codex-agent-client-mingwx64",
        "codex-agent-runtime-desktop", "codex-agent-runtime-desktop-jvm",
        "codex-agent-runtime-desktop-linuxarm64", "codex-agent-runtime-desktop-linuxx64",
        "codex-agent-runtime-desktop-macosarm64", "codex-agent-runtime-desktop-macosx64",
        "codex-agent-runtime-desktop-mingwx64",
    ),
    "ios-device" to setOf(
        "codex-agent-client-iosarm64", "codex-agent-runtime-ios", "codex-agent-runtime-ios-iosarm64",
    ),
    "ios-simulator" to setOf(
        "codex-agent-client-iossimulatorarm64", "codex-agent-runtime-ios-iossimulatorarm64",
    ),
    "node-js" to setOf(
        "codex-agent-client-js", "codex-agent-runtime-node", "codex-agent-runtime-node-js",
    ),
    "node-wasm" to setOf(
        "codex-agent-client-wasm-js", "codex-agent-runtime-node-wasm-js",
    ),
)
internal val promotedCandidateLaneNames = setOf(
    "contracts", "portable", "android", "node-js", "node-wasm",
    "desktop-macos-arm64", "desktop-macos-x64", "desktop-linux-arm64", "desktop-linux-x64",
    "desktop-windows-x64", "ios-native-tests", "ios-rust-device", "ios-rust-simulator",
    "ios-framework-device", "ios-framework-simulator", "ios-kotlin-tests", "ios-swift-build",
    "ios-swift-tests", "ios-package", "ios-privacy-metrics", "consumer-common", "consumer-android",
    "consumer-desktop", "consumer-ios-device", "consumer-ios-simulator", "consumer-node-js",
    "consumer-node-wasm",
)
private val impactPlanKeys = setOf(
    "schemaVersion", "event", "repository", "pullRequest", "baseCommit", "headCommit",
    "validationCommit", "validationTree", "mergeReady", "androidEvidenceRequired", "full",
    "unknownPaths", "changedPaths", "lanes",
)
private val impactLaneKeys = setOf("build", "test", "metadata", "reuseAllowed", "reasons")
private val aggregateReceiptKeys = setOf(
    "schemaVersion", "repository", "event", "validationCommit", "validationTree",
    "impactPlan", "lanes", "result",
)
private val aggregateLaneKeys = setOf(
    "runId", "runAttempt", "artifactName", "validationCommit", "validationTree", "result",
)
private val transportProvenanceKeys = setOf("schemaVersion", "source", "sourceTransportArtifactName", "previous")
private val transportSourceKeys = setOf(
    "event", "runId", "runAttempt", "pullRequest", "validationCommit", "validationTree", "artifactName",
)
private val promotedDesktopLanes = linkedMapOf(
    "macosArm64" to "desktop-macos-arm64",
    "macosX64" to "desktop-macos-x64",
    "linuxArm64" to "desktop-linux-arm64",
    "linuxX64" to "desktop-linux-x64",
    "mingwX64" to "desktop-windows-x64",
)

private data class PromotedLane(
    val name: String,
    val root: File,
    val receipt: JsonObject,
) {
    fun files(kind: String): List<File> = listOf("artifacts", "evidence").flatMap { collection ->
        receipt.releaseArray(collection).mapNotNull { value ->
            val item = value as? JsonObject ?: error("Promoted $name $collection entry is invalid")
            if (item.releaseString("kind") == kind) root.resolve(item.releaseString("relativePath")) else null
        }
    }

    fun one(kind: String): File = files(kind).singleOrNull()
        ?: error("Promoted $name must contain exactly one $kind file")
}

private data class BoundRuntimeEvidence(
    val file: File,
    val producerCommit: String,
) {
    fun record() = buildJsonObject {
        file.releaseRecord().forEach { (key, value) -> put(key, value) }
        put("producerCommit", JsonPrimitive(producerCommit))
    }
}

private data class PromotedRuntimeEvidence(
    val desktop: List<BoundRuntimeEvidence>,
    val jvm: List<BoundRuntimeEvidence>,
    val node: List<BoundRuntimeEvidence>,
    val nodeWasm: List<BoundRuntimeEvidence>,
    val android: List<BoundRuntimeEvidence>,
    val jvmRunner: BoundRuntimeEvidence,
    val nodeRunner: BoundRuntimeEvidence,
    val nodeWasmRunner: BoundRuntimeEvidence,
)

private data class BoundProducedEvidence(
    val file: File,
    val producer: TransportProducerIdentity,
) {
    fun record() = buildJsonObject {
        file.releaseRecord().forEach { (key, value) -> put(key, value) }
        put("producerCommit", JsonPrimitive(producer.commit))
        put("producerTree", JsonPrimitive(producer.tree))
    }
}

private data class PromotedRustProof(
    val proof: BoundProducedEvidence,
    val spec: AppleRustSliceSpec,
    val archive: File,
) {
    fun record() = buildJsonObject {
        proof.record().forEach { (key, value) -> put(key, value) }
        put("target", JsonPrimitive(spec.target))
        put("archiveFileName", JsonPrimitive(archive.name))
        put("archiveBytes", JsonPrimitive(archive.length()))
        put("archiveSha256", JsonPrimitive(archive.releaseDigest()))
    }
}

private data class PromotedIosEvidence(
    val nativeTests: BoundProducedEvidence,
    val rustProofs: List<PromotedRustProof>,
    val artifactMetrics: BoundProducedEvidence,
    val runtimeMetrics: BoundProducedEvidence,
)

internal data class TransportProducerIdentity(
    val commit: String,
    val tree: String,
)

internal data class PromotedCandidateInputs(
    val promotedArtifacts: File,
    val signedMavenRepository: File,
    val version: String,
    val releaseTag: String,
    val commit: String,
    val tree: String,
    val promotionRunId: Long,
    val promotionRunAttempt: Int,
    val approvals: File,
    val privacyManifest: File,
    val privacyDataFlowReview: File,
    val privacyReviewTemplate: File,
    val iosResourcePolicy: File,
    val packageSwift: File,
    val remoteConsumerManifest: File,
    val desktopDistributionManifest: File,
    val desktopBundledLicense: File,
    val desktopBundledNotice: File,
    val releaseTooling: File,
    val repository: File,
    val payload: File,
)

internal fun assemblePromotedCandidate(inputs: PromotedCandidateInputs) {
        val version = inputs.version
        val commit = inputs.commit
        val tree = inputs.tree
        check(inputs.releaseTag == "v$version") { "Candidate release tag/version mismatch" }
        check(commit.matches(Regex("[0-9a-f]{40}")) && tree.matches(Regex("[0-9a-f]{40}"))) {
            "Promoted candidate Git identity is not immutable"
        }
        val repository = inputs.repository.canonicalFile
        val payload = inputs.payload.canonicalFile
        check(payload == repository.resolve("build/protected-candidate/$commit/payload").canonicalFile) {
            "Promoted candidate payload must be commit-isolated"
        }
        check(!payload.exists()) { "Promoted candidate payload already exists: $payload" }
        payload.mkdirs()

        val promotionRoot = inputs.promotedArtifacts
        val validationRoot = promotionRoot.resolve("codex-agent-promoted-validation-$commit")
        val validationFiles = safeRegularFiles(validationRoot).associateBy { it.name }
        check(validationFiles.keys == setOf("impact-plan.json", "validation-receipt.json", "promotion-receipt.json")) {
            "Promoted validation artifact has a missing or unexpected file set"
        }
        val promotionReceipt = validationFiles.getValue("promotion-receipt.json").readReleaseObject()
        validatePromotionReceipt(
            promotionReceipt, commit, tree, inputs.promotionRunId, inputs.promotionRunAttempt,
        )
        val impactPlan = validationFiles.getValue("impact-plan.json").readReleaseObject()
        val validationReceipt = validationFiles.getValue("validation-receipt.json").readReleaseObject()
        val lanes = validatePromotedLanes(promotionRoot, promotionReceipt, impactPlan, validationReceipt, commit, tree)
        val declaredReleaseTooling = lanes.getValue("contracts").one("release-tooling")
        check(inputs.releaseTooling.canonicalFile == declaredReleaseTooling.canonicalFile) {
            "Candidate assembly must run the release tool declared by the promoted contracts receipt"
        }
        val releaseTooling = copyToPayload(declaredReleaseTooling, payload, RELEASE_TOOLING_FILE_NAME)

        verifyCanonicalUnsignedPrimaryParity(lanes, inputs.signedMavenRepository, version)
        val signedRepository = inputs.signedMavenRepository
        val mavenInventory = payload.resolve("maven-inventory.json")
        verifyMavenRepository(signedRepository, CodexAgentBuild.MAVEN_GROUP, version, true, mavenInventory)
        verifyPromotedPublicationBytes(lanes, signedRepository, version)
        verifyPromotedConsumers(lanes, version)

        val centralBundle = payload.resolve("codex-agent-$version-central.zip")
        val centralInventory = payload.resolve("central-bundle.json")
        buildCentralBundle(signedRepository, mavenInventory, centralBundle, centralInventory, 1_000_000_000L)
        val runtimeEvidence = copyPromotedRuntimeEvidence(lanes, payload)

        val swiftSource = lanes.getValue("ios-package").one("swift-package-binary")
        check(swiftSource.name == "CodexAgent-$version.xcframework.zip") { "Promoted Swift package name mismatch" }
        val swiftChecksumSource = lanes.getValue("ios-package").one("swiftpm-checksum")
        check(swiftChecksumSource.readText().trim() == swiftSource.releaseDigest()) {
            "Promoted SwiftPM checksum does not bind the promoted ZIP"
        }
        verifyPromotedSwiftMetadata(
            inputs.packageSwift, inputs.remoteConsumerManifest, version, swiftSource,
        )
        val swiftArchive = copyToPayload(swiftSource, payload, swiftSource.name)
        val swiftChecksum = copyToPayload(swiftChecksumSource, payload, "${swiftSource.name}.sha256")
        val iosEvidence = copyPromotedIosEvidence(
            lanes, payload, swiftSource, inputs.iosResourcePolicy,
        )
        val swiftPackageProof = payload.resolve("swift-package-proof.json")
        swiftPackageProof.atomicWriteJson(promotedSwiftPackageProof(
            version,
            inputs.releaseTag,
            commit,
            tree,
            lanes.getValue("ios-package").producerIdentity(),
            swiftArchive,
            swiftChecksum,
            inputs.packageSwift,
        ))

        val privacyLane = lanes.getValue("ios-privacy-metrics")
        val privacyAuditSource = privacyLane.singleFileNamed("audit.json")
        val privacyReviewSource = privacyLane.singleFileNamed("privacy-required-reason-review.json")
        check(privacyAuditSource.readReleaseObject().releaseBoolean("passed")) { "Promoted privacy audit did not pass" }
        verifyBoundIosPrivacyReview(inputs.privacyReviewTemplate, privacyReviewSource, privacyAuditSource)
        val privacyAudit = copyToPayload(privacyAuditSource, payload, "privacy-audit.json")
        val privacyReview = copyToPayload(privacyReviewSource, payload, privacyReviewSource.name)

        val promotionInventory = payload.resolve("promoted-artifact-inventory.json")
        promotionInventory.atomicWriteJson(promotedArtifactInventory(commit, tree, lanes))
        val promotionReceiptFile = copyToPayload(
            validationFiles.getValue("promotion-receipt.json"), payload, "promotion-receipt.json",
        )
        val impactPlanFile = copyToPayload(validationFiles.getValue("impact-plan.json"), payload, "impact-plan.json")
        val validationReceiptFile = copyToPayload(
            validationFiles.getValue("validation-receipt.json"), payload, "validation-receipt.json",
        )

        val policies = linkedMapOf(
            "approvals" to copyToPayload(inputs.approvals, payload),
            "privacyManifest" to copyToPayload(inputs.privacyManifest, payload),
            "privacyDataFlowReview" to copyToPayload(inputs.privacyDataFlowReview, payload),
            "privacyRequiredReasonReviews" to privacyReview,
            "iosResourcePolicy" to copyToPayload(inputs.iosResourcePolicy, payload),
            "packageSwift" to copyToPayload(inputs.packageSwift, payload),
            "desktopDistributionManifest" to copyToPayload(inputs.desktopDistributionManifest, payload),
            "desktopBundledLicense" to copyToPayload(inputs.desktopBundledLicense, payload),
            "desktopBundledNotice" to copyToPayload(inputs.desktopBundledNotice, payload),
        )
        verifyDesktopBundledGplApproval(
            policies.getValue("approvals"), policies.getValue("desktopDistributionManifest"),
            policies.getValue("desktopBundledLicense"), policies.getValue("desktopBundledNotice"),
        )

        val manifest = buildJsonObject {
            put("schemaVersion", JsonPrimitive(PROMOTED_CANDIDATE_SCHEMA))
            put("version", JsonPrimitive(version))
            put("releaseTag", JsonPrimitive(inputs.releaseTag))
            put("candidateCommit", JsonPrimitive(commit))
            put("candidateTree", JsonPrimitive(tree))
            put("protectedCandidate", JsonPrimitive(true))
            put("artifacts", buildJsonObject {
                put("swiftPackage", buildJsonObject {
                    swiftArchive.releaseRecord().forEach { (key, value) -> put(key, value) }
                    put("swiftPmChecksum", JsonPrimitive(swiftArchive.releaseDigest()))
                    put("members", swiftArchive.zipMemberRecords())
                })
                put("centralBundle", centralBundle.releaseRecord())
            })
            put("evidence", buildJsonObject {
                put("swiftPackageChecksum", swiftChecksum.releaseRecord())
                put("centralBundleInventory", centralInventory.releaseRecord())
                put("mavenInventory", mavenInventory.releaseRecord())
                put("promotionReceipt", promotionReceiptFile.releaseRecord())
                put("impactPlan", impactPlanFile.releaseRecord())
                put("validationReceipt", validationReceiptFile.releaseRecord())
                put("promotedArtifactInventory", promotionInventory.releaseRecord())
                put("privacyAudit", privacyAudit.releaseRecord())
                put("releaseTooling", releaseTooling.releaseRecord())
                put("swiftPackageProof", swiftPackageProof.releaseRecord())
                put("iosNativeTests", iosEvidence.nativeTests.record())
                put("iosDeviceRustProof", iosEvidence.rustProofs.single {
                    it.spec.target == IOS_DEVICE_RUST_TARGET
                }.record())
                put("iosSimulatorRustProof", iosEvidence.rustProofs.single {
                    it.spec.target == IOS_SIMULATOR_RUST_TARGET
                }.record())
                put("artifactMetrics", iosEvidence.artifactMetrics.record())
                put("iosRuntimeMetrics", iosEvidence.runtimeMetrics.record())
                put("desktopRuntime", runtimeEvidence.desktop.records())
                put("jvmRuntime", runtimeEvidence.jvm.records())
                put("jvmRuntimeRunner", runtimeEvidence.jvmRunner.record())
                put("nodeRuntime", runtimeEvidence.node.records())
                put("nodeRuntimeRunner", runtimeEvidence.nodeRunner.record())
                put("nodeWasmRuntime", runtimeEvidence.nodeWasm.records())
                put("nodeWasmRuntimeRunner", runtimeEvidence.nodeWasmRunner.record())
                put("androidRuntime", runtimeEvidence.android.records())
            })
            put("policies", buildJsonObject {
                policies.forEach { (name, file) -> put(name, file.releaseRecord()) }
            })
        }
        verifyCandidateManifestStructure(manifest)
        payload.resolve("candidate-manifest.json").atomicWriteJson(manifest)
        verifyCandidatePayload(
            payload.resolve("candidate-manifest.json"), payload, version, inputs.releaseTag, commit, policies,
        )
}

private fun copyPromotedRuntimeEvidence(
    lanes: Map<String, PromotedLane>,
    payload: File,
): PromotedRuntimeEvidence {
    fun copy(lane: PromotedLane, source: File) = BoundRuntimeEvidence(
        copyToPayload(source, payload),
        lane.runtimeProducerCommit(),
    )
    fun desktop(kind: String) = promotedDesktopLanes.values.map { laneName ->
        lanes.getValue(laneName).let { lane -> copy(lane, lane.one(kind)) }
    }

    val portable = lanes.getValue("portable")
    val jvmRunnerSource = portable.one("jvm-runner")
    val nodeRunnerSource = portable.one("node-js-runner")
    val nodeWasmRunnerSource = portable.one("node-wasm-runner")

    val androidLane = lanes.getValue("android")
    val androidSources = androidLane.files("firebase-runtime-evidence")
    check(androidSources.size == candidateFirebaseAndroidEvidenceFileNames.size &&
        androidSources.map(File::getName).toSet() == candidateFirebaseAndroidEvidenceFileNames.toSet()) {
        "Promoted Firebase Android runtime evidence set is incomplete"
    }
    return PromotedRuntimeEvidence(
        desktop = desktop("runtime-evidence"),
        jvm = desktop("jvm-runtime-evidence"),
        node = desktop("node-runtime-evidence"),
        nodeWasm = desktop("node-wasm-runtime-evidence"),
        android = androidSources.sortedBy(File::getName).map { copy(androidLane, it) },
        jvmRunner = copy(portable, jvmRunnerSource),
        nodeRunner = copy(portable, nodeRunnerSource),
        nodeWasmRunner = copy(portable, nodeWasmRunnerSource),
    )
}

private fun PromotedLane.runtimeProducerCommit(): String {
    return producerIdentity().commit
}

private fun PromotedLane.producerIdentity(): TransportProducerIdentity {
    val provenanceFiles = files("transport-provenance")
    check(provenanceFiles.size <= 1) { "Promoted $name has multiple transport provenance chains" }
    return resolveTransportProducerIdentity(
        receipt.releaseString("validationCommit"),
        receipt.releaseString("validationTree"),
        provenanceFiles.singleOrNull(),
        name,
    )
}

internal fun resolveRuntimeProducerCommit(
    receiptCommit: String,
    provenanceFile: File?,
    lane: String,
): String = resolveTransportProducerIdentity(receiptCommit, "0".repeat(40), provenanceFile, lane).commit

internal fun resolveTransportProducerIdentity(
    receiptCommit: String,
    receiptTree: String,
    provenanceFile: File?,
    lane: String,
): TransportProducerIdentity {
    check(receiptCommit.matches(Regex("[0-9a-f]{40}")) && receiptTree.matches(Regex("[0-9a-f]{40}"))) {
        "Promoted $lane receipt producer identity is not immutable"
    }
    if (provenanceFile == null) return TransportProducerIdentity(receiptCommit, receiptTree)
    check(provenanceFile.name == "transport-provenance.json") {
        "Promoted $lane transport provenance file is misnamed"
    }
    var current = provenanceFile.readReleaseObject()
    repeat(100) {
        check(current.keys == transportProvenanceKeys && current.releaseInt("schemaVersion") == 1) {
            "Promoted $lane transport provenance schema is invalid"
        }
        val source = current.releaseObject("source")
        check(source.keys == transportSourceKeys && source.releaseString("event") in setOf("pull_request", "merge_group") &&
            source.releaseLong("runId") > 0 && source.releaseInt("runAttempt") > 0 &&
            source.releaseString("validationCommit").matches(Regex("[0-9a-f]{40}")) &&
            source.releaseString("validationTree").matches(Regex("[0-9a-f]{40}"))) {
            "Promoted $lane transport source identity is invalid"
        }
        listOf(source.releaseString("artifactName"), current.releaseString("sourceTransportArtifactName"))
            .forEach { artifactName ->
                check(artifactName == File(artifactName).name &&
                    (artifactName.startsWith("codex-agent-ci-$lane-") ||
                        artifactName.startsWith("codex-agent-promoted-$lane-"))) {
                    "Promoted $lane transport source artifact is invalid"
                }
            }
        when (val previous = current["previous"]) {
            JsonNull -> return TransportProducerIdentity(
                source.releaseString("validationCommit"),
                source.releaseString("validationTree"),
            )
            is JsonObject -> current = previous
            else -> error("Promoted $lane transport provenance previous value is invalid")
        }
    }
    error("Promoted $lane transport provenance chain is too deep")
}

private fun copyPromotedIosEvidence(
    lanes: Map<String, PromotedLane>,
    payload: File,
    swiftArchive: File,
    resourcePolicy: File,
): PromotedIosEvidence {
    fun copy(lane: PromotedLane, source: File) = BoundProducedEvidence(
        copyToPayload(source, payload),
        lane.producerIdentity(),
    )
    val nativeTestsLane = lanes.getValue("ios-native-tests")
    val nativeTests = copy(nativeTestsLane, nativeTestsLane.one("native-test-proof"))
    val rustProofs = listOf(
        appleRustSliceSpecs[0] to lanes.getValue("ios-rust-device"),
        appleRustSliceSpecs[1] to lanes.getValue("ios-rust-simulator"),
    ).map { (spec, lane) ->
        val archive = lane.one("rust-archive")
        val proof = lane.one("rust-proof")
        check(archive.name == spec.archiveName && proof.name == spec.proofName) {
            "Promoted ${spec.target} Rust evidence file names are invalid"
        }
        verifyStaticArchive(archive)
        verifyReleaseRecord(archive, proof.readReleaseObject().releaseObject("archive"))
        PromotedRustProof(copy(lane, proof), spec, archive)
    }
    val packageLane = lanes.getValue("ios-package")
    val artifactMetrics = copy(packageLane, packageLane.one("ios-package-metrics-input"))
    val metrics = artifactMetrics.file.readReleaseObject()
    check(metrics.keys == setOf(
        "compressedXcframeworkBytes", "deviceFrameworkBytes", "sampleAppInstallBytes",
    ) && metrics.releaseLong("compressedXcframeworkBytes") == swiftArchive.length()) {
        "Promoted iOS artifact metrics do not bind the exact Swift package"
    }
    verifyAppleArtifactBudgets(
        AppleArtifactMetrics(
            metrics.releaseLong("compressedXcframeworkBytes"),
            metrics.releaseLong("deviceFrameworkBytes"),
            metrics.releaseLong("sampleAppInstallBytes"),
        ),
        resourcePolicy,
    )
    val runtimeLane = lanes.getValue("ios-kotlin-tests")
    val runtimeMetrics = copy(runtimeLane, runtimeLane.one("runtime-metrics-evidence"))
    validateIosRuntimeMetrics(runtimeMetrics.file.readReleaseObject())
    return PromotedIosEvidence(nativeTests, rustProofs, artifactMetrics, runtimeMetrics)
}

private fun promotedSwiftPackageProof(
    version: String,
    releaseTag: String,
    candidateCommit: String,
    candidateTree: String,
    producer: TransportProducerIdentity,
    archive: File,
    checksumFile: File,
    packageSwift: File,
) = buildJsonObject {
    put("schemaVersion", JsonPrimitive(1))
    put("protocol", JsonPrimitive("promoted-swift-package-v1"))
    put("result", JsonPrimitive("passed"))
    put("version", JsonPrimitive(version))
    put("releaseTag", JsonPrimitive(releaseTag))
    put("candidateCommit", JsonPrimitive(candidateCommit))
    put("candidateTree", JsonPrimitive(candidateTree))
    put("producerCommit", JsonPrimitive(producer.commit))
    put("producerTree", JsonPrimitive(producer.tree))
    put("packageSwiftUrl", JsonPrimitive(
        "https://github.com/${CodexAgentBuild.REPOSITORY}/releases/download/$releaseTag/${archive.name}",
    ))
    put("swiftPmChecksum", JsonPrimitive(archive.releaseDigest()))
    put("archive", archive.releaseRecord())
    put("checksumFile", checksumFile.releaseRecord())
    put("packageSwift", packageSwift.releaseRecord())
}

private fun List<BoundRuntimeEvidence>.records() = buildJsonArray {
    sortedBy { it.file.name }.forEach { add(it.record()) }
}

private fun validatePromotionReceipt(
    receipt: JsonObject,
    commit: String,
    tree: String,
    runId: Long,
    runAttempt: Int,
) {
    check(receipt.keys == promotedReceiptKeys && receipt.releaseInt("schemaVersion") == 1) {
        "Unsupported or non-exact promotion receipt schema"
    }
    check(receipt.releaseString("repository") == CodexAgentBuild.REPOSITORY &&
        receipt.releaseString("finalCommit") == commit && receipt.releaseString("finalTree") == tree &&
        receipt.releaseString("validatedTree") == tree &&
        receipt.releaseString("workflowPath") == ".github/workflows/promote.yml" &&
        receipt.releaseString("result") == "passed" &&
        receipt.releaseLong("promotionRunId") == runId &&
        receipt.releaseInt("promotionRunAttempt") == runAttempt) { "Promotion receipt identity mismatch" }
    check(receipt.releaseLong("promotionRunId") > 0 && receipt.releaseInt("promotionRunAttempt") > 0 &&
        receipt.releaseLong("validationRunId") > 0 && receipt.releaseInt("validationRunAttempt") > 0) {
        "Promotion run identity is invalid"
    }
    check(receipt.releaseString("promotedAggregateArtifactName") == "codex-agent-promoted-validation-$commit") {
        "Promoted aggregate artifact name mismatch"
    }
    check(receipt.releaseString("promotedInventoryArtifactName") == "codex-agent-promoted-inventories-$commit") {
        "Promoted inventory artifact name mismatch"
    }
}

private fun validatePromotedLanes(
    root: File,
    promotion: JsonObject,
    plan: JsonObject,
    aggregate: JsonObject,
    commit: String,
    tree: String,
): Map<String, PromotedLane> {
    check(plan.keys == impactPlanKeys && plan.releaseInt("schemaVersion") == 1 &&
        plan.releaseString("repository") == CodexAgentBuild.REPOSITORY &&
        plan.releaseString("event") == "merge_group" && plan.releaseString("validationTree") == tree &&
        plan.releaseString("validationCommit") == promotion.releaseString("validatedCommit") &&
        plan.releaseBoolean("mergeReady")) {
        "A merge-ready impact plan is required for release promotion"
    }
    val planned = plan.releaseObject("lanes")
    check(planned.keys == promotedCandidateLaneNames && planned.values.all { value ->
        val state = value.jsonObject
        state.keys == impactLaneKeys && runCatching {
            state.releaseBoolean("build")
            state.releaseBoolean("test")
            state.releaseBoolean("metadata")
            state.releaseBoolean("reuseAllowed")
        }.isSuccess && state["reasons"] is JsonArray
    }) { "Promotion impact plan lane schema is invalid" }
    val activeLanes = planned.filterValues { value ->
        val state = value.jsonObject
        state.releaseBoolean("build") || state.releaseBoolean("test") || state.releaseBoolean("metadata")
    }.keys
    val promotionLanes = promotion.releaseObject("lanes")
    val aggregateLanes = aggregate.releaseObject("lanes")
    check(promotionLanes.keys == promotedCandidateLaneNames && aggregate.keys == aggregateReceiptKeys &&
        aggregateLanes.keys == activeLanes && aggregate.releaseInt("schemaVersion") == 1 &&
        aggregate.releaseString("repository") == CodexAgentBuild.REPOSITORY &&
        aggregate.releaseString("event") == "merge_group" && aggregate.releaseString("validationTree") == tree &&
        aggregate.releaseString("validationCommit") == promotion.releaseString("validatedCommit") &&
        aggregate.releaseString("impactPlan") == "impact-plan.json" &&
        aggregate.releaseString("result") == "passed") { "Promoted lane set or aggregate identity mismatch" }

    val expectedDirectories = promotionLanes.keys.mapTo(mutableSetOf()) { "codex-agent-promoted-$it-$commit" }
    expectedDirectories += "codex-agent-promoted-validation-$commit"
    val actualDirectories = root.listFiles().orEmpty().filter(File::isDirectory).mapTo(mutableSetOf(), File::getName)
    check(actualDirectories == expectedDirectories) { "Promoted artifact directory set mismatch" }
    return promotionLanes.mapValues { (lane, value) ->
        val identity = value.jsonObject
        check(identity.keys == promotedLaneKeys && identity.releaseLong("sourceRunId") > 0 &&
            identity.releaseInt("sourceRunAttempt") > 0 &&
            identity.releaseString("promotedArtifactName") == "codex-agent-promoted-$lane-$commit") {
            "Promoted $lane identity is invalid"
        }
        val laneRoot = root.resolve(identity.releaseString("promotedArtifactName"))
        val files = safeRegularFiles(laneRoot)
        val receiptFile = laneRoot.resolve("lane-receipt.json")
        val receipt = receiptFile.readReleaseObject()
        check(receipt.keys == laneReceiptKeys && receipt.releaseInt("schemaVersion") == 2 &&
            receipt.releaseString("repository") == CodexAgentBuild.REPOSITORY &&
            receipt.releaseString("workflowPath") == ".github/workflows/ci.yml" &&
            receipt.releaseString("event") in setOf("pull_request", "merge_group") &&
            receipt.releaseString("lane") == lane &&
            receipt.releaseString("validationCommit").matches(Regex("[0-9a-f]{40}")) &&
            receipt.releaseString("validationTree").matches(Regex("[0-9a-f]{40}")) &&
            receipt.releaseLong("runId") > 0 && receipt.releaseInt("runAttempt") > 0 &&
            receipt.releaseString("artifactName").startsWith("codex-agent-ci-$lane-") &&
            receipt.releaseString("result") == "passed") {
            "Promoted $lane receipt identity mismatch"
        }
        if (lane in activeLanes) {
            val summary = aggregateLanes.getValue(lane).jsonObject
            check(identity.releaseString("sourceKind") == "validation" &&
                identity["sourcePromotionRunId"] == JsonNull && identity["sourcePromotionCommit"] == JsonNull &&
                receipt.releaseString("validationTree") == tree &&
                receipt.releaseLong("runId") == identity.releaseLong("sourceRunId") &&
                receipt.releaseInt("runAttempt") == identity.releaseInt("sourceRunAttempt") &&
                receipt.releaseString("artifactName") == identity.releaseString("sourceArtifactName") &&
                summary.keys == aggregateLaneKeys && summary.releaseString("result") == "passed" &&
                summary.releaseLong("runId") == receipt.releaseLong("runId") &&
                summary.releaseInt("runAttempt") == receipt.releaseInt("runAttempt") &&
                summary.releaseString("artifactName") == receipt.releaseString("artifactName") &&
                summary.releaseString("validationCommit") == receipt.releaseString("validationCommit") &&
                summary.releaseString("validationTree") == receipt.releaseString("validationTree")) {
                "Promoted active $lane source does not match the current validation aggregate"
            }
        } else {
            val sourceCommit = identity.releaseString("sourcePromotionCommit")
            check(identity.releaseString("sourceKind") == "promotion" && sourceCommit != commit &&
                sourceCommit.matches(Regex("[0-9a-f]{40}")) &&
                identity.releaseLong("sourcePromotionRunId") == identity.releaseLong("sourceRunId") &&
                identity.releaseString("sourceArtifactName") == "codex-agent-promoted-$lane-$sourceCommit") {
                "Promoted carried $lane provenance is invalid"
            }
        }
        val inputFiles = receipt.releaseObject("inputFiles")
        check(inputFiles.keys == promotedInputFiles.keys && promotedInputFiles.all { (name, file) ->
            inputFiles.releaseString(name) == file && laneRoot.resolve(file).isFile
        }) { "Promoted $lane input inventory set mismatch" }
        val declared = mutableSetOf("lane-receipt.json", *promotedInputFiles.values.toTypedArray())
        listOf("artifacts", "evidence").forEach { collection ->
            receipt.releaseArray(collection).forEach { entry ->
                val item = entry as? JsonObject ?: error("Promoted $lane $collection entry is invalid")
                val expectedKeys = if (collection == "artifacts") {
                    setOf("relativePath", "kind", "bytes", "sha256")
                } else {
                    setOf("relativePath", "kind", "sha256")
                }
                check(item.keys == expectedKeys) { "Promoted $lane $collection record is malformed" }
                val relative = item.releaseString("relativePath")
                check(relative.isSafeRelativePath() && declared.add(relative)) { "Unsafe or duplicate $lane path: $relative" }
                val file = laneRoot.resolve(relative)
                check(file.isFile && item.releaseString("sha256") == file.releaseDigest() &&
                    (collection != "artifacts" || file.length() == item.releaseLong("bytes"))) {
                    "Promoted $lane file is missing or integrity-mismatched: $relative"
                }
            }
        }
        val actual = files.mapTo(mutableSetOf()) { it.relativeTo(laneRoot).invariantSeparatorsPath }
        check(actual == declared) { "Promoted $lane file set does not match its receipt" }
        PromotedLane(lane, laneRoot, receipt)
    }
}

internal fun canonicalPromotedMavenOwners(
    ownership: Map<String, Set<String>> = promotedMavenArtifactOwnership,
): Map<String, String> {
    check(ownership.keys == stagedConsumerBuildTasks.keys) { "Canonical Maven owner target set is incomplete" }
    val owners = linkedMapOf<String, String>()
    ownership.forEach { (target, artifactIds) -> artifactIds.forEach { artifactId ->
        check(owners.put(artifactId, target) == null) {
            "Duplicate canonical Maven ownership for $artifactId"
        }
    } }
    val expectedArtifactIds = expectedMavenPrimaryPaths("VERSION").mapTo(sortedSetOf()) {
        it.substringBefore('/')
    }
    check(owners.keys == expectedArtifactIds) { "Canonical Maven artifact ownership set is incomplete" }
    return owners
}

internal fun canonicalPromotedMavenPrimarySources(
    repositories: Map<String, File>,
    version: String,
    ownership: Map<String, Set<String>> = promotedMavenArtifactOwnership,
): Map<String, File> {
    val owners = canonicalPromotedMavenOwners(ownership)
    check(repositories.keys == ownership.keys) { "Promoted consumer Maven repository set is incomplete" }
    val groupPrefix = "${CodexAgentBuild.MAVEN_GROUP.replace('.', '/')}/"
    val relocationPrefix = "${OLD_MAVEN_GROUP.replace('.', '/')}/"
    val sources = linkedMapOf<String, File>()
    repositories.forEach { (target, repository) -> safeRegularFiles(repository).forEach { file ->
        if (promotedSidecarSuffixes.any(file.name::endsWith)) return@forEach
        if (centralExclusion(file) != null) return@forEach
        val relative = file.relativeTo(repository).invariantSeparatorsPath
        check(relative.isSafeRelativePath()) { "Unsafe promoted $target Maven path: $relative" }
        val artifactId = when {
            relative.startsWith(groupPrefix) -> relative.removePrefix(groupPrefix).substringBefore('/')
            relative.startsWith(relocationPrefix) -> relative.removePrefix(relocationPrefix).substringBefore('/')
            else -> error("Unexpected promoted Maven path: $relative")
        }
        val owner = if (relative.startsWith(groupPrefix)) {
            owners[artifactId] ?: error("Canonical Maven owner is missing for $artifactId")
        } else {
            check(artifactId in mavenRelocationArtifactIds) { "Unexpected Maven relocation: $artifactId" }
            "common"
        }
        if (target == owner) {
            check(sources.put(relative, file) == null) { "Duplicate canonical Maven ownership for $relative" }
        }
    } }
    val expectedPaths = expectedMavenPrimaryPaths(version).mapTo(sortedSetOf()) { "$groupPrefix$it" } +
        expectedMavenRelocationPaths(version)
    check(sources.keys == expectedPaths) { "Canonical promoted Maven primary set is incomplete" }
    return sources
}

internal fun stageCanonicalPromotedMavenPrimaries(
    promotedArtifacts: File,
    commit: String,
    version: String,
    output: File,
) {
    check(commit.matches(Regex("[0-9a-f]{40}"))) { "Promoted Maven commit is not immutable" }
    check(!output.exists() || output.listFiles().isNullOrEmpty()) { "Canonical Maven output is not empty: $output" }
    check(!output.canonicalFile.toPath().startsWith(promotedArtifacts.canonicalFile.toPath())) {
        "Canonical Maven output must be outside promoted artifacts"
    }
    val repositories = promotedMavenArtifactOwnership.keys.associateWith { target ->
        promotedArtifacts.resolve("codex-agent-promoted-consumer-$target-$commit/payload/maven")
    }
    val sources = canonicalPromotedMavenPrimarySources(repositories, version)
    check(output.mkdirs() || output.isDirectory) { "Cannot create canonical Maven output: $output" }
    sources.toSortedMap().forEach { (relative, source) ->
        val destination = output.resolve(relative)
        check(destination.parentFile.mkdirs() || destination.parentFile.isDirectory) {
            "Cannot create canonical Maven directory: ${destination.parentFile}"
        }
        Files.copy(source.toPath(), destination.toPath())
    }
}

private fun verifyCanonicalUnsignedPrimaryParity(
    lanes: Map<String, PromotedLane>,
    signed: File,
    version: String,
) {
    val repositories = promotedMavenArtifactOwnership.keys.associateWith { target ->
        lanes.getValue("consumer-$target").root.resolve("payload/maven")
    }
    val canonicalRecords = canonicalPromotedMavenPrimarySources(repositories, version)
        .mapValues { (_, file) -> file.releaseDigest() }
    val signedFiles = safeRegularFiles(signed)
    val signedRecords = signedFiles.associate { it.relativeTo(signed).invariantSeparatorsPath to it.releaseDigest() }
    val expectedSignedPaths = buildSet {
        addAll(canonicalRecords.keys)
        canonicalRecords.keys.forEach { add("$it.asc") }
    }
    check(signedRecords.keys == expectedSignedPaths) { "Signed Maven repository has a missing or unexpected file" }
    check(canonicalRecords.all { (path, digest) -> signedRecords[path] == digest }) {
        "Signed Maven repository changed canonical promoted primary bytes"
    }
    val signatures = signedRecords.keys.filterTo(sortedSetOf()) { it.endsWith(".asc") }
    check(signatures == canonicalRecords.keys.mapTo(sortedSetOf()) { "$it.asc" }) {
        "Every promoted Maven primary must have exactly one signature"
    }
}

private fun verifyPromotedPublicationBytes(lanes: Map<String, PromotedLane>, maven: File, version: String) {
    val android = lanes.getValue("android").one("aar")
    val mavenAndroid = maven.resolve(
        "${CodexAgentBuild.MAVEN_GROUP.replace('.', '/')}/codex-agent-runtime-android/$version/" +
            "codex-agent-runtime-android-$version.aar",
    )
    check(android.releaseDigest() == mavenAndroid.releaseDigest()) {
        "Promoted Android AAR and Maven publication differ"
    }
    val desktop = lanes.filterKeys { it.startsWith("desktop-") }.values.map { it.one("classifier") }
    check(desktop.size == desktopRuntimeEvidenceTargets.size) { "Promoted desktop classifier set is incomplete" }
    val mavenDesktop = maven.resolve(
        "${CodexAgentBuild.MAVEN_GROUP.replace('.', '/')}/codex-agent-runtime-desktop/$version",
    )
    desktop.forEach { classifier ->
        check(classifier.releaseDigest() == mavenDesktop.resolve(classifier.name).releaseDigest()) {
            "Promoted desktop classifier and Maven publication differ: ${classifier.name}"
        }
    }
}

private fun verifyPromotedSwiftMetadata(packageSwift: File, remoteConsumer: File, version: String, archive: File) {
    val expectedUrl = "https://github.com/${CodexAgentBuild.REPOSITORY}/releases/download/v$version/${archive.name}"
    val packageContents = packageSwift.readText()
    val urls = Regex("""url\s*:\s*"([^"]+)"""").findAll(packageContents).map { it.groupValues[1] }.toList()
    val checksums = Regex("""checksum\s*:\s*"([0-9a-f]{64})"""")
        .findAll(packageContents).map { it.groupValues[1] }.toList()
    check(urls == listOf(expectedUrl) && checksums == listOf(archive.releaseDigest())) {
        "Package.swift does not bind the promoted Swift archive"
    }
    val repositoryUrl = Regex.escape("https://github.com/${CodexAgentBuild.REPOSITORY}.git")
    val remoteVersion = Regex(
        """\.package\s*\(\s*url\s*:\s*"$repositoryUrl"\s*,\s*exact\s*:\s*"([^"]+)"\s*\)""",
        RegexOption.DOT_MATCHES_ALL,
    ).find(remoteConsumer.readText())?.groupValues?.get(1)
    check(remoteVersion == version) { "RemoteConsumer exact dependency version must equal $version" }
}

private fun verifyPromotedConsumers(lanes: Map<String, PromotedLane>, version: String) {
    val targets = lanes.filterKeys { it.startsWith("consumer-") }.values.map { lane ->
        val report = lane.one("consumer-report").readReleaseObject()
        val target = report.releaseString("target")
        check(report.keys == setOf(
            "schemaVersion", "result", "version", "repository", "mavenGroup", "target", "tasks",
        ) && report.releaseInt("schemaVersion") == 5 && report.releaseString("result") == "passed" &&
            report.releaseString("version") == version && report.releaseString("repository") == "CENTRAL_STAGING-only" &&
            report.releaseString("mavenGroup") == CodexAgentBuild.MAVEN_GROUP &&
            report.releaseArray("tasks").map { (it as? JsonPrimitive)?.content } ==
            stagedConsumerBuildTasks.getValue(target)) {
            "Promoted ${lane.name} consumer did not pass for $version"
        }
        val repository = lane.root.resolve("payload/maven")
        val inventory = lane.one("maven-inventory").readReleaseObject()
        check(inventory.keys == setOf("schemaVersion", "groupId", "version", "target", "files") &&
            inventory.releaseInt("schemaVersion") == 1 &&
            inventory.releaseString("groupId") == CodexAgentBuild.MAVEN_GROUP &&
            inventory.releaseString("version") == version && inventory.releaseString("target") == target) {
            "Promoted $target Maven inventory identity is invalid"
        }
        val inventoryEntries = inventory.releaseArray("files")
        val records = inventoryEntries.associate { value ->
            val record = value as? JsonObject ?: error("Promoted $target Maven inventory entry is invalid")
            check(record.keys == setOf("fileName", "bytes", "sha256")) {
                "Promoted $target Maven inventory entry schema is invalid"
            }
            val relative = record.releaseString("fileName")
            check(relative.isSafeRelativePath()) { "Unsafe promoted $target Maven path: $relative" }
            relative to record
        }
        check(records.size == inventoryEntries.size) { "Promoted $target Maven inventory has duplicate paths" }
        val files = safeRegularFiles(repository).associateBy { it.relativeTo(repository).invariantSeparatorsPath }
        check(records.keys == files.keys && records.all { (relative, record) ->
            val file = files.getValue(relative)
            record.releaseLong("bytes") == file.length() && record.releaseString("sha256") == file.releaseDigest()
        }) { "Promoted $target Maven inventory does not bind its repository" }
        target
    }.toSet()
    check(targets == stagedConsumerBuildTasks.keys) { "Promoted consumer target set is incomplete" }
}

private fun promotedArtifactInventory(
    commit: String,
    tree: String,
    lanes: Map<String, PromotedLane>,
) = buildJsonObject {
    put("schemaVersion", JsonPrimitive(1))
    put("repository", JsonPrimitive(CodexAgentBuild.REPOSITORY))
    put("finalCommit", JsonPrimitive(commit))
    put("finalTree", JsonPrimitive(tree))
    put("lanes", buildJsonArray {
        lanes.toSortedMap().forEach { (lane, value) -> add(buildJsonObject {
            put("lane", JsonPrimitive(lane))
            put("artifactName", JsonPrimitive("codex-agent-promoted-$lane-$commit"))
            put("files", buildJsonArray {
                safeRegularFiles(value.root).sortedBy { it.relativeTo(value.root).invariantSeparatorsPath }.forEach { file ->
                    add(file.releaseRecord(file.relativeTo(value.root).invariantSeparatorsPath))
                }
            })
        }) }
    })
}

private fun PromotedLane.singleFileNamed(name: String): File = safeRegularFiles(root)
    .filter { it.name == name }.singleOrNull() ?: error("Promoted $this must contain exactly one $name")

private fun safeRegularFiles(root: File): List<File> {
    check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Promoted directory is missing or unsafe: $root" }
    val entries = Files.walk(root.toPath()).use { paths -> paths.toList() }
    entries.filter { it != root.toPath() }.forEach { path ->
        check(!Files.isSymbolicLink(path) && (Files.isDirectory(path) || Files.isRegularFile(path))) {
            "Promoted artifact contains an unsafe entry: $path"
        }
    }
    return entries.filter(Files::isRegularFile).map { it.toFile() }
}

private fun String.isSafeRelativePath(): Boolean {
    val path = File(this)
    return isNotBlank() && !path.isAbsolute && '\\' !in this && split('/').none { it.isBlank() || it == "." || it == ".." }
}

private fun copyToPayload(source: File, payload: File, name: String = source.name): File {
    check(name == File(name).name && name !in setOf("", ".", "..") && '/' !in name && '\\' !in name) {
        "Unsafe promoted candidate file name: $name"
    }
    val output = payload.resolve(name)
    check(!output.exists()) { "Duplicate promoted candidate file name: $name" }
    Files.copy(source.toPath(), output.toPath(), REPLACE_EXISTING)
    return output
}
