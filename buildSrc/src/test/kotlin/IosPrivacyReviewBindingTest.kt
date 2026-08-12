import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class IosPrivacyReviewBindingTest {
    @Test
    fun `stable reviewed semantics rebind only the evidence hash deterministically`() = withFixture(
        signals = IosPrivacySignals(emptySet(), setOf("attributesOfFileSystemForPath:")),
        decisions = listOf(diskDecision("declared", listOf("E174.1"), api("string", "attributesOfFileSystemForPath:"))),
        declarations = mapOf(DISK to listOf("E174.1")),
    ) { fixture ->
        fixture.bind()
        val first = fixture.output.readBytes()
        val expected = JsonObject(fixture.template.readReleaseObject() +
            ("evidenceSha256" to JsonPrimitive(fixture.evidence.releaseDigest())))
        assertEquals(expected, fixture.output.readReleaseObject())
        fixture.bind()
        assertTrue(first.contentEquals(fixture.output.readBytes()))

        verifyIosPrivacyAudit(fixture.policy, fixture.evidence, fixture.output, fixture.audit)
        verifyBoundIosPrivacyReview(fixture.template, fixture.output, fixture.audit)
        val payload = fixture.root.resolve("payload").apply { mkdirs() }
        fixture.output.copyTo(payload.resolve(fixture.output.name))
        fixture.audit.copyTo(payload.resolve(fixture.audit.name))
        val candidate = buildJsonObject {
            put("policies", buildJsonObject {
                put("privacyRequiredReasonReviews", fixture.output.releaseRecord())
            })
            put("evidence", buildJsonObject { put("privacyAudit", fixture.audit.releaseRecord()) })
        }
        assertEquals(payload.resolve(fixture.output.name).canonicalFile, resolveCandidatePrivacyReview(
            candidate, payload, null, fixture.template,
        ))
        val tampered = JsonObject(fixture.output.readReleaseObject() +
            ("evidenceSha256" to JsonPrimitive("f".repeat(64))))
        fixture.output.atomicWriteJson(tampered)
        assertFailsWith<IllegalStateException> {
            verifyBoundIosPrivacyReview(fixture.template, fixture.output, fixture.audit)
        }
    }

    @Test
    fun `direct and mixed source escalation cannot inherit a string-only decision`() {
        listOf(
            IosPrivacySignals(setOf("_fstatfs"), emptySet()),
            IosPrivacySignals(setOf("_fstatfs"), setOf("attributesOfFileSystemForPath:")),
        ).forEach { signals -> withFixture(
            signals,
            listOf(diskDecision("notRequired", emptyList(), api("string", "attributesOfFileSystemForPath:"))),
            emptyMap(),
        ) { fixture ->
            val failure = assertFailsWith<IllegalStateException> { fixture.bind() }
            assertTrue(failure.message.orEmpty().contains("Reviewed API inventory changed"))
        } }
    }

    @Test
    fun `new API in an already reviewed category requires review`() = withFixture(
        IosPrivacySignals(emptySet(), setOf("NSUserDefaults", "CFPreferences")),
        listOf(userDefaultsDecision(api("string", "NSUserDefaults"))),
        emptyMap(),
    ) { fixture ->
        assertFailsWith<IllegalStateException> { fixture.bind() }
    }

    @Test
    fun `new unreviewed category requires review`() = withFixture(
        IosPrivacySignals(emptySet(), setOf("attributesOfFileSystemForPath:", "systemUptime")),
        listOf(diskDecision("declared", listOf("E174.1"), api("string", "attributesOfFileSystemForPath:"))),
        mapOf(DISK to listOf("E174.1")),
    ) { fixture ->
        val failure = assertFailsWith<IllegalStateException> { fixture.bind() }
        assertTrue(failure.message.orEmpty().contains("Current privacy findings differ"))
    }

    @Test
    fun `policy drift invalidates the decision template`() = withFixture(
        IosPrivacySignals(emptySet(), setOf("attributesOfFileSystemForPath:")),
        listOf(diskDecision("declared", listOf("E174.1"), api("string", "attributesOfFileSystemForPath:"))),
        mapOf(DISK to listOf("E174.1")),
    ) { fixture ->
        fixture.policy.appendText(" ")
        assertFailsWith<IllegalStateException> { fixture.bind() }
    }

    @Test
    fun `declared decision must match the current manifest`() = withFixture(
        IosPrivacySignals(emptySet(), setOf("attributesOfFileSystemForPath:")),
        listOf(diskDecision("declared", listOf("E174.1"), api("string", "attributesOfFileSystemForPath:"))),
        mapOf(DISK to listOf("85F4.1")),
    ) { fixture ->
        val failure = assertFailsWith<IllegalStateException> { fixture.bind() }
        assertTrue(failure.message.orEmpty().contains("reason mismatch"))
    }

    private fun withFixture(
        signals: IosPrivacySignals,
        decisions: List<JsonObject>,
        declarations: Map<String, List<String>>,
        block: (Fixture) -> Unit,
    ) {
        val root = createTempDirectory("privacy-review-binding").toFile()
        try {
            val manifest = root.resolve("PrivacyInfo.xcprivacy").apply { writeText(manifest(declarations)) }
            val xcframework = root.resolve("CodexAgent.xcframework")
            listOf("ios-arm64", "ios-arm64-simulator").forEach { slice ->
                val framework = xcframework.resolve("$slice/CodexAgent.framework").apply { mkdirs() }
                writeArchive(framework.resolve("CodexAgent"), standard("fixture.o", "object"))
                manifest.copyTo(framework.resolve("PrivacyInfo.xcprivacy"))
            }
            val archived = root.resolve("Sample.xcarchive/Products/Applications/Sample.app").apply { mkdirs() }
            manifest.copyTo(archived.resolve("PrivacyInfo.xcprivacy"))
            val policy = root.resolve("policy.json")
            val evidence = root.resolve("evidence.json")
            generateIosPrivacyEvidence(
                xcframework,
                root.resolve("Sample.xcarchive"),
                manifest,
                root.resolve("data-flow.json").apply { writeText("{}") },
                policy,
                evidence,
                buildJsonArray {},
            ) { signals }
            val template = root.resolve("template.json").apply { atomicWriteJson(buildJsonObject {
                put("schemaVersion", JsonPrimitive(1))
                put("reviewStatus", JsonPrimitive("approved"))
                put("evidenceSha256", JsonPrimitive("0".repeat(64)))
                put("policySha256", JsonPrimitive(policy.releaseDigest()))
                put("reviews", JsonArray(decisions))
            }) }
            block(Fixture(root, template, policy, evidence))
        } finally {
            root.deleteRecursively()
        }
    }

    private data class Fixture(val root: File, val template: File, val policy: File, val evidence: File) {
        val output = root.resolve("privacy-required-reason-review.json")
        val audit = root.resolve("audit.json")
        fun bind() = generateIosPrivacyRequiredReasonReview(template, policy, evidence, output)
    }

    companion object {
        const val DISK = "NSPrivacyAccessedAPICategoryDiskSpace"
        const val USER_DEFAULTS = "NSPrivacyAccessedAPICategoryUserDefaults"
    }
}

