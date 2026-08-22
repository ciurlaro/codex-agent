import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun verifyCandidateManifestStructure(manifest: JsonObject) {
    when (manifest.releaseInt("schemaVersion")) {
        9 -> verifyLegacyCandidateManifestStructure(manifest)
        PROMOTED_CANDIDATE_SCHEMA -> verifyPromotedCandidateManifestStructure(manifest)
        else -> error("Unsupported candidate manifest schema")
    }
}

private fun verifyLegacyCandidateManifestStructure(manifest: JsonObject) {
    check(manifest.keys == setOf(
        "schemaVersion", "version", "releaseTag", "candidateCommit", "protectedCandidate",
        "artifacts", "evidence", "policies",
    )) { "Candidate manifest has unexpected top-level fields" }
    check(manifest.releaseInt("schemaVersion") == 9) { "Candidate manifest schema must be 9" }
    val version = manifest.releaseString("version")
    check(manifest.releaseString("releaseTag") == "v$version") { "Candidate release tag/version mismatch" }
    check(manifest.releaseString("candidateCommit").matches(Regex("[0-9a-f]{40}"))) {
        "Candidate commit is not immutable"
    }
    check(manifest.releaseBoolean("protectedCandidate")) { "Candidate is not technically protected" }

    val artifacts = manifest.releaseObject("artifacts")
    check(artifacts.keys == setOf("swiftPackage", "centralBundle")) { "Candidate artifact set is invalid" }
    val swift = artifacts.releaseObject("swiftPackage")
    verifyRecordShape(swift)
    check(swift.releaseString("swiftPmChecksum") == swift.releaseString("sha256")) {
        "SwiftPM checksum and ZIP SHA-256 differ"
    }
    check(swift["members"] is JsonArray) { "SwiftPM member inventory is missing" }
    verifyRecordShape(artifacts.releaseObject("centralBundle"))

    val evidence = manifest.releaseObject("evidence")
    val expectedEvidence = setOf(
        "swiftPmProof", "centralBundleInventory", "mavenInventory", "cleanKmpConsumer", "ciProvenance",
        "desktopRuntime", "jvmRuntime", "jvmRuntimeRunner", "nodeRuntime", "nodeRuntimeRunner",
        "nodeWasmRuntime", "nodeWasmRuntimeRunner", "androidRuntime", "iosNative", "privacyAudit",
        "artifactMetrics", "iosRuntimeMetrics",
    )
    check(evidence.keys == expectedEvidence) { "Candidate evidence set is invalid" }
    expectedEvidence.minus(candidateEvidenceArrayNames)
        .forEach { verifyRecordShape(evidence.releaseObject(it)) }
    mapOf(
        "swiftPmProof" to "swiftpm-proof.json",
        "ciProvenance" to CANDIDATE_CI_PROVENANCE_FILE,
        "artifactMetrics" to "artifact-metrics.json",
        "iosRuntimeMetrics" to "runtime-metrics.json",
        "iosNative" to "ios-native-evidence.json",
        "jvmRuntimeRunner" to JVM_RUNTIME_RUNNER_ARCHIVE,
        "nodeRuntimeRunner" to NODE_RUNTIME_RUNNER_ARCHIVE,
        "nodeWasmRuntimeRunner" to NODE_WASM_RUNTIME_RUNNER_ARCHIVE,
    ).forEach { (field, fileName) ->
        check(evidence.releaseObject(field).releaseString("fileName") == fileName) {
            "Candidate $field file name is invalid"
        }
    }
    verifyEvidenceArray(
        evidence, "desktopRuntime", desktopRuntimeEvidenceTargets.keys.map(::desktopRuntimeEvidenceFileName).toSet(),
    )
    verifyEvidenceArray(
        evidence, "jvmRuntime", desktopRuntimeEvidenceTargets.keys.map(::jvmRuntimeEvidenceFileName).toSet(),
    )
    verifyEvidenceArray(
        evidence, "nodeRuntime",
        desktopRuntimeEvidenceTargets.keys.map { nodeRuntimeEvidenceFileName(it, NODE_RUNTIME_JS_BACKEND) }.toSet(),
    )
    verifyEvidenceArray(
        evidence, "nodeWasmRuntime",
        desktopRuntimeEvidenceTargets.keys.map { nodeRuntimeEvidenceFileName(it, NODE_RUNTIME_WASM_BACKEND) }.toSet(),
    )
    verifyEvidenceArray(evidence, "androidRuntime", candidateFirebaseAndroidEvidenceFileNames.toSet())
    val policies = manifest.releaseObject("policies")
    val requiredPolicies = setOf(
        "approvals", "privacyManifest", "privacyDataFlowReview", "packageSwift",
        "desktopDistributionManifest", "desktopBundledLicense", "desktopBundledNotice",
    )
    check(policies.keys == requiredPolicies || policies.keys == requiredPolicies + "privacyRequiredReasonReviews") {
        "Candidate policy set is invalid"
    }
    policies.values.forEach { verifyRecordShape(it as? JsonObject ?: error("Invalid candidate policy record")) }
}

