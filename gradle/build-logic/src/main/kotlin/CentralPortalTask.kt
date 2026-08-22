import java.io.File
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private val reusableCentralStates = setOf("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED")

internal data class CentralIdentity(
    val name: String,
    val version: String,
    val commit: String,
    val candidateSha256: String,
    val bundleSha256: String,
    val purls: Set<String>,
)

internal data class CentralDeployment(
    val id: String,
    val name: String,
    val state: String,
    val candidateSha256: String,
    val bundleSha256: String,
    val remoteBundleVerifiedSha256: String?,
)

internal fun prepareCentralDeployment(
    bundle: File,
    candidate: File,
    record: File,
    api: String,
    username: String,
    password: String,
    allowNewUpload: Boolean,
    sender: (CentralPortalRequest) -> CentralPortalResponse = JdkCentralPortalSender()::send,
    sleeper: (Long) -> Unit = Thread::sleep,
) {
    val identity = centralIdentity(bundle, candidate)
    if (record.isFile) {
        val stored = record.readDeployment()
        stored.requireIdentity(identity)
        check(stored.state in reusableCentralStates) { "Central deployment is not reusable: ${stored.state}" }
        if (stored.state in verifiableCentralStates && !stored.isRemoteBundleVerified(identity)) {
            check(username.isNotBlank() && password.isNotBlank()) { "Central credentials are missing" }
            val headers = mapOf("Authorization" to centralAuthorization(username, password))
            val current = centralStatus(stored, identity, api, headers, sender, sleeper)
            record.writeDeployment(current.verifyRemoteBundle(bundle, identity, api, headers, sender, sleeper))
        }
        return
    }
    check(username.isNotBlank() && password.isNotBlank()) { "Central credentials are missing" }
    val headers = mapOf("Authorization" to centralAuthorization(username, password))
    findCentralDeployment(identity, api, headers, sender, sleeper)?.let { recovered ->
        record.writeDeployment(recovered)
        val verified = centralStatus(recovered, identity, api, headers, sender, sleeper)
        val proven = verified.verifyRemoteBundle(bundle, identity, api, headers, sender, sleeper)
        record.writeDeployment(proven)
        check(verified.state in reusableCentralStates) { "Central deployment is not reusable: ${verified.state}" }
        return
    }
    check(allowNewUpload) { "Central deployment record is absent; refusing a duplicate-prone upload" }
    val id = sender.checked(centralMultipartUpload(bundle, headers, api, identity.name), sleeper).trim()
    check(runCatching { UUID.fromString(id) }.isSuccess) { "Central upload returned an invalid deployment ID" }
    record.writeDeployment(CentralDeployment(id, identity.name, "PENDING", identity.candidateSha256, identity.bundleSha256, null))
}

internal fun awaitCentralValidation(
    bundle: File,
    candidate: File,
    record: File,
    api: String,
    username: String,
    password: String,
    attempts: Int = 120,
    delayMillis: Long = 10_000,
    sender: (CentralPortalRequest) -> CentralPortalResponse = JdkCentralPortalSender()::send,
    sleeper: (Long) -> Unit = Thread::sleep,
) {
    val identity = centralIdentity(bundle, candidate)
    var deployment = record.readDeployment().also { it.requireIdentity(identity) }
    val headers = mapOf("Authorization" to centralAuthorization(username, password))
    repeat(attempts) {
        deployment = centralStatus(deployment, identity, api, headers, sender, sleeper)
            .verifyRemoteBundle(bundle, identity, api, headers, sender, sleeper)
        record.writeDeployment(deployment)
        when (deployment.state) {
            "VALIDATED", "PUBLISHING", "PUBLISHED" -> return
            "FAILED" -> error("Central deployment failed")
            "PENDING", "VALIDATING" -> sleeper(delayMillis)
            else -> error("Unsupported Central deployment state: ${deployment.state}")
        }
    }
    error("Central deployment timed out waiting for validation")
}

