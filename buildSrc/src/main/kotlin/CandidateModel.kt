import java.io.File
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
    val swiftPmProof: File,
    val centralBundle: File,
    val centralInventory: File,
    val mavenInventory: File,
    val kmpConsumer: File,
    val desktopEvidence: List<File>,
    val nodeEvidence: List<File>,
    val nodeClassifierArchives: List<File>,
    val nodeRuntimeRunner: File,
    val windowsSupervisorPackage: File,
    val windowsSupervisorIdentity: File,
    val windowsSupervisorExecutable: File,
    val windowsSupervisorSource: File,
    val iosNativeEvidence: File,
    val privacyAudit: File,
    val artifactMetrics: File,
    val resourceReports: List<File>,
    val approvals: File,
    val privacyManifest: File,
    val privacyDataFlowReview: File,
    val privacyRequiredReasonReviews: File?,
    val packageSwift: File,
    val desktopDistributionManifest: File,
    val desktopBundledLicense: File,
    val desktopBundledNotice: File,
)

internal fun buildCandidateManifest(input: CandidateInputFiles): JsonObject {
    check(input.releaseTag == "v${input.version}") { "Candidate release tag must equal v${input.version}" }
    check(input.commit.matches(Regex("[0-9a-f]{40}"))) { "Candidate commit is not immutable" }
    val swiftHash = input.swiftZip.releaseDigest()
    check(input.swiftChecksum.readText().trim() == swiftHash) { "SwiftPM checksum does not match the candidate ZIP" }
    verifySwiftPackageProof(input, swiftHash)

    val central = input.centralInventory.readReleaseObject()
    check(central.releaseBoolean("belowCentralPortalUploadLimit")) { "Central bundle exceeds the Portal limit" }
    verifyReleaseRecord(input.centralBundle, central.releaseObject("bundle"))
    check(central.releaseString("mavenInventorySha256") == input.mavenInventory.releaseDigest()) {
        "Central inventory does not bind the Maven inventory"
    }
    val maven = input.mavenInventory.readReleaseObject()
    check(maven.releaseString("version") == input.version) { "Maven inventory version mismatch" }
    val expectedMavenArtifactCount = expectedMavenPrimaryPaths(input.version).size
    check(maven.releaseInt("primaryArtifactCount") == expectedMavenArtifactCount) {
        "Maven inventory must contain $expectedMavenArtifactCount primary artifacts"
    }
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
    val desktopErrors = validateDesktopRuntimeEvidence(
        input.desktopEvidence,
        input.commit,
        input.version,
        input.mavenInventory,
        input.desktopDistributionManifest,
    )
    check(desktopErrors.isEmpty()) { "Desktop runtime evidence is invalid: ${desktopErrors.joinToString()}" }
    val supervisorIdentity = verifyWindowsSupervisorPackage(
        input.windowsSupervisorPackage,
        input.windowsSupervisorIdentity,
        input.windowsSupervisorSource,
    )
    verifyWindowsSupervisorIdentity(
        supervisorIdentity,
        input.windowsSupervisorExecutable,
        input.windowsSupervisorSource,
    )
    val nodeErrors = validateNodeRuntimeEvidence(
        input.nodeEvidence,
        input.commit,
        input.desktopDistributionManifest,
        input.nodeClassifierArchives,
        input.nodeRuntimeRunner,
        input.windowsSupervisorExecutable,
    )
    check(nodeErrors.isEmpty()) { "Node runtime evidence is invalid: ${nodeErrors.joinToString()}" }
    verifyWindowsSupervisorMavenBinding(input, maven)
    verifyCandidateIosNativeEvidence(input.iosNativeEvidence, input.commit)
    verifyDesktopBundledGplApproval(
        input.approvals,
        input.desktopDistributionManifest,
        input.desktopBundledLicense,
        input.desktopBundledNotice,
    )
    check(input.resourceReports.isNotEmpty()) { "At least one resource evidence report is required" }
    input.resourceReports.forEach { report ->
        check(report.readReleaseObject().releaseInt("exitCode") == 0) { "Resource phase did not pass: ${report.name}" }
    }
    val evidenceNames = listOf(
        input.swiftPmProof,
        input.centralInventory,
        input.mavenInventory,
        input.kmpConsumer,
        input.iosNativeEvidence,
        *input.desktopEvidence.toTypedArray(),
        *input.nodeEvidence.toTypedArray(),
        input.nodeRuntimeRunner,
        input.windowsSupervisorIdentity,
        input.privacyAudit,
        input.artifactMetrics,
    ) + input.resourceReports
    check(evidenceNames.map(File::getName).toSet().size == evidenceNames.size) {
        "Candidate evidence file names must be unique"
    }

    val manifest = buildJsonObject {
        put("schemaVersion", JsonPrimitive(7))
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
            put("swiftPmProof", input.swiftPmProof.releaseRecord())
            put("centralBundleInventory", input.centralInventory.releaseRecord())
            put("mavenInventory", input.mavenInventory.releaseRecord())
            put("cleanKmpConsumer", input.kmpConsumer.releaseRecord())
            put("desktopRuntime", buildJsonArray {
                input.desktopEvidence.sortedBy(File::getName).forEach { add(it.releaseRecord()) }
            })
            put("nodeRuntime", buildJsonArray {
                input.nodeEvidence.sortedBy(File::getName).forEach { add(it.releaseRecord()) }
            })
            put("nodeRuntimeRunner", input.nodeRuntimeRunner.releaseRecord())
            put("windowsSupervisorIdentity", input.windowsSupervisorIdentity.releaseRecord())
            put("iosNative", input.iosNativeEvidence.releaseRecord())
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
            put("desktopDistributionManifest", input.desktopDistributionManifest.releaseRecord())
            put("desktopBundledLicense", input.desktopBundledLicense.releaseRecord())
            put("desktopBundledNotice", input.desktopBundledNotice.releaseRecord())
        })
    }
    verifyCandidateManifestStructure(manifest)
    return manifest
}

