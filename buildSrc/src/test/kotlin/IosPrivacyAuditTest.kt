import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class IosPrivacyAuditTest {
    @Test
    fun `maintains all five source-specific categories and blocks ambiguous symbols`() {
        val signals = IosPrivacySignals(
            setOf("_stat", "_mach_absolute_time", "_statfs", "_getattrlist"),
            setOf("activeInputModes", "NSUserDefaults"),
        )
        val (hits, ambiguous) = IosPrivacyPolicy.classify(signals)
        assertEquals(IosPrivacyPolicy.categories.map { it.name }.toSet(), hits.map { it.category }.toSet())
        assertEquals(setOf("_getattrlist"), ambiguous)
        assertTrue(IosPrivacyPolicy.classify(IosPrivacySignals(emptySet(), setOf("_stat"))).first.isEmpty())
    }

    @Test
    fun `complete evidence verifies the reviewed manifest and archive placement`() = withFixture(
        manifest(mapOf(FILE_TIMESTAMP to listOf("C617.1"))),
        IosPrivacySignals(setOf("_stat"), emptySet()),
    ) { fixture ->
        verifyIosPrivacyAudit(fixture.policy, fixture.evidence, null, fixture.audit)
        assertTrue(fixture.audit.readReleaseObject().releaseBoolean("passed"))
        val evidence = fixture.evidence.readReleaseObject()
        assertEquals(2, evidence.releaseArray("slices").size)
        assertEquals(2, evidence.releaseArray("frameworkManifests").size)
    }

    @Test
    fun `ambiguous finding requires an exact hash-bound review`() = withFixture(
        manifest(mapOf(FILE_TIMESTAMP to listOf("C617.1"))),
        IosPrivacySignals(setOf("_stat", "_getattrlist"), emptySet()),
    ) { fixture ->
        assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, null, fixture.audit)
        }
        assertTrue(fixture.audit.readText().contains("ambiguous:_getattrlist"))
        val review = fixture.root.resolve("review.json")
        writeReview(review, fixture, "ambiguous:_getattrlist", "notRequired", JsonArray(emptyList()))
        verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
    }

    @Test
    fun `manual category review requires allowed reasons and an exact declaration`() = withFixture(
        manifest(mapOf(SYSTEM_BOOT to listOf("35F9.1"))),
        IosPrivacySignals(setOf("_mach_absolute_time"), emptySet()),
    ) { fixture ->
        val review = fixture.root.resolve("review.json")
        val declaration = buildJsonArray { add(buildJsonObject {
            put("category", JsonPrimitive(SYSTEM_BOOT))
            put("reasons", listOf("35F9.1").jsonStrings())
        }) }
        writeReview(review, fixture, "category:$SYSTEM_BOOT", "declared", declaration)
        verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
    }

    @Test
    fun `string-only category accepts exact hash-bound not-required review`() = withFixture(
        manifest(emptyMap()),
        IosPrivacySignals(emptySet(), setOf("systemUptime")),
    ) { fixture ->
        val review = fixture.root.resolve("review.json")
        writeReview(review, fixture, "category:$SYSTEM_BOOT", "notRequired", JsonArray(emptyList()))
        verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
        assertTrue(fixture.audit.readReleaseObject().releaseBoolean("passed"))
    }

    @Test
    fun `not-required category rejects direct symbol evidence`() = withFixture(
        manifest(emptyMap()),
        IosPrivacySignals(setOf("_mach_absolute_time"), emptySet()),
    ) { fixture ->
        val review = fixture.root.resolve("review.json")
        writeReview(review, fixture, "category:$SYSTEM_BOOT", "notRequired", JsonArray(emptyList()))
        val failure = assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
        }
        assertTrue(failure.message.orEmpty().contains("requires string-only category evidence"))
    }

    @Test
    fun `not-required category rejects mixed symbol and string evidence`() = withFixture(
        manifest(emptyMap()),
        IosPrivacySignals(setOf("_mach_absolute_time"), setOf("systemUptime")),
    ) { fixture ->
        val review = fixture.root.resolve("review.json")
        writeReview(review, fixture, "category:$SYSTEM_BOOT", "notRequired", JsonArray(emptyList()))
        val failure = assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
        }
        assertTrue(failure.message.orEmpty().contains("sources=[string, undefined-symbol]"))
    }

    @Test
    fun `string-only not-required review rejects stale evidence or policy hash`() = withFixture(
        manifest(emptyMap()),
        IosPrivacySignals(emptySet(), setOf("systemUptime")),
    ) { fixture ->
        val review = fixture.root.resolve("review.json")
        writeReview(
            review,
            fixture,
            "category:$SYSTEM_BOOT",
            "notRequired",
            JsonArray(emptyList()),
            evidenceHash = "0".repeat(64),
        )
        val failure = assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
        }
        assertTrue(failure.message.orEmpty().contains("review evidence hash mismatch"))
        writeReview(
            review,
            fixture,
            "category:$SYSTEM_BOOT",
            "notRequired",
            JsonArray(emptyList()),
            policyHash = "0".repeat(64),
        )
        val policyFailure = assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
        }
        assertTrue(policyFailure.message.orEmpty().contains("review policy hash mismatch"))
    }

    @Test
    fun `string-only not-required review makes a declaration unnecessary`() = withFixture(
        manifest(mapOf(SYSTEM_BOOT to listOf("35F9.1"))),
        IosPrivacySignals(emptySet(), setOf("systemUptime")),
    ) { fixture ->
        val review = fixture.root.resolve("review.json")
        writeReview(review, fixture, "category:$SYSTEM_BOOT", "notRequired", JsonArray(emptyList()))
        val failure = assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
        }
        assertTrue(failure.message.orEmpty().contains("unnecessary declaration: $SYSTEM_BOOT"))
    }

    @Test
    fun `string-only not-required review requires a rationale`() = withFixture(
        manifest(emptyMap()),
        IosPrivacySignals(emptySet(), setOf("systemUptime")),
    ) { fixture ->
        val review = fixture.root.resolve("review.json")
        writeReview(
            review,
            fixture,
            "category:$SYSTEM_BOOT",
            "notRequired",
            JsonArray(emptyList()),
            rationale = " ",
        )
        val failure = assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
        }
        assertTrue(failure.message.orEmpty().contains("review rationale is missing"))
    }

    @Test
    fun `rejects stale duplicate extra and reason-mismatched reviews`() = withFixture(
        manifest(mapOf(SYSTEM_BOOT to listOf("35F9.1"))),
        IosPrivacySignals(setOf("_mach_absolute_time"), emptySet()),
    ) { fixture ->
        val review = fixture.root.resolve("review.json")
        val invalidDeclaration = buildJsonArray { add(buildJsonObject {
            put("category", JsonPrimitive(SYSTEM_BOOT))
            put("reasons", listOf("not-allowed").jsonStrings())
        }) }
        writeReview(review, fixture, "category:$SYSTEM_BOOT", "declared", invalidDeclaration, evidenceHash = "0".repeat(64))
        val failure = assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
        }
        assertTrue(failure.message.orEmpty().contains("hash mismatch"))
        assertTrue(fixture.audit.readText().contains("review reasons are invalid"))
    }

    @Test
    fun `rejects duplicate and extra review findings`() = withFixture(
        manifest(mapOf(FILE_TIMESTAMP to listOf("C617.1"))),
        IosPrivacySignals(setOf("_stat", "_getattrlist"), emptySet()),
    ) { fixture ->
        val review = fixture.root.resolve("review.json")
        fun entry(finding: String) = buildJsonObject {
            put("findingId", JsonPrimitive(finding))
            put("reviewStatus", JsonPrimitive("approved"))
            put("disposition", JsonPrimitive("notRequired"))
            put("declarations", JsonArray(emptyList()))
            put("rationale", JsonPrimitive("Reviewed exact static-member evidence."))
        }
        fun record(vararg findings: String) = buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("reviewStatus", JsonPrimitive("approved"))
            put("evidenceSha256", JsonPrimitive(fixture.evidence.releaseDigest()))
            put("policySha256", JsonPrimitive(fixture.policy.releaseDigest()))
            put("reviews", buildJsonArray { findings.forEach { add(entry(it)) } })
        }
        review.atomicWriteJson(record("ambiguous:_getattrlist", "ambiguous:_getattrlist"))
        assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
        }
        assertTrue(fixture.audit.readText().contains("duplicate required-reason review finding"))
        review.atomicWriteJson(record("ambiguous:_getattrlist", "ambiguous:_stale"))
        assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, review, fixture.audit)
        }
        assertTrue(fixture.audit.readText().contains("stale required-reason review"))
    }

    @Test
    fun `rejects unnecessary declarations and missing archived placement`() = withFixture(
        manifest(mapOf(FILE_TIMESTAMP to listOf("C617.1"), SYSTEM_BOOT to listOf("35F9.1"))),
        IosPrivacySignals(setOf("_stat"), emptySet()),
        copyArchivedManifest = false,
    ) { fixture ->
        val failure = assertFailsWith<IllegalStateException> {
            verifyIosPrivacyAudit(fixture.policy, fixture.evidence, null, fixture.audit)
        }
        assertTrue(failure.message.orEmpty().contains("unnecessary declaration"))
        assertTrue(failure.message.orEmpty().contains("archived sample"))
    }

    private fun withFixture(
        manifestText: String,
        signals: IosPrivacySignals,
        copyArchivedManifest: Boolean = true,
        block: (Fixture) -> Unit,
    ) {
        val root = createTempDirectory("privacy-audit").toFile()
        try {
            val manifest = root.resolve("PrivacyInfo.xcprivacy").apply { writeText(manifestText) }
            val xcframework = root.resolve("CodexAgent.xcframework")
            listOf("ios-arm64", "ios-arm64-simulator").forEach { slice ->
                val framework = xcframework.resolve("$slice/CodexAgent.framework").apply { mkdirs() }
                writeArchive(framework.resolve("CodexAgent"), standard("fixture.o", "object"))
                manifest.copyTo(framework.resolve("PrivacyInfo.xcprivacy"))
            }
            val archive = root.resolve("Sample.xcarchive")
            if (copyArchivedManifest) {
                val app = archive.resolve("Products/Applications/Sample.app").apply { mkdirs() }
                manifest.copyTo(app.resolve("PrivacyInfo.xcprivacy"))
            }
            val dataFlow = root.resolve("privacy-data-flow-review.json").apply { writeText("{}") }
            val fixture = Fixture(root, root.resolve("policy.json"), root.resolve("evidence.json"), root.resolve("audit.json"))
            generateIosPrivacyEvidence(
                xcframework, archive, manifest, dataFlow, fixture.policy, fixture.evidence,
                buildJsonArray {},
            ) { signals }
            block(fixture)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeReview(
        file: File,
        fixture: Fixture,
        finding: String,
        disposition: String,
        declarations: JsonArray,
        evidenceHash: String = fixture.evidence.releaseDigest(),
        policyHash: String = fixture.policy.releaseDigest(),
        rationale: String = "Reviewed exact static-member evidence.",
    ) = file.atomicWriteJson(buildJsonObject {
        put("schemaVersion", JsonPrimitive(1))
        put("reviewStatus", JsonPrimitive("approved"))
        put("evidenceSha256", JsonPrimitive(evidenceHash))
        put("policySha256", JsonPrimitive(policyHash))
        put("reviews", buildJsonArray { add(buildJsonObject {
            put("findingId", JsonPrimitive(finding))
            put("reviewStatus", JsonPrimitive("approved"))
            put("disposition", JsonPrimitive(disposition))
            put("declarations", declarations)
            put("rationale", JsonPrimitive(rationale))
        }) })
    })

    private data class Fixture(val root: File, val policy: File, val evidence: File, val audit: File)

    companion object {
        const val FILE_TIMESTAMP = "NSPrivacyAccessedAPICategoryFileTimestamp"
        const val SYSTEM_BOOT = "NSPrivacyAccessedAPICategorySystemBootTime"
    }
}

private fun manifest(declarations: Map<String, List<String>>) = """
    <?xml version="1.0" encoding="UTF-8"?>
    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
    <plist version="1.0"><dict><key>NSPrivacyAccessedAPITypes</key><array>
    ${declarations.entries.joinToString("\n") { (category, reasons) ->
        "<dict><key>NSPrivacyAccessedAPIType</key><string>$category</string>" +
            "<key>NSPrivacyAccessedAPITypeReasons</key><array>" +
            reasons.joinToString("") { "<string>$it</string>" } + "</array></dict>"
    }}
    </array></dict></plist>
""".trimIndent().trimStart()