internal fun releaseCentralDeployment(
    bundle: File,
    candidate: File,
    record: File,
    api: String,
    username: String,
    password: String,
    attempts: Int = 120,
    delayMillis: Long = 10_000,
    sender: (CentralPortalRequest) -> CentralPortalResponse = JdkCentralPortalSender()::send,
    sleeper: (Long) -> Unit = Thread::sleep,
) {
    val identity = centralIdentity(bundle, candidate)
    var deployment = record.readDeployment().also { it.requireIdentity(identity) }
    val headers = mapOf("Authorization" to centralAuthorization(username, password))
    var releaseRequested = deployment.state == "PUBLISHING"
    repeat(attempts) {
        deployment = centralStatus(deployment, identity, api, headers, sender, sleeper)
            .verifyRemoteBundle(bundle, identity, api, headers, sender, sleeper)
        record.writeDeployment(deployment)
        when (deployment.state) {
            "PUBLISHED" -> {
                check(deployment.isRemoteBundleVerified(identity)) { "Central deployment bundle is unverified" }
                return
            }
            "FAILED" -> error("Central deployment failed")
            "VALIDATED" -> if (!releaseRequested) {
                check(deployment.isRemoteBundleVerified(identity)) { "Central deployment bundle is unverified" }
                sender.checked(CentralPortalRequest("POST", "$api/deployment/${deployment.id}", headers), sleeper)
                releaseRequested = true
                deployment = deployment.copy(state = "PUBLISHING")
                record.writeDeployment(deployment)
            }
            "PENDING", "VALIDATING", "PUBLISHING" -> Unit
            else -> error("Unsupported Central deployment state: ${deployment.state}")
        }
        sleeper(delayMillis)
    }
    error("Central deployment timed out waiting for PUBLISHED")
}

private fun centralIdentity(bundle: File, candidate: File): CentralIdentity {
    check(bundle.isFile && candidate.isFile) { "Central bundle and candidate manifest must be files" }
    val manifest = candidate.readReleaseObject()
    verifyCandidateManifestStructure(manifest)
    val version = manifest.releaseString("version")
    val commit = manifest.releaseString("candidateCommit")
    check(commit.matches(Regex("[0-9a-f]{40}"))) { "Central candidate commit is not immutable" }
    val artifacts = manifest.releaseObject("artifacts")
    val bundleRecord = if ("centralBundles" in artifacts) {
        promotedCentralBundleRecords(manifest).singleOrNull { it.releaseString("fileName") == bundle.name }
            ?: error("Central bundle is not declared by the candidate")
    } else {
        artifacts.releaseObject("centralBundle").also {
            check(it.releaseString("fileName") == bundle.name) { "Central bundle file name mismatch" }
        }
    }
    verifyReleaseRecord(bundle, bundleRecord)
    val bundleSha = bundle.releaseDigest()
    return CentralIdentity(
        "codex-agent-$version-$commit-$bundleSha",
        version,
        commit,
        candidate.releaseDigest(),
        bundleSha,
        bundle.centralPurls(),
    )
}

private fun centralStatus(
    deployment: CentralDeployment,
    identity: CentralIdentity,
    api: String,
    headers: Map<String, String>,
    sender: (CentralPortalRequest) -> CentralPortalResponse,
    sleeper: (Long) -> Unit,
): CentralDeployment {
    val status = releaseJson.parseToJsonElement(
        sender.checked(CentralPortalRequest("POST", "$api/status?id=${deployment.id}", headers), sleeper),
    ) as? JsonObject ?: error("Central status is not a JSON object")
    check(status.releaseString("deploymentId") == deployment.id) { "Central status deployment ID mismatch" }
    check(status.releaseString("deploymentName") == deployment.name) { "Central status deployment name mismatch" }
    val state = status.releaseString("deploymentState")
    if (state in verifiableCentralStates) {
        val values = status.releaseArray("purls").map { it.jsonPrimitive.content }
        check(values.size == values.toSet().size && values.toSet() == identity.purls) {
            "Central status PURL set mismatch"
        }
    }
    return deployment.copy(state = state)
}
