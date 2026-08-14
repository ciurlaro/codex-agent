import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal const val IOS_VERIFIED_DISTRIBUTION_PROOF = "verified-distribution-proof.json"
internal const val IOS_VERIFIED_DISTRIBUTION_PROPERTY = "codexAgent.iosVerifiedDistributionDirectory"

internal val appleVerifiedReportLayout = linkedMapOf(
    "reports/ios-release/artifact-metrics.json" to "reports/ios-release/artifact-metrics.json",
    "reports/ios-release/deployment-targets.txt" to "reports/ios-release/deployment-targets.txt",
    "reports/ios-release/license-packaging.txt" to "reports/ios-release/license-packaging.txt",
    "reports/ios-release/runtime-metrics.json" to "reports/ios-release/runtime-metrics.json",
    "reports/ios-release/privacy/audit.json" to "reports/ios-release/privacy/audit.json",
    "reports/ios-release/privacy/evidence.json" to "reports/ios-release/privacy/evidence.json",
    "reports/ios-release/privacy/policy.json" to "reports/ios-release/privacy/policy.json",
    "reports/ios-release/privacy/privacy-required-reason-review.json" to
        "reports/ios-release/privacy/privacy-required-reason-review.json",
    "reports/swift-authentication-tests-summary.json" to "swift-authentication-tests-summary.json",
)

internal val appleVerifiedToolchainLayout = linkedMapOf(
    "toolchain/xcode.txt" to "reports/ios-release/toolchain/xcode.txt",
    "toolchain/swift.txt" to "reports/ios-release/toolchain/swift.txt",
)

internal val appleVerifiedCompletedTasks = listOf(
    ":codex-agent-runtime-ios:verifyAppleToolchain",
    ":codex-agent-runtime-ios:validateImportedCodexAgentIosNativeEvidence",
    ":codex-agent-runtime-ios:compileKotlinIosArm64",
    ":codex-agent-runtime-ios:iosSimulatorArm64Test",
    ":codex-agent-runtime-ios:verifyCodexAgentSwiftPackage",
    ":codex-agent-runtime-ios:verifyCodexAgentSwiftAuthenticationTests",
    ":codex-agent-runtime-ios:packageCodexAgentAppleDistribution",
    ":codex-agent-runtime-ios:packageCodexAgentSwiftPackageBinary",
    ":codex-agent-runtime-ios:verifyCodexAgentRemoteSwiftPackage",
    ":codex-agent-runtime-ios:verifyIosDeploymentTargets",
    ":codex-agent-runtime-ios:verifyIosLicensePackaging",
    ":codex-agent-runtime-ios:verifyIosPrivacyManifest",
    ":codex-agent-runtime-ios:verifyIosReleaseBudgets",
)

internal data class AppleVerifiedDistributionIdentity(
    val commit: String,
    val tree: String,
    val version: String,
    val nativeProvenanceSha256: String,
    val packageSwiftSha256: String,
    val nativeEvidenceReceiptSha256: String,
)

internal data class AppleVerifiedDistributionInventory(
    val artifacts: Map<String, File>,
    val reports: Map<String, File>,
    val toolchain: Map<String, File>,
    val proof: File,
)

internal fun appleVerifiedArtifactNames(version: String) = setOf(
    "CodexAgentPackage-$version.zip",
    "CodexAgent-$version.xcframework.zip",
    "CodexAgent-$version.xcframework.zip.sha256",
)

internal fun buildAppleVerifiedDistributionProof(
    identity: AppleVerifiedDistributionIdentity,
    artifacts: Map<String, File>,
    reports: Map<String, File>,
    toolchain: Map<String, File>,
    nativeEvidence: Map<String, File>,
): JsonObject = buildJsonObject {
    put("schemaVersion", JsonPrimitive(1))
    put("protocol", JsonPrimitive("codex-agent-ios-verified-distribution-v1"))
    put("result", JsonPrimitive("passed"))
    put("candidateCommit", JsonPrimitive(identity.commit))
    put("candidateTree", JsonPrimitive(identity.tree))
    put("cleanCheckout", JsonPrimitive(true))
    put("version", JsonPrimitive(identity.version))
    put("nativeProvenanceSha256", JsonPrimitive(identity.nativeProvenanceSha256))
    put("packageSwiftSha256", JsonPrimitive(identity.packageSwiftSha256))
    put("nativeEvidenceReceiptSha256", JsonPrimitive(identity.nativeEvidenceReceiptSha256))
    put("completedTasks", JsonArray(appleVerifiedCompletedTasks.map(::JsonPrimitive)))
    put("artifacts", releaseRecords(artifacts))
    put("reports", releaseRecords(reports))
    put("toolchain", releaseRecords(toolchain))
    put("nativeEvidence", releaseRecords(nativeEvidence))
}

