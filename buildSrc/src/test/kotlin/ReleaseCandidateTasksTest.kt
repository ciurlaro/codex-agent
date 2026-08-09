import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReleaseCandidateTasksTest {
    @Test
    fun `pending privacy inventory is valid but both approvals remain blocking`() = withPrivacyFiles(
        approved = false,
        inventory = pendingInventory,
    ) { approvals, manifest, inventory ->
        val failure = assertFailsWith<IllegalStateException> {
            verifyPublicationReadiness(approvals, manifest, inventory)
        }
        assertTrue(failure.message.orEmpty().contains("privacyCollectedDataReviewApproved=false"))
        assertTrue(failure.message.orEmpty().contains("staticFrameworkGplDistributionApproved=false"))
    }

    @Test
    fun `approved privacy inventory accepts exact declared data types and purposes`() = withPrivacyFiles(
        approved = true,
        inventory = approvedInventory,
    ) { approvals, manifest, inventory ->
        verifyPublicationReadiness(approvals, manifest, inventory)
    }

    @Test
    fun `approved privacy inventory rejects incomplete terminal decision`() = withPrivacyFiles(
        approved = true,
        inventory = approvedInventory.replace("\"declare\"", "null"),
    ) { approvals, manifest, inventory ->
        assertFailsWith<IllegalStateException> {
            verifyPublicationReadiness(approvals, manifest, inventory)
        }
    }

    @Test
    fun `privacy approval rejects inventory hash mismatch`() = withPrivacyFiles(
        approved = true,
        inventory = approvedInventory,
    ) { approvals, manifest, inventory ->
        inventory.appendText("\n")
        val failure = assertFailsWith<IllegalStateException> {
            verifyPublicationReadiness(approvals, manifest, inventory)
        }
        assertTrue(failure.message.orEmpty().contains("inventory hash mismatch"))
    }

    @Test
    fun `ambiguous required reason API emits blocking manual review finding`() {
        withPrivacyAudit(reviews = "{\"schemaVersion\":1,\"reviews\":[]}") { manifest, symbols, reviews, packaging, audit ->
            symbols.writeText("_stat\n_getattrlist\n")
            val failure = assertFailsWith<IllegalStateException> {
                verifyPrivacyRequiredReasons(manifest, symbols, reviews, packaging, audit)
            }
            assertTrue(failure.message.orEmpty().contains("_getattrlist"))
            assertTrue(audit.readText().contains("blocking manual review required"))
        }
    }

    @Test
    fun `explicit ambiguous API review is accepted without guessing`() {
        val review = """
            {"schemaVersion":1,"reviews":[{"symbol":"_getattrlist","reviewStatus":"approved","disposition":"declared","category":"NSPrivacyAccessedAPICategoryFileTimestamp","reasons":["C617.1"],"rationale":"Reviewed final linked call site."}]}
        """.trimIndent()
        withPrivacyAudit(reviews = review) { manifest, symbols, reviews, packaging, audit ->
            symbols.writeText("_stat\n_getattrlist\n")
            verifyPrivacyRequiredReasons(manifest, symbols, reviews, packaging, audit)
            assertTrue(audit.readText().contains("explicitlyReviewedApiSymbols"))
        }
    }

    private fun withPrivacyFiles(
        approved: Boolean,
        inventory: String,
        block: (File, File, File) -> Unit,
    ) {
        val directory = createTempDirectory("privacy-approval").toFile()
        try {
            val manifest = directory.resolve("PrivacyInfo.xcprivacy").apply { writeText("manifest") }
            val inventoryFile = directory.resolve("inventory.json").apply { writeText(inventory) }
            val approvals = directory.resolve("approvals.json").apply {
                writeText(
                    """{"privacyCollectedDataReviewApproved":$approved,"staticFrameworkGplDistributionApproved":$approved,"privacyManifestSha256":"${manifest.sha256()}","privacyDataFlowInventorySha256":"${inventoryFile.sha256()}"}""",
                )
            }
            block(approvals, manifest, inventoryFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun withPrivacyAudit(
        reviews: String,
        block: (File, File, File, File, File) -> Unit,
    ) {
        val directory = createTempDirectory("privacy-audit").toFile()
        try {
            val manifest = directory.resolve("PrivacyInfo.xcprivacy").apply {
                writeText("NSPrivacyAccessedAPICategoryFileTimestamp C617.1")
            }
            val symbols = directory.resolve("symbols.txt")
            val reviewsFile = directory.resolve("reviews.json").apply { writeText(reviews) }
            val packaging = directory.resolve("packaging.json").apply { writeText("{\"frameworkManifests\":2}") }
            val audit = directory.resolve("audit.json")
            block(manifest, symbols, reviewsFile, packaging, audit)
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        private const val pendingInventory = """
            {"schemaVersion":1,"reviewStatus":"pending","terminalCollectedDataDecision":null,"appleCollectedDataTypes":[],"reviewedNoSdkDeclarationRationale":null}
        """
        private const val approvedInventory = """
            {"schemaVersion":1,"reviewStatus":"approved","terminalCollectedDataDecision":"declare","appleCollectedDataTypes":[{"appleDataType":"NSPrivacyCollectedDataTypeUserContent","purposes":["NSPrivacyCollectedDataTypePurposeAppFunctionality"]}],"reviewedNoSdkDeclarationRationale":null}
        """
    }
}

private fun File.sha256(): String = MessageDigest.getInstance("SHA-256").digest(readBytes())
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
