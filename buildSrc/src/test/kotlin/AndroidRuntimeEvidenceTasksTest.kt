import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class AndroidRuntimeEvidenceTasksTest {
    @Test
    fun `Android SDK tool providers remain configuration-cache safe`() {
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { it.resolve("codex-agent-runtime-android/build.gradle.kts").isFile }
        val registration = repository.resolve("codex-agent-runtime-android/build.gradle.kts").readText()
            .substringAfter("val localAndroidSdkPath")
            .substringBefore("mavenPublishing")

        assertTrue("providers.fileContents" in registration)
        assertTrue("JavaFile(it, \"platform-tools/adb\")" in registration)
        assertTrue("JavaFile(it, \"cmdline-tools/latest/bin/apkanalyzer\")" in registration)
        assertFalse("androidSdkPath.map { file(" in registration)
        assertFalse("providers.provider" in registration)
        assertFalse("?: error(" in registration)
    }

    @Test
    fun `valid self-instrumenting evidence binds the report APK AAR and pinned runtime`() = withFixture { fixture ->
        val verified = fixture.verify()
        assertEquals(fixture.apk.releaseDigest(), verified.instrumentationApkSha256)
        assertEquals(fixture.aar.releaseDigest(), verified.releaseAarSha256)
        assertEquals(fixture.runtime.releaseDigest(), verified.bundledRuntimeSha256)
    }

    @Test
    fun `schema commit command device result and identity mismatches fail`() {
        val mismatches = listOf(
            "schemaVersion" to JsonPrimitive(1),
            "commitSha" to JsonPrimitive("f".repeat(40)),
            "testCommand" to JsonPrimitive("connectedDebugAndroidTest"),
            "deviceArchitecture" to JsonPrimitive("x86_64"),
            "deviceApi" to JsonPrimitive(25),
            "result" to JsonPrimitive("failed"),
            "testsRun" to JsonPrimitive(0),
            "testedApplicationKind" to JsonPrimitive("target-apk"),
            "applicationId" to JsonPrimitive("other"),
            "instrumentationTargetPackage" to JsonPrimitive("other"),
            "testedApplicationApkSha256" to JsonPrimitive("f".repeat(64)),
        )
        mismatches.forEach { (key, value) ->
            withFixture { fixture ->
                fixture.replace(key, value)
                assertFailsWith<IllegalStateException>(key) { fixture.verify() }
            }
        }
    }

    @Test
    fun `missing or tampered downloaded artifacts fail`() {
        listOf<(Fixture) -> Unit>(
            { it.apk.delete() },
            { it.report.delete() },
            { it.apk.appendText("tampered") },
            { it.report.appendText("tampered") },
            { it.aar.appendText("tampered") },
        ).forEach { mutate ->
            withFixture { fixture ->
                mutate(fixture)
                assertFailsWith<IllegalStateException> { fixture.verify() }
            }
        }
    }

    @Test
    fun `failed skipped zero and incomplete instrumentation reports fail`() {
        val reports = listOf(
            reportXml("<failure/>", ""),
            reportXml("<skipped/>", ""),
            "<testsuite/>",
            reportXml("", "", secondName = "notTheRequiredTest"),
        )
        reports.forEach { xml ->
            withFixture { fixture ->
                fixture.report.writeText(xml)
                fixture.rebuildEvidence()
                assertFailsWith<IllegalStateException> { fixture.verify() }
            }
        }
    }

    @Test
    fun `only the exact instrumentation class can satisfy the required methods`() {
        withFixture { fixture ->
            fixture.report.writeText(reportXml("", ""))
            fixture.rebuildEvidence()
            fixture.verify()
        }
        val wrongClass = "io.example.WrongRuntimeBootstrapDeviceTest"
        val invalid = listOf(
            reportXml("", "", firstClass = wrongClass, secondClass = wrongClass),
            reportXml("", "", secondClass = wrongClass),
            reportXml("", "").replace(
                "</testsuite>",
                "<testcase classname=\"$ANDROID_RUNTIME_TEST_CLASS\" " +
                    "name=\"missingNonExecutableAndCorruptOverridesFailClosed\"/></testsuite>",
            ),
        )
        invalid.forEach { xml ->
            withFixture { fixture ->
                fixture.report.writeText(xml)
                fixture.rebuildEvidence()
                assertFailsWith<IllegalStateException> { fixture.verify() }
            }
        }
    }

    @Test
    fun `manifest and bundled runtime mismatches fail`() {
        withFixture { fixture ->
            assertFailsWith<IllegalStateException> {
                fixture.verify(AndroidManifestIdentity(ANDROID_TEST_APPLICATION_ID, "other"))
            }
        }
        withFixture { fixture ->
            fixture.writeArchive(fixture.apk, APK_RUNTIME_ENTRY, "different".encodeToByteArray())
            fixture.rebuildEvidence()
            assertFailsWith<IllegalStateException> { fixture.verify() }
        }
        withFixture { fixture ->
            fixture.writeArchive(fixture.apk, "other", fixture.runtime.readBytes())
            fixture.replace("instrumentationApkSha256", JsonPrimitive(fixture.apk.releaseDigest()))
            fixture.replace("testedApplicationApkSha256", JsonPrimitive(fixture.apk.releaseDigest()))
            assertFailsWith<IllegalStateException> { fixture.verify() }
        }
    }

    @Test
    fun `device selection requires exactly one authorized device`() {
        assertEquals("serial", singleAuthorizedAndroidDevice("List of devices attached\nserial\tdevice\n"))
        listOf(
            "List of devices attached\n",
            "List of devices attached\nserial\tunauthorized\n",
            "List of devices attached\na\tdevice\nb\tdevice\n",
        ).forEach { output ->
            assertFailsWith<IllegalStateException> { singleAuthorizedAndroidDevice(output) }
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val directory = createTempDirectory("android-evidence").toFile()
        try {
            block(Fixture(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class Fixture(val root: File) {
        val runtime = root.resolve("runtime.so").apply { writeText("pinned runtime") }
        val apk = root.resolve(ANDROID_EVIDENCE_APK)
        val aar = root.resolve("codex-agent-runtime-android-release.aar")
        val report = root.resolve(ANDROID_EVIDENCE_REPORT).apply { writeText(reportXml("", "")) }
        val evidence = root.resolve(ANDROID_EVIDENCE_FILE)

        init {
            writeArchive(apk, APK_RUNTIME_ENTRY, runtime.readBytes())
            writeArchive(aar, AAR_RUNTIME_ENTRY, runtime.readBytes())
            rebuildEvidence()
        }

        fun verify(identity: AndroidManifestIdentity = AndroidManifestIdentity(
            ANDROID_TEST_APPLICATION_ID,
            ANDROID_TEST_APPLICATION_ID,
        )): AndroidRuntimeEvidenceVerification = verifyAndroidRuntimeEvidenceArtifacts(
            evidence,
            root,
            aar,
            COMMIT,
            runtime.releaseDigest(),
        ) { identity }

        fun rebuildEvidence() {
            evidence.atomicWriteJson(buildAndroidRuntimeEvidence(AndroidRuntimeEvidenceValues(
                COMMIT,
                "arm64-v8a",
                35,
                report.releaseDigest(),
                apk.releaseDigest(),
                ANDROID_TEST_APPLICATION_ID,
                ANDROID_TEST_APPLICATION_ID,
                aar.name,
                aar.releaseDigest(),
                runCatching { apk.singleZipEntryDigest(APK_RUNTIME_ENTRY) }.getOrDefault(runtime.releaseDigest()),
                aar.singleZipEntryDigest(AAR_RUNTIME_ENTRY),
            )))
        }

        fun replace(key: String, value: JsonPrimitive) {
            evidence.atomicWriteJson(JsonObject(evidence.readReleaseObject() + (key to value)))
        }

        fun writeArchive(file: File, entryName: String, bytes: ByteArray) {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    companion object {
        private const val COMMIT = "0123456789abcdef0123456789abcdef01234567"

        private fun reportXml(
            firstBody: String,
            secondBody: String,
            secondName: String = "successfulRuntimeInstallsCertificatePrivacyAndCleanupPolicies",
            firstClass: String = ANDROID_RUNTIME_TEST_CLASS,
            secondClass: String = ANDROID_RUNTIME_TEST_CLASS,
        ): String = """
            <testsuite tests="2" failures="0" errors="0" skipped="0">
              <testcase classname="$firstClass" name="missingNonExecutableAndCorruptOverridesFailClosed">$firstBody</testcase>
              <testcase classname="$secondClass" name="$secondName">$secondBody</testcase>
            </testsuite>
        """.trimIndent()
    }
}
