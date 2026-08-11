import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal data class CandidateInputFiles(
    val version: String,
    val releaseTag: String,
    val commit: String,
    val swiftZip: File,
    val swiftChecksum: File,
    val swiftPmAbProof: File,
    val centralBundle: File,
    val centralInventory: File,
    val mavenInventory: File,
    val kmpConsumer: File,
    val androidEvidence: File,
    val privacyAudit: File,
    val artifactMetrics: File,
    val resourceReports: List<File>,
    val approvals: File,
    val privacyManifest: File,
    val privacyDataFlowReview: File,
    val privacyRequiredReasonReviews: File?,
    val packageSwift: File,
)

internal fun buildCandidateManifest(input: CandidateInputFiles): JsonObject {
    check(input.releaseTag == "v${input.version}") { "Candidate release tag must equal v${input.version}" }
    check(input.commit.matches(Regex("[0-9a-f]{40}"))) { "Candidate commit is not immutable" }
    val swiftHash = input.swiftZip.releaseDigest()
    check(input.swiftChecksum.readText().trim() == swiftHash) { "SwiftPM checksum does not match the candidate ZIP" }
    check(input.swiftPmAbProof.name == "swiftpm-ab-proof.json" && input.swiftPmAbProof.isFile) {
        "SwiftPM A/B proof is missing or has the wrong file name"
    }
    input.swiftPmAbProof.readReleaseObject()

    val central = input.centralInventory.readReleaseObject()
    check(central.releaseBoolean("belowCentralPortalUploadLimit")) { "Central bundle exceeds the Portal limit" }
    verifyReleaseRecord(input.centralBundle, central.releaseObject("bundle"))
    check(central.releaseString("mavenInventorySha256") == input.mavenInventory.releaseDigest()) {
        "Central inventory does not bind the Maven inventory"
    }
    val maven = input.mavenInventory.readReleaseObject()
    check(maven.releaseString("version") == input.version) { "Maven inventory version mismatch" }
    check(maven.releaseInt("primaryArtifactCount") == 53) { "Maven inventory must contain 53 primary artifacts" }
    val consumer = input.kmpConsumer.readReleaseObject()
    check(consumer.releaseString("result") == "passed") { "Clean KMP consumer did not pass" }
    check(consumer.releaseString("version") == input.version) { "Clean KMP consumer version mismatch" }
    check(consumer.releaseString("mavenInventorySha256") == input.mavenInventory.releaseDigest()) {
        "Clean KMP consumer does not bind the Maven inventory"
    }
    val privacyAudit = input.privacyAudit.readReleaseObject()
    check(privacyAudit.releaseBoolean("passed")) { "Static privacy audit did not pass" }
    val privacyReviewHash = input.privacyRequiredReasonReviews?.releaseDigest()
    check(privacyAudit.releaseStringOrNull("reviewSha256") == privacyReviewHash) {
        "Static privacy audit does not bind the supplied required-reason review"
    }
    check(input.artifactMetrics.name == "artifact-metrics.json" && input.artifactMetrics.isFile) {
        "Artifact metrics are required"
    }
    input.artifactMetrics.readReleaseObject()
    check(input.androidEvidence.isFile) { "Android runtime evidence is required" }
    val androidErrors = validateAndroidEvidence(input.androidEvidence, input.commit)
    check(androidErrors.isEmpty()) { "Android runtime evidence is invalid: ${androidErrors.joinToString()}" }
    check(input.resourceReports.isNotEmpty()) { "At least one resource evidence report is required" }
    input.resourceReports.forEach { report ->
        check(report.readReleaseObject().releaseInt("exitCode") == 0) { "Resource phase did not pass: ${report.name}" }
    }
    val evidenceNames = listOf(
        input.swiftPmAbProof,
        input.centralInventory,
        input.mavenInventory,
        input.kmpConsumer,
        input.androidEvidence,
        input.privacyAudit,
        input.artifactMetrics,
    ) + input.resourceReports
    check(evidenceNames.map(File::getName).toSet().size == evidenceNames.size) {
        "Candidate evidence file names must be unique"
    }

    val manifest = buildJsonObject {
        put("schemaVersion", JsonPrimitive(2))
        put("version", JsonPrimitive(input.version))
        put("releaseTag", JsonPrimitive(input.releaseTag))
        put("candidateCommit", JsonPrimitive(input.commit))
        put("protectedCandidate", JsonPrimitive(true))
        put("artifacts", buildJsonObject {
            put("swiftPackage", buildJsonObject {
                input.swiftZip.releaseRecord().forEach { (key, value) -> put(key, value) }
                put("swiftPmChecksum", JsonPrimitive(swiftHash))
                put("members", input.swiftZip.zipMemberRecords())
            })
            put("centralBundle", input.centralBundle.releaseRecord())
        })
        put("evidence", buildJsonObject {
            put("swiftPmAbProof", input.swiftPmAbProof.releaseRecord())
            put("centralBundleInventory", input.centralInventory.releaseRecord())
            put("mavenInventory", input.mavenInventory.releaseRecord())
            put("cleanKmpConsumer", input.kmpConsumer.releaseRecord())
            put("androidRuntime", input.androidEvidence.releaseRecord())
            put("privacyAudit", input.privacyAudit.releaseRecord())
            put("artifactMetrics", input.artifactMetrics.releaseRecord())
            put("resourceMeasurements", buildJsonArray {
                input.resourceReports.sortedBy(File::getName).forEach { add(it.releaseRecord()) }
            })
        })
        put("policies", buildJsonObject {
            put("approvals", input.approvals.releaseRecord())
            put("privacyManifest", input.privacyManifest.releaseRecord())
            put("privacyDataFlowReview", input.privacyDataFlowReview.releaseRecord())
            input.privacyRequiredReasonReviews?.let {
                put("privacyRequiredReasonReviews", it.releaseRecord())
            }
            put("packageSwift", input.packageSwift.releaseRecord())
        })
    }
    verifyCandidateManifestStructure(manifest)
    return manifest
}