internal fun verifyAppleVerifiedDistribution(
    directory: File,
    currentNativeEvidence: File,
    identity: AppleVerifiedDistributionIdentity,
): AppleVerifiedDistributionInventory {
    val files = verifiedRegularFiles(directory)
    val proofFile = files[IOS_VERIFIED_DISTRIBUTION_PROOF]
        ?: error("Verified Apple distribution proof is missing")
    val proof = proofFile.readReleaseObject()
    val expectedKeys = setOf(
        "schemaVersion", "protocol", "result", "candidateCommit", "candidateTree", "cleanCheckout", "version",
        "nativeProvenanceSha256", "packageSwiftSha256", "nativeEvidenceReceiptSha256", "completedTasks",
        "artifacts", "reports", "toolchain", "nativeEvidence",
    )
    check(proof.keys == expectedKeys && proof.releaseInt("schemaVersion") == 1 &&
        proof.releaseString("protocol") == "codex-agent-ios-verified-distribution-v1" &&
        proof.releaseString("result") == "passed") { "Invalid verified Apple distribution proof schema" }
    check(identity.commit.matches(Regex("[0-9a-f]{40}")) &&
        proof.releaseString("candidateCommit") == identity.commit &&
        proof.releaseString("candidateTree") == identity.tree && proof.releaseBoolean("cleanCheckout")) {
        "Verified Apple distribution checkout identity mismatch"
    }
    mapOf(
        "version" to identity.version,
        "nativeProvenanceSha256" to identity.nativeProvenanceSha256,
        "packageSwiftSha256" to identity.packageSwiftSha256,
        "nativeEvidenceReceiptSha256" to identity.nativeEvidenceReceiptSha256,
    ).forEach { (key, value) ->
        check(proof.releaseString(key) == value) { "Verified Apple distribution $key mismatch" }
    }
    check(proof.releaseArray("completedTasks").map { (it as JsonPrimitive).content } == appleVerifiedCompletedTasks) {
        "Verified Apple distribution completed-task set mismatch"
    }
    val artifacts = verifyRecordGroup(proof, "artifacts", files, appleVerifiedArtifactNames(identity.version))
    val reports = verifyRecordGroup(proof, "reports", files, appleVerifiedReportLayout.keys)
    val toolchain = verifyRecordGroup(proof, "toolchain", files, appleVerifiedToolchainLayout.keys)
    val nativeFiles = verifiedRegularFiles(currentNativeEvidence)
    val nativeNames = appleRustSliceSpecs.flatMap { listOf(it.archiveName, it.proofName) }.toSet() + IOS_NATIVE_TESTS_PROOF
    verifyRecordGroup(proof, "nativeEvidence", nativeFiles, nativeNames)
    val expectedFiles = artifacts.keys + reports.keys + toolchain.keys + IOS_VERIFIED_DISTRIBUTION_PROOF
    check(files.keys == expectedFiles) { "Verified Apple distribution contains missing or extra files" }
    val swiftArchive = artifacts.getValue("CodexAgent-${identity.version}.xcframework.zip")
    val checksum = artifacts.getValue("CodexAgent-${identity.version}.xcframework.zip.sha256").readText().trim()
    check(checksum == swiftArchive.releaseDigest()) { "Verified Apple distribution Swift checksum mismatch" }
    return AppleVerifiedDistributionInventory(artifacts, reports, toolchain, proofFile)
}

internal fun verifiedRegularFiles(root: File): Map<String, File> {
    check(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Verified evidence directory is missing or unsafe" }
    val entries = Files.walk(root.toPath()).use { it.toList() }
    entries.filter { it != root.toPath() }.forEach { path ->
        check(!Files.isSymbolicLink(path) && (Files.isDirectory(path) || Files.isRegularFile(path))) {
            "Verified evidence contains an unsafe entry: $path"
        }
    }
    return entries.filter(Files::isRegularFile).associate { path ->
        root.toPath().relativize(path).joinToString("/") to path.toFile()
    }
}

private fun releaseRecords(files: Map<String, File>) = buildJsonArray {
    files.toSortedMap().forEach { (path, file) -> add(file.releaseRecord(path)) }
}

private fun verifyRecordGroup(
    proof: JsonObject,
    key: String,
    available: Map<String, File>,
    expectedPaths: Set<String>,
): Map<String, File> {
    val values = proof.releaseArray(key)
    check(values.size == expectedPaths.size) { "Verified Apple $key record count mismatch" }
    val records = values.associate { value ->
        val record = value as? JsonObject ?: error("Verified Apple $key record is not an object")
        check(record.keys == setOf("fileName", "bytes", "sha256")) { "Invalid verified Apple $key record" }
        val path = record.releaseString("fileName")
        check(path.isNotBlank() && !path.startsWith('/') && ".." !in path.split('/') && '\\' !in path) {
            "Unsafe verified Apple $key path: $path"
        }
        val file = available[path] ?: error("Verified Apple $key file is missing: $path")
        verifyReleaseRecord(file, record)
        path to file
    }
    check(records.keys == expectedPaths) { "Verified Apple $key file set mismatch" }
    return records
}
