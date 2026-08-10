import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CentralPortalTaskTest {
    @Test
    fun `validate uploads a user-managed bundle and records validated state`() = withFixture { fixture ->
        val portal = FakePortal(
            CentralPortalResponse(201, "deployment-1\n"),
            CentralPortalResponse(200, """{"deploymentState":"VALIDATED"}"""),
        )

        fixture.execute("validate", portal)

        assertEquals(2, portal.requests.size)
        val upload = portal.requests[0]
        assertTrue(upload.url.startsWith("$API/upload?publishingType=USER_MANAGED&name=codex-agent-0.2.0-0123456789ab-"))
        assertEquals("POST", upload.method)
        assertEquals("Bearer " + Base64.getEncoder().encodeToString("user:password".toByteArray(UTF_8)), upload.headers["Authorization"])
        assertEquals("multipart/form-data; boundary=CodexAgentCentralPortalBoundary", upload.headers["Content-Type"])
        assertTrue(upload.body.toString(UTF_8).contains("\r\nContent-Disposition:"))
        assertTrue(upload.body.toString(UTF_8).contains("name=\"bundle\"; filename=\"bundle.zip\""))
        assertEquals("bundle bytes", upload.bodyFile?.readText())
        assertEquals("\r\n--CodexAgentCentralPortalBoundary--\r\n", upload.suffix.toString(UTF_8))

        val record = fixture.record.jsonObject()
        assertEquals("deployment-1", record["deploymentId"])
        assertEquals("VALIDATED", record["deploymentState"])
        assertEquals(upload.url.substringAfter("&name="), record["deploymentName"])
        assertFalse(fixture.record.readText().contains("user"))
        assertFalse(fixture.record.readText().contains("password"))
    }

    @Test
    fun `validate reuses a matching record without uploading again`() = withFixture { fixture ->
        fixture.seed()
        val portal = FakePortal(CentralPortalResponse(200, """{"deploymentState":"PUBLISHED"}"""))

        fixture.execute("validate", portal)

        assertEquals(listOf("$API/status?id=deployment-1"), portal.requests.map { it.url })
        assertEquals("PUBLISHED", fixture.record.jsonObject()["deploymentState"])
    }

    @Test
    fun `record identity and hash mismatches fail before network access`() {
        listOf("deploymentName", "candidateManifestSha256", "bundleSha256").forEach { field ->
            withFixture { fixture ->
                fixture.seed()
                fixture.record.mutate(field, "wrong")
                var networkRequests = 0

                assertFailsWith<IllegalStateException> {
                    fixture.execute("validate", sender = {
                        networkRequests++
                        error("network must not be reached")
                    })
                }
                assertEquals(0, networkRequests, field)
            }
        }
    }

    @Test
    fun `release requires an existing record and does not upload`() = withFixture { fixture ->
        var networkRequests = 0

        val failure = assertFailsWith<IllegalStateException> {
            fixture.execute("release", sender = {
                networkRequests++
                error("network must not be reached")
            })
        }

        assertTrue(failure.message.orEmpty().contains("existing deployment record"))
        assertEquals(0, networkRequests)
    }

    @Test
    fun `release validates then releases the same deployment and waits for published`() = withFixture { fixture ->
        fixture.seed()
        val portal = FakePortal(
            CentralPortalResponse(200, """{"deploymentState":"VALIDATED"}"""),
            CentralPortalResponse(204, ""),
            CentralPortalResponse(200, """{"deploymentState":"PUBLISHED"}"""),
        )

        fixture.execute("release", portal)

        assertEquals(
            listOf(
                "$API/status?id=deployment-1",
                "$API/deployment/deployment-1",
                "$API/status?id=deployment-1",
            ),
            portal.requests.map { it.url },
        )
        assertEquals("PUBLISHED", fixture.record.jsonObject()["deploymentState"])
    }

    @Test
    fun `already published deployment succeeds without another release request`() = withFixture { fixture ->
        fixture.seed()
        val portal = FakePortal(CentralPortalResponse(200, """{"deploymentState":"PUBLISHED"}"""))

        fixture.execute("release", portal)

        assertEquals(listOf("$API/status?id=deployment-1"), portal.requests.map { it.url })
    }

    @Test
    fun `failed deployment is recorded and reported`() = withFixture { fixture ->
        fixture.seed()
        val portal = FakePortal(CentralPortalResponse(200, """{"deploymentState":"FAILED","errors":["bad"]}"""))

        val failure = assertFailsWith<IllegalStateException> { fixture.execute("validate", portal) }

        assertTrue(failure.message.orEmpty().contains("deployment failed"))
        assertEquals("FAILED", fixture.record.jsonObject()["deploymentState"])
    }

    @Test
    fun `polling performs exactly 120 delayed attempts before timing out`() = withFixture { fixture ->
        fixture.seed()
        var requests = 0
        var sleeps = 0
        val sender: (CentralPortalRequest) -> CentralPortalResponse = {
            requests++
            CentralPortalResponse(200, """{"deploymentState":"VALIDATING"}""")
        }

        val failure = assertFailsWith<IllegalStateException> {
            fixture.execute("validate", sender) { delay ->
                assertEquals(10_000L, delay)
                sleeps++
            }
        }

        assertTrue(failure.message.orEmpty().contains("timed out waiting for VALIDATED"))
        assertEquals(120, requests)
        assertEquals(120, sleeps)
    }

    @Test
    fun `HTTP failure exposes status and response body`() = withFixture { fixture ->
        val failure = assertFailsWith<IllegalStateException> {
            fixture.execute(
                "validate",
                sender = { CentralPortalResponse(401, "invalid credentials") },
            )
        }

        assertTrue(failure.message.orEmpty().contains("401"))
        assertTrue(failure.message.orEmpty().contains("invalid credentials"))
        assertFalse(fixture.record.exists())
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val directory = createTempDirectory("central-portal").toFile()
        try {
            block(
                Fixture(
                    bundle = directory.resolve("bundle.zip").apply { writeText("bundle bytes") },
                    candidate = directory.resolve("candidate.json").apply {
                        writeText("""{"version":"0.2.0","candidateCommit":"0123456789abcdef0123456789abcdef01234567"}""")
                    },
                    record = directory.resolve("state/deployment.json"),
                ),
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class Fixture(val bundle: File, val candidate: File, val record: File) {
        fun execute(
            mode: String,
            portal: FakePortal,
            sleeper: (Long) -> Unit = {},
        ) = execute(mode, portal::send, sleeper)

        fun execute(
            mode: String,
            sender: (CentralPortalRequest) -> CentralPortalResponse,
            sleeper: (Long) -> Unit = {},
        ) = executeCentralPortal(
            mode = mode,
            bundle = bundle,
            candidate = candidate,
            record = record,
            api = API,
            username = "user",
            password = "password",
            sender = sender,
            sleeper = sleeper,
        )

        fun seed() {
            execute(
                "validate",
                FakePortal(
                    CentralPortalResponse(201, "deployment-1"),
                    CentralPortalResponse(200, """{"deploymentState":"VALIDATED"}"""),
                ),
            )
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
    }
}

@Suppress("UNCHECKED_CAST")
private fun File.jsonObject(): Map<String, Any?> = JsonSlurper().parse(this) as Map<String, Any?>

private fun File.mutate(field: String, value: String) {
    val json = jsonObject().toMutableMap()
    json[field] = value
    writeText(JsonOutput.toJson(json))
}
