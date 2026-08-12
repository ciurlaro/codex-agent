import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class CentralPortalTaskTest {
    @Test
    fun `prepare uploads USER_MANAGED once and immediately records pending identity`() = withFixture { fixture ->
        val portal = FakePortal(CentralPortalResponse(201, ID))

        fixture.prepare(portal, allow = true)

        assertEquals(1, portal.requests.size)
        val upload = portal.requests.single()
        assertTrue(upload.url.startsWith("$API/upload?publishingType=USER_MANAGED&name=codex-agent-0.2.0-0123456789ab-"))
        assertEquals("Bearer " + Base64.getEncoder().encodeToString("user:password".toByteArray(UTF_8)), upload.headers["Authorization"])
        assertTrue(upload.body.toString(UTF_8).contains("name=\"bundle\"; filename=\"bundle.zip\""))
        assertEquals("bundle bytes", upload.bodyFile?.readText())
        assertEquals("PENDING", fixture.record.readReleaseObject().releaseString("deploymentState"))
        assertEquals(ID, fixture.record.readReleaseObject().releaseString("deploymentId"))
        assertFalse(fixture.record.readText().contains("password"))
    }

    @Test
    fun `prepare reuses every nonfailed matching state without network or duplicate upload`() = withFixture { fixture ->
        fixture.prepare(FakePortal(CentralPortalResponse(201, ID)), allow = true)
        listOf("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED").forEach { state ->
            fixture.setState(state)
            var requests = 0
            fixture.prepare(sender = { requests++; error("network must not be reached") })
            assertEquals(0, requests, state)
        }
    }

    @Test
    fun `prepare without durable record fails closed unless first upload is explicit`() = withFixture { fixture ->
        var requests = 0
        val failure = assertFailsWith<IllegalStateException> {
            fixture.prepare(sender = { requests++; error("network must not be reached") })
        }
        assertTrue(failure.message.orEmpty().contains("refusing"))
        assertEquals(0, requests)
    }

    @Test
    fun `malformed candidate manifest is rejected before network`() = withFixture { fixture ->
        fixture.candidate.atomicWriteJson(buildJsonObject { put("version", JsonPrimitive("0.2.0")) })
        var requests = 0
        assertFailsWith<IllegalStateException> {
            fixture.prepare(sender = { requests++; error("network must not be reached") }, allow = true)
        }
        assertEquals(0, requests)
    }

    @Test
    fun `record and candidate mismatches fail before network`() = withFixture { fixture ->
        fixture.prepare(FakePortal(CentralPortalResponse(201, ID)), allow = true)
        listOf("deploymentName", "candidateManifestSha256", "bundleSha256").forEach { field ->
            val original = fixture.record.readText()
            fixture.mutateRecord(field, "wrong")
            var requests = 0
            assertFailsWith<IllegalStateException> {
                fixture.prepare(sender = { requests++; error("network must not be reached") })
            }
            assertEquals(0, requests, field)
            fixture.record.writeText(original)
        }
    }

    @Test
    fun `invalid upload deployment id is rejected without a record`() = withFixture { fixture ->
        assertFailsWith<IllegalStateException> {
            fixture.prepare(FakePortal(CentralPortalResponse(201, "not-a-uuid")), allow = true)
        }
        assertFalse(fixture.record.exists())
    }

    @Test
    fun `await validation preserves state order and verifies returned identity`() = withFixture { fixture ->
        fixture.prepare(FakePortal(CentralPortalResponse(201, ID)), allow = true)
        var sleeps = 0
        val portal = FakePortal(
            status("PENDING"),
            status("VALIDATING"),
            status("VALIDATED"),
        )

        fixture.await(portal) { assertEquals(10L, it); sleeps++ }

        assertEquals(3, portal.requests.size)
        assertEquals(2, sleeps)
        assertEquals("VALIDATED", fixture.record.readReleaseObject().releaseString("deploymentState"))
    }

    @Test
    fun `status id or name mismatch is blocking and is not recorded`() = withFixture { fixture ->
        fixture.prepare(FakePortal(CentralPortalResponse(201, ID)), allow = true)
        listOf(
            """{"deploymentId":"wrong","deploymentName":"${fixture.name}","deploymentState":"VALIDATED"}""",
            """{"deploymentId":"$ID","deploymentName":"wrong","deploymentState":"VALIDATED"}""",
        ).forEach { body ->
            fixture.setState("PENDING")
            assertFailsWith<IllegalStateException> { fixture.await(FakePortal(CentralPortalResponse(200, body))) }
            assertEquals("PENDING", fixture.record.readReleaseObject().releaseString("deploymentState"))
        }
    }

    @Test
    fun `failed and unknown states are recorded then rejected`() = withFixture { fixture ->
        fixture.prepare(FakePortal(CentralPortalResponse(201, ID)), allow = true)
        listOf("FAILED", "MYSTERY").forEach { state ->
            fixture.setState("PENDING")
            assertFailsWith<IllegalStateException> { fixture.await(FakePortal(status(state))) }
            assertEquals(state, fixture.record.readReleaseObject().releaseString("deploymentState"))
        }
    }

    @Test
    fun `await timeout is bounded`() = withFixture { fixture ->
        fixture.prepare(FakePortal(CentralPortalResponse(201, ID)), allow = true)
        var sleeps = 0
        val portal = FakePortal(status("VALIDATING"), status("VALIDATING"), status("VALIDATING"))
        val failure = assertFailsWith<IllegalStateException> {
            fixture.await(portal, attempts = 3) { sleeps++ }
        }
        assertTrue(failure.message.orEmpty().contains("timed out"))
        assertEquals(3, sleeps)
    }

    @Test
    fun `release validates releases exact deployment and waits for published`() = withFixture { fixture ->
        fixture.prepare(FakePortal(CentralPortalResponse(201, ID)), allow = true)
        val portal = FakePortal(
            status("VALIDATED"),
            CentralPortalResponse(204, ""),
            status("PUBLISHED"),
        )

        fixture.release(portal)

        assertEquals(
            listOf("$API/status?id=$ID", "$API/deployment/$ID", "$API/status?id=$ID"),
            portal.requests.map { it.url },
        )
        assertEquals("PUBLISHED", fixture.record.readReleaseObject().releaseString("deploymentState"))
    }

    @Test
    fun `already published deployment succeeds without another release request`() = withFixture { fixture ->
        fixture.prepare(FakePortal(CentralPortalResponse(201, ID)), allow = true)
        fixture.setState("PUBLISHED")
        val portal = FakePortal(status("PUBLISHED"))
        fixture.release(portal)
        assertEquals(listOf("$API/status?id=$ID"), portal.requests.map { it.url })
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val directory = createTempDirectory("central-portal").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }

    private class Fixture(directory: File) {
        val bundle = directory.resolve("bundle.zip").apply { writeText("bundle bytes") }
        val candidate = directory.resolve("candidate.json")
        val record = directory.resolve("state/deployment.json")
        val name: String

        init {
            val hash = bundle.releaseDigest()
            name = "codex-agent-0.2.0-${COMMIT.take(12)}-${hash.take(12)}"
            fun record(fileName: String) = buildJsonObject {
                put("fileName", JsonPrimitive(fileName))
                put("bytes", JsonPrimitive(1))
                put("sha256", JsonPrimitive("0".repeat(64)))
            }
            candidate.atomicWriteJson(buildJsonObject {
                put("schemaVersion", JsonPrimitive(3))
                put("version", JsonPrimitive("0.2.0"))
                put("releaseTag", JsonPrimitive("v0.2.0"))
                put("candidateCommit", JsonPrimitive(COMMIT))
                put("protectedCandidate", JsonPrimitive(true))
                put("artifacts", buildJsonObject {
                    put("swiftPackage", buildJsonObject {
                        record("CodexAgent-0.2.0.xcframework.zip").forEach { (key, value) -> put(key, value) }
                        put("swiftPmChecksum", JsonPrimitive("0".repeat(64)))
                        put("members", buildJsonArray {})
                    })
                    put("centralBundle", bundle.releaseRecord())
                })
                put("evidence", buildJsonObject {
                    put("swiftPmProof", record("swiftpm-proof.json"))
                    put("centralBundleInventory", record("central-bundle.json"))
                    put("mavenInventory", record("maven-inventory.json"))
                    put("cleanKmpConsumer", record("kmp-consumer.json"))
                    put("androidRuntime", record("android-evidence.json"))
                    put("privacyAudit", record("privacy-audit.json"))
                    put("artifactMetrics", record("artifact-metrics.json"))
                    put("resourceMeasurements", buildJsonArray { add(record("resource-measurement.json")) })
                })
                put("policies", buildJsonObject {
                    put("approvals", record("publication-approvals.json"))
                    put("privacyManifest", record("PrivacyInfo.xcprivacy"))
                    put("privacyDataFlowReview", record("privacy-data-flow-review.json"))
                    put("packageSwift", record("Package.swift"))
                })
            })
        }

        fun prepare(portal: FakePortal, allow: Boolean = false) = prepare(portal::send, allow)
        fun prepare(sender: (CentralPortalRequest) -> CentralPortalResponse, allow: Boolean = false) =
            prepareCentralDeployment(bundle, candidate, record, API, "user", "password", allow, sender)

        fun await(portal: FakePortal, attempts: Int = 120, sleeper: (Long) -> Unit = {}) =
            awaitCentralValidation(bundle, candidate, record, API, "user", "password", attempts, 10, portal::send, sleeper)

        fun release(portal: FakePortal) =
            releaseCentralDeployment(bundle, candidate, record, API, "user", "password", 10, 0, portal::send) {}

        fun setState(state: String) = mutateRecord("deploymentState", state)

        fun mutateRecord(field: String, value: String) {
            val values = record.readReleaseObject().toMutableMap()
            values[field] = JsonPrimitive(value)
            record.atomicWriteJson(JsonObject(values))
        }
    }

    private class FakePortal(vararg responses: CentralPortalResponse) {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<CentralPortalRequest>()
        fun send(request: CentralPortalRequest): CentralPortalResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    companion object {
        private const val API = "https://central.example/api/v1/publisher"
        private const val ID = "28570f16-da32-4c14-bd2e-c1acc0782365"
        private const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        private fun status(state: String) = CentralPortalResponse(
            200,
            """{"deploymentId":"$ID","deploymentName":"codex-agent-0.2.0-${COMMIT.take(12)}-${"bundle bytes".encodeToByteArray().let { bytes -> java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }.take(12)}","deploymentState":"$state"}""",
        )
    }
}
