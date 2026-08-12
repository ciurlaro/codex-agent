import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal const val CENTRAL_API = "https://central.example/api/v1/publisher"
internal const val CENTRAL_ID = "28570f16-da32-4c14-bd2e-c1acc0782365"
internal const val CENTRAL_COMMIT = "0123456789abcdef0123456789abcdef01234567"
internal val CENTRAL_ENTRIES = linkedMapOf(
    "io/github/example/client/0.2.0/client-0.2.0.jar" to byteArrayOf(0, 1, 2, -1),
    "io/github/example/client/0.2.0/client-0.2.0.pom" to "<project/>".encodeToByteArray(),
)
internal const val CENTRAL_PURL = "pkg:maven/io.github.example/client@0.2.0"
internal val CENTRAL_BUNDLE_BYTES = centralZip(CENTRAL_ENTRIES.toList())
internal val CENTRAL_NAME = "codex-agent-0.2.0-$CENTRAL_COMMIT-${CENTRAL_BUNDLE_BYTES.sha256()}"

internal fun centralZip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().let { bytes ->
    ZipOutputStream(bytes).use { zip ->
        entries.forEach { (name, content) ->
            zip.putNextEntry(ZipEntry(name).apply { time = 0 })
            zip.write(content)
            zip.closeEntry()
        }
    }
    bytes.toByteArray()
}

internal fun duplicateCentralZip(): ByteArray = centralZip(listOf(
    "a.txt" to "one".encodeToByteArray(), "b.txt" to "two".encodeToByteArray(),
)).also { bytes ->
    val from = "b.txt".encodeToByteArray()
    val to = "a.txt".encodeToByteArray()
    for (index in 0..bytes.size - from.size) {
        if (from.indices.all { bytes[index + it] == from[it] }) to.indices.forEach { bytes[index + it] = to[it] }
    }
}

private fun ByteArray.sha256(): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(this).joinToString("") { "%02x".format(it) }

internal fun uploadPortal() = FakePortal(deployments(), CentralPortalResponse(201, CENTRAL_ID))
internal fun deployments(vararg items: String) = CentralPortalResponse(
    200,
    """{"deployments":[${items.joinToString()}],"page":0,"pageSize":20,"pageCount":${if (items.isEmpty()) 0 else 1},"totalResultCount":${items.size}}""",
)
internal fun deployment(
    id: String = CENTRAL_ID,
    name: String = CENTRAL_NAME,
    state: String = "VALIDATED",
) = """{"deploymentId":"$id","deploymentName":"$name","deploymentState":"$state"}"""

internal fun status(
    state: String,
    id: String = CENTRAL_ID,
    name: String = CENTRAL_NAME,
    purls: List<String> = if (state in verifiableCentralStates) listOf(CENTRAL_PURL) else emptyList(),
) = CentralPortalResponse(
    200,
    """{"deploymentId":"$id","deploymentName":"$name","deploymentState":"$state","purls":[${purls.joinToString { "\"$it\"" }}]}""",
)

internal fun downloads(
    entries: Map<String, ByteArray> = CENTRAL_ENTRIES,
): List<CentralPortalResponse> = entries.toSortedMap().values.map { CentralPortalResponse(200, it) }

internal fun withCentralFixture(
    bundleBytes: ByteArray = CENTRAL_BUNDLE_BYTES,
    block: (CentralFixture) -> Unit,
) {
    val directory = kotlin.io.path.createTempDirectory("central-portal").toFile()
    try { block(CentralFixture(directory, bundleBytes)) } finally { directory.deleteRecursively() }
}

internal class CentralFixture(directory: File, bundleBytes: ByteArray) {
    val bundle = directory.resolve("bundle.zip").apply { writeBytes(bundleBytes) }
    val candidate = directory.resolve("candidate.json")
    val record = directory.resolve("state/deployment.json")
    val name = "codex-agent-0.2.0-$CENTRAL_COMMIT-${bundle.releaseDigest()}"

    init {
        fun record(fileName: String) = buildJsonObject {
            put("fileName", JsonPrimitive(fileName)); put("bytes", JsonPrimitive(1)); put("sha256", JsonPrimitive("0".repeat(64)))
        }
        candidate.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(4)); put("version", JsonPrimitive("0.2.0"))
            put("releaseTag", JsonPrimitive("v0.2.0")); put("candidateCommit", JsonPrimitive(CENTRAL_COMMIT))
            put("protectedCandidate", JsonPrimitive(true))
            put("artifacts", buildJsonObject {
                put("swiftPackage", buildJsonObject {
                    record("CodexAgent-0.2.0.xcframework.zip").forEach { (key, value) -> put(key, value) }
                    put("swiftPmChecksum", JsonPrimitive("0".repeat(64))); put("members", buildJsonArray {})
                })
                put("centralBundle", bundle.releaseRecord())
            })
            put("evidence", buildJsonObject {
                put("swiftPmProof", record("swiftpm-proof.json")); put("centralBundleInventory", record("central-bundle.json"))
                put("mavenInventory", record("maven-inventory.json")); put("cleanKmpConsumer", record("kmp-consumer.json"))
                put("androidRuntime", record("android-evidence.json")); put("desktopRuntime", buildJsonArray {
                    desktopRuntimeEvidenceTargets.keys.forEach { add(record(desktopRuntimeEvidenceFileName(it))) }
                })
                put("privacyAudit", record("privacy-audit.json")); put("artifactMetrics", record("artifact-metrics.json"))
                put("resourceMeasurements", buildJsonArray { add(record("resource-measurement.json")) })
            })
            put("policies", buildJsonObject {
                put("approvals", record("publication-approvals.json")); put("privacyManifest", record("PrivacyInfo.xcprivacy"))
                put("privacyDataFlowReview", record("privacy-data-flow-review.json")); put("packageSwift", record("Package.swift"))
                put("desktopDistributionManifest", record("codex-app-server-distributions.json"))
                put("desktopBundledLicense", record("openai-codex-LICENSE.txt")); put("desktopBundledNotice", record("openai-codex-NOTICE.txt"))
            })
        })
    }

    fun prepare(portal: FakePortal, allow: Boolean = false) = prepare(portal::send, allow)
    fun prepare(sender: (CentralPortalRequest) -> CentralPortalResponse, allow: Boolean = false) =
        prepareCentralDeployment(bundle, candidate, record, CENTRAL_API, "user", "password", allow, sender)
    fun await(portal: FakePortal, attempts: Int = 120, sleeper: (Long) -> Unit = {}) =
        awaitCentralValidation(bundle, candidate, record, CENTRAL_API, "user", "password", attempts, 10, portal::send, sleeper)
    fun release(portal: FakePortal) =
        releaseCentralDeployment(bundle, candidate, record, CENTRAL_API, "user", "password", 10, 0, portal::send) {}
    fun setState(state: String) = mutateRecord("deploymentState", state)
    fun mutateRecord(field: String, value: String) = mutateRecord(field, JsonPrimitive(value))
    fun mutateRecord(field: String, value: JsonElement) {
        val values = record.readReleaseObject().toMutableMap(); values[field] = value
        record.atomicWriteJson(JsonObject(values))
    }
}

internal class FakePortal(vararg responses: CentralPortalResponse) {
    private val responses = ArrayDeque(responses.toList())
    val requests = mutableListOf<CentralPortalRequest>()
    fun send(request: CentralPortalRequest): CentralPortalResponse {
        requests += request
        return responses.removeFirst()
    }
}