internal fun verifyCandidateManifestStructure(manifest: JsonObject) {
    check(manifest.keys == setOf(
        "schemaVersion", "version", "releaseTag", "candidateCommit", "protectedCandidate", "artifacts", "evidence", "policies",
    )) { "Candidate manifest has unexpected top-level fields" }
    check(manifest.releaseInt("schemaVersion") == 2) { "Candidate manifest schema must be 2" }
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
        "swiftPmAbProof", "centralBundleInventory", "mavenInventory", "cleanKmpConsumer", "androidRuntime",
        "privacyAudit", "artifactMetrics", "resourceMeasurements",
    )
    check(evidence.keys == expectedEvidence) { "Candidate evidence set is invalid" }
    expectedEvidence.minus("resourceMeasurements").forEach { verifyRecordShape(evidence.releaseObject(it)) }
    check(evidence.releaseObject("swiftPmAbProof").releaseString("fileName") == "swiftpm-ab-proof.json") {
        "Candidate SwiftPM A/B proof file name is invalid"
    }
    check(evidence.releaseObject("artifactMetrics").releaseString("fileName") == "artifact-metrics.json") {
        "Candidate artifact metrics file name is invalid"
    }
    val resources = evidence.releaseArray("resourceMeasurements")
    check(resources.isNotEmpty()) { "Candidate resource evidence is missing" }
    resources.forEach { verifyRecordShape(it as? JsonObject ?: error("Invalid resource record")) }
    val policies = manifest.releaseObject("policies")
    val requiredPolicies = setOf("approvals", "privacyManifest", "privacyDataFlowReview", "packageSwift")
    check(policies.keys == requiredPolicies || policies.keys == requiredPolicies + "privacyRequiredReasonReviews") {
        "Candidate policy set is invalid"
    }
    policies.values.forEach { verifyRecordShape(it as? JsonObject ?: error("Invalid candidate policy record")) }
}

private fun verifyRecordShape(record: JsonObject) {
    val fileName = record.releaseString("fileName")
    check(fileName == File(fileName).name && '/' !in fileName && '\\' !in fileName) { "Unsafe candidate file name" }
    check(record.releaseLong("bytes") >= 0) { "Candidate file size is invalid" }
    check(record.releaseString("sha256").matches(Regex("[0-9a-f]{64}"))) { "Candidate SHA-256 is invalid" }
}
