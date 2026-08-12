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
                Regex("""\d+\.\d+\.\d+""").containsMatchIn(file.name) || "pending" in file.name.lowercase()
            )
        }
        assertTrue(forbidden.isEmpty(), "Forbidden release JSON: ${forbidden.map(File::getName)}")
    }

    @Test
    fun `pending privacy inventory is valid but privacy and iOS GPL approvals remain blocking`() = withFixture(
        privacyApproved = false,
        inventory = pendingInventory,
    ) { fixture ->
        val failure = assertFailsWith<IllegalStateException> { fixture.verify() }
        assertTrue(failure.message.orEmpty().contains("privacyCollectedDataReviewApproved=false"))
        assertTrue(failure.message.orEmpty().contains("staticFrameworkGplDistributionApproved=false"))
    }

    @Test
    fun `approved privacy inventory accepts exact declared data types and purposes`() = withFixture(
        privacyApproved = true,
        inventory = approvedInventory,
    ) { it.verify() }

    @Test
    fun `approved privacy inventory accepts reviewed no SDK declaration decision`() = withFixture(
        privacyApproved = true,
        inventory = approvedNoSdkInventory,
    ) { it.verify() }

    @Test
    fun `approved privacy inventory rejects incomplete decisions and hash drift`() {
        listOf(
            approvedNoSdkInventory.replace("\"Reviewed SDK decision.\"", "null"),
            approvedInventory.replace("\"declare\"", "null"),
        ).forEach { inventory -> withFixture(true, inventory) { fixture ->
            assertFailsWith<IllegalStateException> { fixture.verify() }
        } }
        withFixture(true, approvedInventory) { fixture ->
            fixture.inventory.appendText("\n")
            val failure = assertFailsWith<IllegalStateException> { fixture.verify() }
            assertTrue(failure.message.orEmpty().contains("review hash mismatch"))
        }
    }

    @Test
    fun `desktop GPL approval is separate and bound to manifest license and notice`() = withFixture(
        privacyApproved = true,
        inventory = approvedInventory,
        desktopApproved = false,
    ) { fixture ->
        val failure = assertFailsWith<IllegalStateException> { fixture.verify() }
        assertTrue(failure.message.orEmpty().contains("desktopBundledGplDistributionApproved=false"))
    }.also {
        withFixture(true, approvedInventory) { fixture ->
            listOf(fixture.desktopManifest, fixture.desktopLicense, fixture.desktopNotice).forEach { file ->
                val original = file.readBytes()
                file.appendText("drift")
                assertFailsWith<IllegalStateException> { fixture.verify() }
                file.writeBytes(original)
            }
        }
    }

    private fun withFixture(
        privacyApproved: Boolean,
        inventory: String,
        desktopApproved: Boolean = true,
        block: (Fixture) -> Unit,
    ) {
        val directory = createTempDirectory("publication-approval").toFile()
        try { block(Fixture(directory, privacyApproved, inventory, desktopApproved)) }
        finally { directory.deleteRecursively() }
    }

    private class Fixture(root: File, privacyApproved: Boolean, inventoryText: String, desktopApproved: Boolean) {
        val manifest = root.resolve("PrivacyInfo.xcprivacy").apply { writeText("manifest") }
        val inventory = root.resolve("inventory.json").apply { writeText(inventoryText) }
        val desktopManifest = writeTestDesktopDistributionManifest(root.resolve("desktop.json"), "f".repeat(64))
        val desktopLicense = root.resolve("LICENSE.txt").apply { writeText("license") }
        val desktopNotice = root.resolve("NOTICE.txt").apply { writeText("notice") }
        val approvals = writeTestPublicationApprovals(
            root.resolve("approvals.json"), desktopManifest, desktopLicense, desktopNotice,
            privacyApproved, privacyApproved, desktopApproved, manifest, inventory,
        )

        fun verify() = verifyPublicationReadiness(
            approvals, manifest, inventory, desktopManifest, desktopLicense, desktopNotice,
        )
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
