import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun verifyCandidateManifestStructure(manifest: JsonObject) {
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

internal val candidateEvidenceArrayNames = setOf(
    "desktopRuntime", "jvmRuntime", "nodeRuntime", "nodeWasmRuntime", "androidRuntime",
)

private fun verifyEvidenceArray(evidence: JsonObject, name: String, expectedNames: Set<String>) {
    val records = evidence.releaseArray(name).map {
        (it as? JsonObject ?: error("Invalid $name record")).also(::verifyRecordShape)
    }
    check(records.size == expectedNames.size &&
        records.map { it.releaseString("fileName") }.toSet() == expectedNames) {
        "Candidate $name evidence file set is invalid"
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