private fun api(source: String, token: String) = source to token

private fun diskDecision(disposition: String, reasons: List<String>, vararg apis: Pair<String, String>) =
    decision(IosPrivacyReviewBindingTest.DISK, disposition, reasons, *apis)

private fun userDefaultsDecision(vararg apis: Pair<String, String>) =
    decision(IosPrivacyReviewBindingTest.USER_DEFAULTS, "notRequired", emptyList(), *apis)

private fun decision(
    category: String,
    disposition: String,
    reasons: List<String>,
    vararg apis: Pair<String, String>,
) = buildJsonObject {
    put("findingId", JsonPrimitive("category:$category"))
    put("reviewStatus", JsonPrimitive("approved"))
    put("disposition", JsonPrimitive(disposition))
    put("declarations", buildJsonArray {
        if (reasons.isNotEmpty()) add(buildJsonObject {
            put("category", JsonPrimitive(category)); put("reasons", reasons.jsonStrings())
        })
    })
    put("rationale", JsonPrimitive("Reviewed exact API and source inventory."))
    put("reviewedApis", buildJsonArray { apis.sortedWith(compareBy({ it.first }, { it.second })).forEach { (source, token) ->
        add(buildJsonObject { put("source", JsonPrimitive(source)); put("token", JsonPrimitive(token)) })
    } })
}

private fun manifest(declarations: Map<String, List<String>>) = """
    <?xml version="1.0" encoding="UTF-8"?>
    <plist version="1.0"><dict><key>NSPrivacyAccessedAPITypes</key><array>
    ${declarations.entries.joinToString("\n") { (category, reasons) ->
        "<dict><key>NSPrivacyAccessedAPIType</key><string>$category</string>" +
            "<key>NSPrivacyAccessedAPITypeReasons</key><array>" +
            reasons.joinToString("") { "<string>$it</string>" } + "</array></dict>"
    }}
    </array></dict></plist>
""".trimIndent().trimStart()
