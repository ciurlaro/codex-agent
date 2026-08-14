import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal fun verifyCandidateManifestStructure(manifest: JsonObject) {
    check(manifest.keys == setOf(
        "schemaVersion", "version", "releaseTag", "candidateCommit", "protectedCandidate", "artifacts", "evidence", "policies",
    )) { "Candidate manifest has unexpected top-level fields" }
    check(manifest.releaseInt("schemaVersion") == 7) { "Candidate manifest schema must be 7" }
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
        "swiftPmProof", "centralBundleInventory", "mavenInventory", "cleanKmpConsumer",
        "desktopRuntime", "nodeRuntime", "nodeRuntimeRunner", "windowsSupervisorIdentity", "iosNative", "privacyAudit",
        "artifactMetrics", "resourceMeasurements",
    )
    check(evidence.keys == expectedEvidence) { "Candidate evidence set is invalid" }
    expectedEvidence.minus(candidateEvidenceArrayNames)
        .forEach { verifyRecordShape(evidence.releaseObject(it)) }
    check(evidence.releaseObject("swiftPmProof").releaseString("fileName") == "swiftpm-proof.json") {
        "Candidate SwiftPM proof file name is invalid"
    }
    check(evidence.releaseObject("artifactMetrics").releaseString("fileName") == "artifact-metrics.json") {
        "Candidate artifact metrics file name is invalid"
    }
    check(evidence.releaseObject("iosNative").releaseString("fileName") == "ios-native-evidence.json") {
        "Candidate iOS native evidence file name is invalid"
    }
    check(evidence.releaseObject("windowsSupervisorIdentity").releaseString("fileName") ==
        WINDOWS_SUPERVISOR_IDENTITY_FILE_NAME) {
        "Candidate Windows supervisor identity file name is invalid"
    }
    check(evidence.releaseObject("nodeRuntimeRunner").releaseString("fileName") ==
        "codex-agent-node-runtime-evidence-runner.zip") {
        "Candidate Node runtime runner file name is invalid"
    }
    val resources = evidence.releaseArray("resourceMeasurements")
    check(resources.isNotEmpty()) { "Candidate resource evidence is missing" }
    resources.forEach { verifyRecordShape(it as? JsonObject ?: error("Invalid resource record")) }
    val desktop = evidence.releaseArray("desktopRuntime")
    check(desktop.size == desktopRuntimeEvidenceTargets.size) { "Candidate desktop evidence set is incomplete" }
    desktop.forEach { verifyRecordShape(it as? JsonObject ?: error("Invalid desktop runtime record")) }
    val node = evidence.releaseArray("nodeRuntime")
    check(node.size == desktopRuntimeEvidenceTargets.size) { "Candidate Node evidence set is incomplete" }
    node.forEach { verifyRecordShape(it as? JsonObject ?: error("Invalid Node runtime record")) }
    check(node.map { (it as JsonObject).releaseString("fileName") }.toSet() ==
        desktopRuntimeEvidenceTargets.keys.map(::nodeRuntimeEvidenceFileName).toSet()) {
        "Candidate Node evidence file set is invalid"
    }
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

internal val candidateEvidenceArrayNames = setOf("desktopRuntime", "nodeRuntime", "resourceMeasurements")

private fun verifyRecordShape(record: JsonObject) {
    val fileName = record.releaseString("fileName")
    check(fileName == File(fileName).name && '/' !in fileName && '\\' !in fileName) { "Unsafe candidate file name" }
    check(record.releaseLong("bytes") >= 0) { "Candidate file size is invalid" }
    check(record.releaseString("sha256").matches(Regex("[0-9a-f]{64}"))) { "Candidate SHA-256 is invalid" }
}