internal fun promotedCentralBundleRecords(manifest: JsonObject): List<JsonObject> =
    manifest.releaseObject("artifacts").releaseArray("centralBundles").map { value ->
        value as? JsonObject ?: error("Invalid promoted Central bundle record")
    }

internal fun promotedCentralBundleRecord(manifest: JsonObject, shard: String): JsonObject {
    val fileName = centralBundleFileName(manifest.releaseString("version"), shard)
    return promotedCentralBundleRecords(manifest).single { it.releaseString("fileName") == fileName }
}

private fun verifyPromotedCandidateManifestStructure(manifest: JsonObject) {
    check(manifest.keys == setOf(
        "schemaVersion", "version", "releaseTag", "candidateCommit", "candidateTree", "protectedCandidate",
        "artifacts", "evidence", "policies",
    )) { "Promoted candidate manifest has unexpected top-level fields" }
    val version = manifest.releaseString("version")
    check(manifest.releaseString("releaseTag") == "v$version") { "Candidate release tag/version mismatch" }
    listOf("candidateCommit", "candidateTree").forEach { field ->
        check(manifest.releaseString(field).matches(Regex("[0-9a-f]{40}"))) {
            "Promoted candidate $field is not immutable"
        }
    }
    check(manifest.releaseBoolean("protectedCandidate")) { "Candidate is not technically protected" }

    val artifacts = manifest.releaseObject("artifacts")
    check(artifacts.keys == setOf("swiftPackage", "centralBundles")) { "Candidate artifact set is invalid" }
    val swift = artifacts.releaseObject("swiftPackage")
    verifyRecordShape(swift)
    check(swift.releaseString("swiftPmChecksum") == swift.releaseString("sha256") && swift["members"] is JsonArray) {
        "Promoted Swift package metadata is invalid"
    }
    val centralBundles = promotedCentralBundleRecords(manifest)
    check(centralBundles.size == centralBundleShardNames.size &&
        centralBundles.map { it.releaseString("fileName") }.toSet() ==
        centralBundleShardNames.map { centralBundleFileName(version, it) }.toSet()) {
        "Promoted Central bundle set is invalid"
    }
    centralBundles.forEach(::verifyRecordShape)

    val evidence = manifest.releaseObject("evidence")
    val expectedEvidence = setOf(
        "swiftPackageChecksum", "centralBundleInventory", "mavenInventory", "promotionReceipt",
        "impactPlan", "validationReceipt", "promotedArtifactInventory", "privacyAudit", "desktopRuntime",
        "jvmRuntime", "jvmRuntimeRunner", "nodeRuntime", "nodeRuntimeRunner", "nodeWasmRuntime",
        "nodeWasmRuntimeRunner", "androidRuntime", "swiftPackageProof", "iosNativeTests",
        "iosDeviceRustProof", "iosSimulatorRustProof", "artifactMetrics", "iosRuntimeMetrics", "releaseTooling",
    )
    check(evidence.keys == expectedEvidence) { "Promoted candidate evidence set is invalid" }
    expectedEvidence.minus(candidateEvidenceArrayNames).forEach { name ->
        val record = evidence.releaseObject(name)
        when (name) {
            "jvmRuntimeRunner", "nodeRuntimeRunner", "nodeWasmRuntimeRunner" ->
                verifyPromotedRuntimeRecordShape(record)
            "iosNativeTests", "artifactMetrics", "iosRuntimeMetrics" ->
                verifyPromotedProducedRecordShape(record)
            "iosDeviceRustProof", "iosSimulatorRustProof" -> verifyPromotedRustProofRecordShape(record)
            else -> verifyRecordShape(record)
        }
    }
    mapOf(
        "swiftPackageChecksum" to "CodexAgent-$version.xcframework.zip.sha256",
        "centralBundleInventory" to "central-bundle.json",
        "mavenInventory" to "maven-inventory.json",
        "promotionReceipt" to "promotion-receipt.json",
        "impactPlan" to "impact-plan.json",
        "validationReceipt" to "validation-receipt.json",
        "promotedArtifactInventory" to "promoted-artifact-inventory.json",
        "privacyAudit" to "privacy-audit.json",
        "jvmRuntimeRunner" to JVM_RUNTIME_RUNNER_ARCHIVE,
        "nodeRuntimeRunner" to NODE_RUNTIME_RUNNER_ARCHIVE,
        "nodeWasmRuntimeRunner" to NODE_WASM_RUNTIME_RUNNER_ARCHIVE,
        "swiftPackageProof" to "swift-package-proof.json",
        "iosNativeTests" to IOS_NATIVE_TESTS_PROOF,
        "iosDeviceRustProof" to appleRustSliceSpecs[0].proofName,
        "iosSimulatorRustProof" to appleRustSliceSpecs[1].proofName,
        "artifactMetrics" to "artifact-metrics.json",
        "iosRuntimeMetrics" to "runtime-metrics.json",
        "releaseTooling" to RELEASE_TOOLING_FILE_NAME,
    ).forEach { (field, name) ->
        check(evidence.releaseObject(field).releaseString("fileName") == name) {
            "Promoted candidate $field file name is invalid"
        }
    }
    verifyEvidenceArray(
        evidence, "desktopRuntime", desktopRuntimeEvidenceTargets.keys.map(::desktopRuntimeEvidenceFileName).toSet(),
        requireValidationCommit = true,
    )
    verifyEvidenceArray(
        evidence, "jvmRuntime", desktopRuntimeEvidenceTargets.keys.map(::jvmRuntimeEvidenceFileName).toSet(),
        requireValidationCommit = true,
    )
    verifyEvidenceArray(
        evidence, "nodeRuntime",
        desktopRuntimeEvidenceTargets.keys.map { nodeRuntimeEvidenceFileName(it, NODE_RUNTIME_JS_BACKEND) }.toSet(),
        requireValidationCommit = true,
    )
    verifyEvidenceArray(
        evidence, "nodeWasmRuntime",
        desktopRuntimeEvidenceTargets.keys.map { nodeRuntimeEvidenceFileName(it, NODE_RUNTIME_WASM_BACKEND) }.toSet(),
        requireValidationCommit = true,
    )
    verifyEvidenceArray(
        evidence, "androidRuntime", candidateFirebaseAndroidEvidenceFileNames.toSet(),
        requireValidationCommit = true,
    )

    val policies = manifest.releaseObject("policies")
    check(policies.keys == setOf(
        "approvals", "privacyManifest", "privacyDataFlowReview", "privacyRequiredReasonReviews",
        "iosResourcePolicy", "packageSwift", "desktopDistributionManifest", "desktopBundledLicense",
        "desktopBundledNotice",
    )) { "Promoted candidate policy set is invalid" }
    policies.values.forEach { verifyRecordShape(it as? JsonObject ?: error("Invalid candidate policy record")) }
}