private fun verifyWindowsSupervisorMavenBinding(input: CandidateInputFiles, maven: JsonObject) {
    val expectedPath = "io/github/ciurlaro/codex-agent-runtime-node/${input.version}/" +
        "codex-agent-runtime-node-${input.version}-windows-supervisor-x64.zip"
    val matches = maven.releaseArray("files").mapNotNull { it as? JsonObject }
        .filter { it.releaseString("path") == expectedPath }
    check(matches.size == 1) { "Maven inventory does not contain the exact Windows supervisor classifier" }
    val record = matches.single()
    check(record.releaseLong("bytes") == input.windowsSupervisorPackage.length() &&
        record.releaseString("sha256") == input.windowsSupervisorPackage.releaseDigest()) {
        "Maven inventory does not bind the Windows supervisor package"
    }
}

private fun verifySwiftPackageProof(input: CandidateInputFiles, swiftHash: String) {
    check(input.swiftPmProof.name == "swiftpm-proof.json" && input.swiftPmProof.isFile) {
        "SwiftPM candidate proof is missing or has the wrong file name"
    }
    val proof = input.swiftPmProof.readReleaseObject()
    check(proof.keys == setOf(
        "schemaVersion", "protocol", "result", "version", "candidateCommit", "candidateTree", "cleanCheckout",
        "canonicalBuildRoot", "archiveName", "archiveBytes", "swiftPmChecksum", "checksumFileSha256",
        "packageSwiftUrl", "packageSwiftSha256", "packageSwiftChecksum", "nativeProvenanceSha256",
        "xcodeVersionSha256", "swiftVersionSha256", "toolchainSha256",
    )) { "SwiftPM candidate proof fields are invalid" }
    check(proof.releaseInt("schemaVersion") == 1 && proof.releaseString("protocol") == "swiftpm-candidate-v1") {
        "Unsupported SwiftPM candidate proof"
    }
    check(proof.releaseString("result") == "passed" && proof.releaseBoolean("cleanCheckout")) {
        "SwiftPM candidate proof did not pass from a clean checkout"
    }
    check(proof.releaseString("version") == input.version && proof.releaseString("candidateCommit") == input.commit) {
        "SwiftPM candidate proof identity mismatch"
    }
    check(proof.releaseString("candidateTree").matches(Regex("[0-9a-f]{40}"))) {
        "SwiftPM candidate tree is not immutable"
    }
    check(File(proof.releaseString("canonicalBuildRoot")).isAbsolute) { "SwiftPM build root must be absolute" }
    check(proof.releaseString("archiveName") == input.swiftZip.name &&
        proof.releaseLong("archiveBytes") == input.swiftZip.length() &&
        proof.releaseString("swiftPmChecksum") == swiftHash) {
        "SwiftPM candidate proof does not bind the exact ZIP"
    }
    check(proof.releaseString("checksumFileSha256") == input.swiftChecksum.releaseDigest()) {
        "SwiftPM candidate proof does not bind the checksum file"
    }
    val expectedUrl = "https://github.com/ciurlaro/codex-agent/releases/download/v${input.version}/${input.swiftZip.name}"
    check(proof.releaseString("packageSwiftUrl") == expectedUrl &&
        proof.releaseString("packageSwiftSha256") == input.packageSwift.releaseDigest() &&
        proof.releaseString("packageSwiftChecksum") == swiftHash) {
        "SwiftPM candidate proof does not bind committed Package.swift metadata"
    }
    listOf(
        "nativeProvenanceSha256", "xcodeVersionSha256", "swiftVersionSha256", "toolchainSha256",
    ).forEach { field ->
        check(proof.releaseString(field).matches(Regex("[0-9a-f]{64}"))) {
            "SwiftPM candidate proof has an invalid $field"
        }
    }
}
