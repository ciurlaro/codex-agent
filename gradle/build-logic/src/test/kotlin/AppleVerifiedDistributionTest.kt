import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppleVerifiedDistributionTest {
    @Test
    fun `exact verified distribution and transported native evidence validate`() = fixture().use {
        val inventory = it.verify()
        assertEquals(appleVerifiedArtifactNames("0.2.0"), inventory.artifacts.keys)
        assertEquals(appleVerifiedReportLayout.keys, inventory.reports.keys)
        assertEquals(appleVerifiedToolchainLayout.keys, inventory.toolchain.keys)
    }

    @Test
    fun `tampered or extra report is rejected`() = fixture().use {
        val report = it.distribution.resolve(appleVerifiedReportLayout.keys.first())
        report.appendText("tampered")
        assertFailsWith<IllegalStateException> { it.verify() }
        it.rebuildProof()
        it.distribution.resolve("reports/extra.txt").apply { parentFile.mkdirs(); writeText("extra") }
        assertFailsWith<IllegalStateException> { it.verify() }
        Unit
    }

    @Test
    fun `distribution cannot be paired with different native slices`() = fixture().use {
        it.nativeEvidence.resolve(appleRustSliceSpecs.first().archiveName).appendText("different")
        assertFailsWith<IllegalStateException> { it.verify() }
        Unit
    }
}

private class VerifiedDistributionFixture : AutoCloseable {
    private val root = createTempDirectory("apple-verified-distribution").toFile()
    val distribution = root.resolve("distribution").apply { mkdirs() }
    val nativeEvidence = root.resolve("native-evidence").apply { mkdirs() }
    private val provenance = root.resolve("provenance.json").apply { writeText("{}") }
    private val packageSwift = root.resolve("Package.swift").apply { writeText("// package") }
    private val nativeReceipt = root.resolve("native-receipt.json").apply { writeText("{}") }
    private val identity = AppleVerifiedDistributionIdentity(
        "1".repeat(40), "2".repeat(40), "0.2.0", provenance.releaseDigest(),
        packageSwift.releaseDigest(), nativeReceipt.releaseDigest(),
    )

    init {
        val swift = distribution.resolve("CodexAgent-0.2.0.xcframework.zip").apply { writeText("swift") }
        distribution.resolve("CodexAgentPackage-0.2.0.zip").writeText("package")
        distribution.resolve("CodexAgent-0.2.0.xcframework.zip.sha256").writeText(swift.releaseDigest())
        appleVerifiedReportLayout.keys.forEach { path ->
            distribution.resolve(path).apply { parentFile.mkdirs(); writeText(path) }
        }
        appleVerifiedToolchainLayout.keys.forEach { path ->
            distribution.resolve(path).apply { parentFile.mkdirs(); writeText(path) }
        }
        (appleRustSliceSpecs.flatMap { listOf(it.archiveName, it.proofName) } + IOS_NATIVE_TESTS_PROOF).forEach {
            nativeEvidence.resolve(it).writeText(it)
        }
        rebuildProof()
    }

    fun rebuildProof() {
        val all = verifiedRegularFiles(distribution)
        val artifacts = all.filterKeys { it in appleVerifiedArtifactNames("0.2.0") }
        val reports = all.filterKeys { it in appleVerifiedReportLayout }
        val toolchain = all.filterKeys { it in appleVerifiedToolchainLayout }
        distribution.resolve(IOS_VERIFIED_DISTRIBUTION_PROOF).atomicWriteJson(
            buildAppleVerifiedDistributionProof(
                identity, artifacts, reports, toolchain, verifiedRegularFiles(nativeEvidence),
            ),
        )
    }

    fun verify() = verifyAppleVerifiedDistribution(distribution, nativeEvidence, identity)
    override fun close() = root.deleteRecursively().let { }
}

private fun fixture() = VerifiedDistributionFixture()
