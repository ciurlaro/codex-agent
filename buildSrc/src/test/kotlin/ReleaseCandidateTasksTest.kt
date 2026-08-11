import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReleaseCandidateTasksTest {
    @Test
    fun `tracked release policy JSON names are versionless and never pending placeholders`() {
        val releaseDirectory = File(System.getProperty("user.dir")).parentFile.resolve("release")
        val forbidden = releaseDirectory.listFiles().orEmpty().filter { file ->
            file.extension == "json" && (
                Regex("""\d+\.\d+\.\d+""").containsMatchIn(file.name) ||
                    "pending" in file.name.lowercase()
                )
        }
        assertTrue(forbidden.isEmpty(), "Forbidden release JSON: ${forbidden.map(File::getName)}")
    }

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
    fun `approved privacy inventory accepts reviewed no SDK declaration decision`() = withPrivacyFiles(
        approved = true,
        inventory = approvedNoSdkInventory,
    ) { approvals, manifest, inventory ->
        verifyPublicationReadiness(approvals, manifest, inventory)
    }

    @Test
    fun `approved no SDK declaration rejects missing rationale`() = withPrivacyFiles(
        approved = true,
        inventory = approvedNoSdkInventory.replace("\"Reviewed SDK decision.\"", "null"),
    ) { approvals, manifest, inventory ->
        assertFailsWith<IllegalStateException> {
            verifyPublicationReadiness(approvals, manifest, inventory)
        }
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
            assertTrue(failure.message.orEmpty().contains("review hash mismatch"))
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
                    if (approved) {
                        """{"privacyCollectedDataReviewApproved":true,"staticFrameworkGplDistributionApproved":true,"privacyManifestSha256":"${manifest.sha256()}","privacyDataFlowReviewSha256":"${inventoryFile.sha256()}"}"""
                    } else {
                        """{"privacyCollectedDataReviewApproved":false,"staticFrameworkGplDistributionApproved":false,"privacyManifestSha256":null,"privacyDataFlowReviewSha256":null}"""
                    },
                )
            }
            block(approvals, manifest, inventoryFile)
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
        private const val approvedNoSdkInventory = """
            {"schemaVersion":1,"reviewStatus":"approved","terminalCollectedDataDecision":"noSdkDeclaration","appleCollectedDataTypes":[],"reviewedNoSdkDeclarationRationale":"Reviewed SDK decision."}
        """
    }
}

private fun File.sha256(): String = releaseDigest()
