import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class CentralPortalTaskTest {
    @Test
    fun `prepare uploads USER_MANAGED once and immediately records pending identity`() = withFixture { fixture ->
        val portal = uploadPortal()

        fixture.prepare(portal, allow = true)

        assertEquals(2, portal.requests.size)
        assertEquals("GET", portal.requests.first().method)
        val upload = portal.requests.last()
        assertEquals("$CENTRAL_API/upload?publishingType=USER_MANAGED&name=$CENTRAL_NAME", upload.url)
        assertEquals("Bearer " + Base64.getEncoder().encodeToString("user:password".toByteArray(UTF_8)), upload.headers["Authorization"])
        assertTrue(upload.body.toString(UTF_8).contains("name=\"bundle\"; filename=\"bundle.zip\""))
        assertEquals(fixture.bundle, upload.bodyFile)
        assertEquals("PENDING", fixture.record.readReleaseObject().releaseString("deploymentState"))
        assertEquals(CENTRAL_ID, fixture.record.readReleaseObject().releaseString("deploymentId"))
        assertFalse(fixture.record.readText().contains("password"))
    }

    @Test
    fun `prepare reuses matching states with durable proof without network or duplicate upload`() = withFixture { fixture ->
        fixture.prepare(uploadPortal(), allow = true)
        fixture.mutateRecord("remoteBundleVerifiedSha256", fixture.bundle.releaseDigest())
        listOf("PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED").forEach { state ->
            fixture.setState(state)
            var requests = 0
            fixture.prepare(sender = { requests++; error("network must not be reached") })
            assertEquals(0, requests, state)
        }
    }

    @Test
    fun `prepare without durable record fails closed unless first upload is explicit`() = withFixture { fixture ->
        val portal = FakePortal(deployments())
        val failure = assertFailsWith<IllegalStateException> {
            fixture.prepare(portal)
        }
        assertTrue(failure.message.orEmpty().contains("refusing"))
        assertEquals(1, portal.requests.size)
        assertFalse(fixture.record.exists())
    }

    @Test
    fun `prepare recovers one exact deployment verifies it and never uploads`() = withFixture { fixture ->
        val portal = FakePortal(deployments(deployment()), status("VALIDATED"), *downloads().toTypedArray())

        fixture.prepare(portal)

        assertEquals(listOf("GET", "POST", "GET", "GET", "GET"), portal.requests.map { it.method })
        assertTrue(portal.requests.none { it.url.contains("/upload") })
        assertEquals(CENTRAL_ID, fixture.record.readReleaseObject().releaseString("deploymentId"))
        assertEquals("VALIDATED", fixture.record.readReleaseObject().releaseString("deploymentState"))
    }

    @Test
    fun `prepare rejects ambiguous or mismatched recovered deployments without upload`() = withFixture { fixture ->
        val invalid = listOf(
            deployments(deployment(), deployment(id = "98570f16-da32-4c14-bd2e-c1acc0782365")),
            deployments(deployment(id = "not-a-uuid")),
            deployments(deployment(state = "MYSTERY")),
            deployments(deployment(name = "wrong")),
        )
        invalid.forEach { response ->
            val portal = FakePortal(response)
            assertFailsWith<IllegalStateException> { fixture.prepare(portal) }
            assertTrue(portal.requests.none { it.url.contains("/upload") })
            assertFalse(fixture.record.exists())
        }
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
        fixture.prepare(uploadPortal(), allow = true)
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
            fixture.prepare(FakePortal(deployments(), CentralPortalResponse(201, "not-a-uuid")), allow = true)
        }
        assertFalse(fixture.record.exists())
    }

    @Test
    fun `await validation preserves state order and verifies returned identity`() = withFixture { fixture ->
        fixture.prepare(uploadPortal(), allow = true)
        var sleeps = 0
        val portal = FakePortal(
            status("PENDING"),
            status("VALIDATING"),
            status("VALIDATED"),
            *downloads().toTypedArray(),
        )

        fixture.await(portal) { assertEquals(10L, it); sleeps++ }

        assertEquals(6, portal.requests.size)
        assertEquals(2, sleeps)
        assertEquals("VALIDATED", fixture.record.readReleaseObject().releaseString("deploymentState"))
    }

    @Test
    fun `status id or name mismatch is blocking and is not recorded`() = withFixture { fixture ->
        fixture.prepare(uploadPortal(), allow = true)
        listOf(
            """{"deploymentId":"wrong","deploymentName":"${fixture.name}","deploymentState":"VALIDATED"}""",
            """{"deploymentId":"$CENTRAL_ID","deploymentName":"wrong","deploymentState":"VALIDATED"}""",
        ).forEach { body ->
            fixture.setState("PENDING")
            assertFailsWith<IllegalStateException> { fixture.await(FakePortal(CentralPortalResponse(200, body))) }
            assertEquals("PENDING", fixture.record.readReleaseObject().releaseString("deploymentState"))
        }
    }

    @Test
    fun `failed and unknown states are recorded then rejected`() = withFixture { fixture ->
        fixture.prepare(uploadPortal(), allow = true)
        listOf("FAILED", "MYSTERY").forEach { state ->
            fixture.setState("PENDING")
            assertFailsWith<IllegalStateException> { fixture.await(FakePortal(status(state))) }
            assertEquals(state, fixture.record.readReleaseObject().releaseString("deploymentState"))
        }
    }

    @Test
    fun `await timeout is bounded`() = withFixture { fixture ->
        fixture.prepare(uploadPortal(), allow = true)
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
        fixture.prepare(uploadPortal(), allow = true)
        val portal = FakePortal(
            status("VALIDATED"),
            *downloads().toTypedArray(),
            CentralPortalResponse(204, ""),
            status("PUBLISHED"),
        )

        fixture.release(portal)

        val urls = portal.requests.map { it.url }
        assertEquals(
            listOf(
                "$CENTRAL_API/deployment/$CENTRAL_ID/download/$CENTRAL_ANDROID_AAR_ENTRY",
                "$CENTRAL_API/deployment/$CENTRAL_ID/download/io/github/example/client/0.2.0/client-0.2.0.jar",
                "$CENTRAL_API/deployment/$CENTRAL_ID/download/io/github/example/client/0.2.0/client-0.2.0.pom",
            ),
            urls.filter { "/download/" in it }.sorted(),
        )
        assertEquals(
            listOf(
                "$CENTRAL_API/status?id=$CENTRAL_ID",
                "$CENTRAL_API/deployment/$CENTRAL_ID",
                "$CENTRAL_API/status?id=$CENTRAL_ID",
            ),
            urls.filterNot { "/download/" in it },
        )
        assertEquals("PUBLISHED", fixture.record.readReleaseObject().releaseString("deploymentState"))
    }

    @Test
    fun `already published deployment succeeds without another release request`() = withFixture { fixture ->
        fixture.prepare(uploadPortal(), allow = true)
        fixture.setState("PUBLISHED")
        val portal = FakePortal(status("PUBLISHED"), *downloads().toTypedArray())
        fixture.release(portal)
        assertEquals(4, portal.requests.size)
    }

    @Test
    fun `429 retries honor Retry-After through the injected sleeper`() = withFixture { fixture ->
        val portal = FakePortal(
            CentralPortalResponse(429, "busy", mapOf("Retry-After" to listOf("2"))),
            deployments(),
            CentralPortalResponse(201, CENTRAL_ID),
        )
        val sleeps = mutableListOf<Long>()

        fixture.prepare(portal::send, allow = true, sleeper = sleeps::add)

        assertEquals(listOf(2_000L), sleeps)
        assertEquals(portal.requests[0].url, portal.requests[1].url)
    }

    @Test
    fun `HTTP response buffering rejects bytes beyond its explicit limit`() {
        assertFailsWith<IllegalStateException> {
            readCentralResponseBytes(ByteArrayInputStream(ByteArray(5)), 4)
        }
    }

    @Test
    fun `digest downloads keep a bounded error body large enough for Retry-After handling`() {
        val request = CentralPortalRequest(
            "GET", "https://central.example/file", emptyMap(), responseByteLimit = 1, digestResponse = true,
        )

        assertEquals(1, request.effectiveResponseByteLimit(200))
        assertTrue(request.effectiveResponseByteLimit(429) > request.responseByteLimit)
    }

    private fun withFixture(block: (CentralFixture) -> Unit) = withCentralFixture(block = block)
}
