import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

class CentralPortalContentVerificationTest {
    @Test
    fun `pending discovery records identity without transfer and resumes through validation`() = withCentralFixture { fixture ->
        val preparePortal = FakePortal(deployments(deployment(state = "PENDING")), status("PENDING"))

        fixture.prepare(preparePortal)

        assertEquals("PENDING", fixture.record.readReleaseObject().releaseString("deploymentState"))
        assertNull(fixture.record.readReleaseObject().releaseStringOrNull("remoteBundleVerifiedSha256"))
        assertTrue(preparePortal.requests.none { it.url.contains("/upload") || it.url.contains("/download/") })

        val awaitPortal = FakePortal(status("VALIDATING"), status("VALIDATED"), *downloads().toTypedArray())
        fixture.await(awaitPortal)

        assertEquals("VALIDATED", fixture.record.readReleaseObject().releaseString("deploymentState"))
        assertEquals(fixture.bundle.releaseDigest(), fixture.record.readReleaseObject().releaseString("remoteBundleVerifiedSha256"))
        assertTrue(awaitPortal.requests.none { it.url.contains("/upload") })
    }

    @Test
    fun `exact PURLs and all expected file bytes establish the available API proof`() = withCentralFixture { fixture ->
        val portal = FakePortal(deployments(deployment()), status("VALIDATED"), *downloads().toTypedArray())
        fixture.prepare(portal)

        val record = fixture.record.readReleaseObject()
        assertEquals(fixture.bundle.releaseDigest(), record.releaseString("remoteBundleVerifiedSha256"))
        assertEquals(3, portal.requests.count { it.url.contains("/download/") })
        var requests = 0
        fixture.prepare(sender = { requests++; error("verified deployment must be reused") })
        assertEquals(0, requests)
    }

    @Test
    fun `missing or extra status PURL blocks before file downloads`() = withCentralFixture { fixture ->
        fixture.prepare(uploadPortal(), allow = true)
        listOf(emptyList(), listOf(CENTRAL_PURL, "pkg:maven/io.github.example/extra@0.2.0")).forEach { purls ->
            fixture.setState("PENDING")
            val portal = FakePortal(status("VALIDATED", purls = purls))

            assertFailsWith<IllegalStateException> { fixture.await(portal) }

            assertTrue(portal.requests.none { it.url.contains("/download/") })
        }
    }

    @Test
    fun `schema two forged proof is discarded and unknown schema is rejected`() = withCentralFixture { fixture ->
        fixture.prepare(uploadPortal(), allow = true)
        fixture.setState("VALIDATED")
        fixture.mutateRecord("schemaVersion", JsonPrimitive(2))
        fixture.mutateRecord("remoteBundleVerifiedSha256", fixture.bundle.releaseDigest())
        val portal = FakePortal(status("VALIDATED"), *downloads().toTypedArray())

        fixture.prepare(portal)

        assertEquals(4, portal.requests.size)
        assertEquals(3, fixture.record.readReleaseObject().releaseInt("schemaVersion"))
        fixture.mutateRecord("schemaVersion", JsonPrimitive(99))
        var requests = 0
        assertFailsWith<IllegalStateException> {
            fixture.prepare(sender = { requests++; error("unknown schema must fail before network") })
        }
        assertEquals(0, requests)
    }

    @Test
    fun `schema three requires its proof field even when unverified`() = withCentralFixture { fixture ->
        fixture.prepare(uploadPortal(), allow = true)
        val values = fixture.record.readReleaseObject().toMutableMap().apply {
            remove("remoteBundleVerifiedSha256")
        }
        fixture.record.atomicWriteJson(kotlinx.serialization.json.JsonObject(values))

        assertFailsWith<IllegalStateException> { fixture.prepare(sender = { error("network must not be reached") }) }
    }

    @Test
    fun `tampered or missing remote file cannot establish proof`() {
        listOf(
            CentralPortalResponse(200, "tampered"),
            CentralPortalResponse(404, "missing"),
        ).forEach { badDownload -> withCentralFixture { fixture ->
            val portal = FakePortal(deployments(deployment()), status("VALIDATED"), badDownload)

            assertFailsWith<IllegalStateException> { fixture.prepare(portal) }

            assertNull(fixture.record.readReleaseObject().releaseStringOrNull("remoteBundleVerifiedSha256"))
            assertTrue(portal.requests.none { it.url.contains("/upload") })
        } }
    }

    @Test
    fun `unsafe and duplicate ZIP entries fail before any download`() {
        val bundles = listOf(
            centralZip(listOf(
                CENTRAL_ANDROID_AAR_ENTRY to CENTRAL_ANDROID_AAR_BYTES,
                "../bad.txt" to "bad".encodeToByteArray(),
            )),
            duplicateCentralZip(),
        )
        bundles.forEach { bundle -> withCentralFixture(bundle) { fixture ->
            val portal = FakePortal(
                deployments(deployment(name = fixture.name)),
                status("VALIDATED", name = fixture.name),
            )

            assertFailsWith<IllegalStateException> { fixture.prepare(portal) }

            assertTrue(portal.requests.none { it.url.contains("/download/") || it.url.contains("/upload") })
        } }
    }

    @Test
    fun `same-coordinate tampering after substring collision is rejected by file bytes`() = withCentralFixture { fixture ->
        val portal = FakePortal(
            deployments(
                deployment(id = "98570f16-da32-4c14-bd2e-c1acc0782365", name = "copy-${fixture.name}"),
                deployment(name = fixture.name),
            ),
            status("VALIDATED", name = fixture.name),
            CentralPortalResponse(200, "wrong bytes"),
        )

        assertFailsWith<IllegalStateException> { fixture.prepare(portal) }

        assertEquals(CENTRAL_ID, fixture.record.readReleaseObject().releaseString("deploymentId"))
        assertNull(fixture.record.readReleaseObject().releaseStringOrNull("remoteBundleVerifiedSha256"))
        assertTrue(portal.requests.none { it.url.contains("/upload") })
    }

    @Test
    fun `release refuses unverified deployment when remote content is unavailable`() = withCentralFixture { fixture ->
        fixture.prepare(uploadPortal(), allow = true)
        val portal = FakePortal(status("VALIDATED"), CentralPortalResponse(404, "missing"))

        assertFailsWith<IllegalStateException> { fixture.release(portal) }

        assertFalse(portal.requests.any { it.url == "$CENTRAL_API/deployment/$CENTRAL_ID" })
        assertNull(fixture.record.readReleaseObject().releaseStringOrNull("remoteBundleVerifiedSha256"))
    }

    @Test
    fun `verified proof permits release without downloading bundle twice`() = withCentralFixture { fixture ->
        fixture.prepare(FakePortal(deployments(deployment()), status("VALIDATED"), *downloads().toTypedArray()))
        val portal = FakePortal(status("VALIDATED"), CentralPortalResponse(204, ""), status("PUBLISHED"))

        fixture.release(portal)

        assertTrue(portal.requests.none { it.url.contains("/download/") })
        assertEquals("PUBLISHED", fixture.record.readReleaseObject().releaseString("deploymentState"))
    }
}
