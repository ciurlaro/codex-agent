import java.io.File
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

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
) {
    val identity = centralIdentity(bundle, candidate)
    if (record.isFile) {
        val stored = record.readDeployment()
        stored.requireIdentity(identity)
        check(stored.state in reusableCentralStates) { "Central deployment is not reusable: ${stored.state}" }
        if (stored.state in verifiableCentralStates && !stored.isRemoteBundleVerified(identity)) {
            check(username.isNotBlank() && password.isNotBlank()) { "Central credentials are missing" }
            val headers = mapOf("Authorization" to centralAuthorization(username, password))
            val current = centralStatus(stored, identity, api, headers, sender)
            record.writeDeployment(current.verifyRemoteBundle(bundle, identity, api, headers, sender))
        }
        return
    }
    check(username.isNotBlank() && password.isNotBlank()) { "Central credentials are missing" }
    val headers = mapOf("Authorization" to centralAuthorization(username, password))
    findCentralDeployment(identity, api, headers, sender)?.let { recovered ->
        record.writeDeployment(recovered)
        val verified = centralStatus(recovered, identity, api, headers, sender)
        val proven = verified.verifyRemoteBundle(bundle, identity, api, headers, sender)
        record.writeDeployment(proven)
        check(verified.state in reusableCentralStates) { "Central deployment is not reusable: ${verified.state}" }
        return
    }
    check(allowNewUpload) { "Central deployment record is absent; refusing a duplicate-prone upload" }
    val id = sender.checked(centralMultipartUpload(bundle, headers, api, identity.name)).trim()
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
        deployment = centralStatus(deployment, identity, api, headers, sender)
            .verifyRemoteBundle(bundle, identity, api, headers, sender)
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
        deployment = centralStatus(deployment, identity, api, headers, sender)
            .verifyRemoteBundle(bundle, identity, api, headers, sender)
        record.writeDeployment(deployment)
        when (deployment.state) {
            "PUBLISHED" -> {
                check(deployment.isRemoteBundleVerified(identity)) { "Central deployment bundle is unverified" }
                return
            }
            "FAILED" -> error("Central deployment failed")
            "VALIDATED" -> if (!releaseRequested) {
                check(deployment.isRemoteBundleVerified(identity)) { "Central deployment bundle is unverified" }
                sender.checked(CentralPortalRequest("POST", "$api/deployment/${deployment.id}", headers))
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
    val bundleRecord = manifest.releaseObject("artifacts").releaseObject("centralBundle")
    check(bundleRecord.releaseString("fileName") == bundle.name) { "Central bundle file name mismatch" }
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
): CentralDeployment {
    val status = releaseJson.parseToJsonElement(
        sender.checked(CentralPortalRequest("POST", "$api/status?id=${deployment.id}", headers)),
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

@DisableCachingByDefault(because = "This task uploads one exact remote deployment")
abstract class PrepareCentralDeploymentTask : CentralPortalTask() {
    @get:Input abstract val allowNewUpload: Property<Boolean>
    @get:OutputFile abstract override val deploymentRecord: RegularFileProperty
    @TaskAction fun prepare() = prepareCentralDeployment(
        bundleFile.get().asFile, candidateManifest.get().asFile, deploymentRecord.get().asFile,
        apiBaseUrl.get(), username.orNull.orEmpty(), password.orNull.orEmpty(), allowNewUpload.get(),
    )
}

@DisableCachingByDefault(because = "This task polls a remote deployment")
abstract class AwaitCentralValidationTask : CentralPortalTask() {
    @get:Internal abstract override val deploymentRecord: RegularFileProperty
    @TaskAction fun await() = awaitCentralValidation(
        bundleFile.get().asFile, candidateManifest.get().asFile, deploymentRecord.get().asFile,
        apiBaseUrl.get(), username.orNull.orEmpty(), password.orNull.orEmpty(),
    )
}

@DisableCachingByDefault(because = "This task releases and polls a remote deployment")
abstract class ReleaseCentralDeploymentTask : CentralPortalTask() {
    @get:Internal abstract override val deploymentRecord: RegularFileProperty
    @TaskAction fun release() = releaseCentralDeployment(
        bundleFile.get().asFile, candidateManifest.get().asFile, deploymentRecord.get().asFile,
        apiBaseUrl.get(), username.orNull.orEmpty(), password.orNull.orEmpty(),
    )
}

abstract class CentralPortalTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val bundleFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val candidateManifest: RegularFileProperty
    abstract val deploymentRecord: RegularFileProperty
    @get:Input abstract val apiBaseUrl: Property<String>
    @get:Internal abstract val username: Property<String>
    @get:Internal abstract val password: Property<String>

    init {
        apiBaseUrl.convention(project.providers.environmentVariable("CENTRAL_PORTAL_API").orElse(DEFAULT_CENTRAL_PORTAL_API))
        username.convention(project.providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
        password.convention(project.providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
    }
}

fun Project.registerCentralPortalTasks() {
    val bundle = layout.file(providers.gradleProperty("codexAgent.centralBundle").map(::file))
    val candidate = layout.file(providers.gradleProperty("codexAgent.candidateManifest").map(::file))
    val record = layout.file(providers.gradleProperty("codexAgent.centralDeploymentRecord").map(::file))
    tasks.register("prepareCentralDeployment", PrepareCentralDeploymentTask::class.java) {
        group = "publishing"
        description = "Uploads the exact candidate bundle once as USER_MANAGED and records its deployment ID."
        bundleFile.set(bundle); candidateManifest.set(candidate); deploymentRecord.set(record)
        allowNewUpload.set(providers.gradleProperty("codexAgent.allowCentralUpload").map(String::toBoolean).orElse(false))
    }
    tasks.register("awaitCentralValidation", AwaitCentralValidationTask::class.java) {
        group = "publishing"
        description = "Waits for the recorded exact Central deployment to validate."
        bundleFile.set(bundle); candidateManifest.set(candidate); deploymentRecord.set(record)
    }
    tasks.register("releaseCentralDeployment", ReleaseCentralDeploymentTask::class.java) {
        group = "publishing"
        description = "Releases the recorded validated Central deployment and waits for PUBLISHED."
        bundleFile.set(bundle); candidateManifest.set(candidate); deploymentRecord.set(record)
    }
}