private fun verifyPromotedProducedRecordShape(record: JsonObject) {
    check(record.keys == setOf("fileName", "bytes", "sha256", "producerCommit", "producerTree")) {
        "Promoted produced-evidence record schema is invalid"
    }
    verifyRecordShape(record)
    listOf("producerCommit", "producerTree").forEach { field ->
        check(record.releaseString(field).matches(Regex("[0-9a-f]{40}"))) {
            "Promoted produced-evidence $field is not immutable"
        }
    }
}

private fun verifyPromotedRustProofRecordShape(record: JsonObject) {
    check(record.keys == setOf(
        "fileName", "bytes", "sha256", "producerCommit", "producerTree", "target",
        "archiveFileName", "archiveBytes", "archiveSha256",
    )) { "Promoted Rust proof record schema is invalid" }
    verifyPromotedProducedRecordShape(JsonObject(record.filterKeys {
        it in setOf("fileName", "bytes", "sha256", "producerCommit", "producerTree")
    }))
    check(record.releaseString("target") in appleRustSliceSpecs.map(AppleRustSliceSpec::target) &&
        record.releaseString("archiveFileName") == File(record.releaseString("archiveFileName")).name &&
        record.releaseLong("archiveBytes") > 8 &&
        record.releaseString("archiveSha256").matches(Regex("[0-9a-f]{64}"))) {
        "Promoted Rust proof archive binding is invalid"
    }
}

internal val candidateEvidenceArrayNames = setOf(
    "desktopRuntime", "jvmRuntime", "nodeRuntime", "nodeWasmRuntime", "androidRuntime",
)

private fun verifyEvidenceArray(
    evidence: JsonObject,
    name: String,
    expectedNames: Set<String>,
    requireValidationCommit: Boolean = false,
) {
    val records = evidence.releaseArray(name).map {
        (it as? JsonObject ?: error("Invalid $name record")).also { record ->
            if (requireValidationCommit) verifyPromotedRuntimeRecordShape(record) else verifyRecordShape(record)
        }
    }
    check(records.size == expectedNames.size &&
        records.map { it.releaseString("fileName") }.toSet() == expectedNames) {
        "Candidate $name evidence file set is invalid"
    }
}

private fun verifyPromotedRuntimeRecordShape(record: JsonObject) {
    check(record.keys == setOf("fileName", "bytes", "sha256", "producerCommit")) {
        "Promoted runtime evidence record schema is invalid"
    }
    verifyRecordShape(record)
    check(record.releaseString("producerCommit").matches(Regex("[0-9a-f]{40}"))) {
        "Promoted runtime evidence producer commit is not immutable"
    }
}

private fun verifyRecordShape(record: JsonObject) {
    val fileName = record.releaseString("fileName")
    check(fileName == File(fileName).name && '/' !in fileName && '\\' !in fileName) {
        "Unsafe candidate file name"
    }
    check(record.releaseLong("bytes") >= 0) { "Candidate file size is invalid" }
    check(record.releaseString("sha256").matches(Regex("[0-9a-f]{64}"))) {
        "Candidate SHA-256 is invalid"
    }
}
