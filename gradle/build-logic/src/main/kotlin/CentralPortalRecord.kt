import java.io.File
import java.util.UUID
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val recordedCentralStates = setOf("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED", "FAILED")

internal fun File.readDeployment(): CentralDeployment {
    check(isFile) { "Central deployment record is required" }
    val json = readReleaseObject()
    val schema = json.releaseInt("schemaVersion")
    val base = setOf(
        "schemaVersion", "deploymentId", "deploymentName", "deploymentState",
        "candidateManifestSha256", "bundleSha256",
    )
    check(schema == 2 || schema == 3) { "Unsupported Central deployment record schema: $schema" }
    val allowedFields = if (schema == 3) listOf(base + "remoteBundleVerifiedSha256")
        else listOf(base, base + "remoteBundleVerifiedSha256")
    check(json.keys in allowedFields) { "Central deployment record fields are invalid" }
    val deployment = CentralDeployment(
        json.releaseString("deploymentId"), json.releaseString("deploymentName"),
        json.releaseString("deploymentState"), json.releaseString("candidateManifestSha256"),
        json.releaseString("bundleSha256"),
        if (schema == 3) json.releaseStringOrNull("remoteBundleVerifiedSha256") else null,
    )
    check(runCatching { UUID.fromString(deployment.id) }.isSuccess) { "Central deployment record ID is invalid" }
    check(deployment.state in recordedCentralStates) { "Central deployment record state is invalid" }
    check(listOf(deployment.candidateSha256, deployment.bundleSha256).all { it.matches(Regex("[0-9a-f]{64}")) }) {
        "Central deployment record hash is invalid"
    }
    check(deployment.remoteBundleVerifiedSha256 == null ||
        deployment.remoteBundleVerifiedSha256 == deployment.bundleSha256) { "Central remote bundle proof mismatch" }
    return deployment
}

internal fun File.writeDeployment(deployment: CentralDeployment) = atomicWriteJson(buildJsonObject {
    put("schemaVersion", JsonPrimitive(3))
    put("deploymentId", JsonPrimitive(deployment.id)); put("deploymentName", JsonPrimitive(deployment.name))
    put("deploymentState", JsonPrimitive(deployment.state))
    put("candidateManifestSha256", JsonPrimitive(deployment.candidateSha256))
    put("bundleSha256", JsonPrimitive(deployment.bundleSha256))
    put("remoteBundleVerifiedSha256", deployment.remoteBundleVerifiedSha256?.let { JsonPrimitive(it) } ?: JsonNull)
})

internal fun CentralDeployment.requireIdentity(identity: CentralIdentity) {
    check(name == identity.name) { "Central deployment name mismatch" }
    check(candidateSha256 == identity.candidateSha256) { "Central candidate manifest SHA-256 mismatch" }
    check(bundleSha256 == identity.bundleSha256) { "Central bundle SHA-256 mismatch" }
    check(remoteBundleVerifiedSha256 == null || remoteBundleVerifiedSha256 == identity.bundleSha256) {
        "Central remote bundle proof mismatch"
    }
}
