import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AndroidRuntimeEvidenceIoTest {
    @Test
    fun `passing report requires the exact runtime class and methods`() = withReport(reportXml()) { report ->
        assertEquals(REQUIRED_ANDROID_RUNTIME_TESTS.size, requirePassingAndroidRuntimeReport(report).testCases.size)
    }

    @Test
    fun `failed skipped incomplete duplicate and wrong reports fail`() {
        val wrongClass = "io.example.WrongRuntimeBootstrapDeviceTest"
        listOf(
            reportXml(firstBody = "<failure/>"),
            reportXml(firstBody = "<error/>"),
            reportXml(firstBody = "<skipped/>"),
            "<testsuite/>",
            reportXml(secondName = "notTheRequiredTest"),
            reportXml(firstClass = wrongClass),
            reportXml().replace(
                "</testsuite>",
                "<testcase classname=\"$ANDROID_RUNTIME_TEST_CLASS\" " +
                    "name=\"missingNonExecutableAndCorruptOverridesFailClosed\"/></testsuite>",
            ),
        ).forEach { xml ->
            withReport(xml) { report ->
                assertFailsWith<IllegalStateException> { requirePassingAndroidRuntimeReport(report) }
            }
        }
    }

    @Test
    fun `manifest parser requires application and instrumentation identities`() {
        val identity = parseAndroidManifestIdentity(
            """<manifest package="host"><instrumentation android:targetPackage="target"/></manifest>""",
        )
        assertEquals(AndroidManifestIdentity("host", "target"), identity)
        assertFailsWith<IllegalStateException> {
            parseAndroidManifestIdentity("""<manifest package="host"/>""")
        }
    }

    private fun withReport(xml: String, block: (File) -> Unit) {
        val directory = createTempDirectory("android-report").toFile()
        try {
            block(directory.resolve("report.xml").apply { writeText(xml) })
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun reportXml(
        firstBody: String = "",
        secondBody: String = "",
        secondName: String = "successfulRuntimeInstallsCertificatePrivacyAndCleanupPolicies",
        firstClass: String = ANDROID_RUNTIME_TEST_CLASS,
    ): String = """
        <testsuite tests="2" failures="0" errors="0" skipped="0">
          <testcase classname="$firstClass"
            name="missingNonExecutableAndCorruptOverridesFailClosed">$firstBody</testcase>
          <testcase classname="$ANDROID_RUNTIME_TEST_CLASS" name="$secondName">$secondBody</testcase>
        </testsuite>
    """.trimIndent()
}
