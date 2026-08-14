import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class FirebaseAndroidRuntimeEvidenceTasksTest {
    @Test
    fun `valid Firebase matrix JUnit app test APK and AAR evidence passes`() = withFixture { fixture ->
        val verified = fixture.verify()
        assertEquals(fixture.app.releaseDigest(), verified.applicationApkSha256)
        assertEquals(fixture.test.releaseDigest(), verified.testApkSha256)
        assertEquals(fixture.aar.releaseDigest(), verified.releaseAarSha256)
        assertEquals(fixture.runtime.releaseDigest(), verified.bundledRuntimeSha256)
    }

    @Test
    fun `schema identity and exact ARM device mismatches fail`() {
        listOf(
            "schemaVersion" to JsonPrimitive(2),
            "commitSha" to JsonPrimitive("f".repeat(40)),
            "deviceModel" to JsonPrimitive("Pixel2"),
            "deviceApi" to JsonPrimitive(34),
            "deviceArchitecture" to JsonPrimitive("x86_64"),
            "applicationId" to JsonPrimitive("other"),
            "instrumentationTargetPackage" to JsonPrimitive("other"),
            "testsRun" to JsonPrimitive(0),
        ).forEach { (key, value) ->
            withFixture { fixture ->
                fixture.replace(key, value)
                assertFailsWith<IllegalStateException>(key) { fixture.verify() }
            }
        }
    }

    @Test
    fun `matrix failures multiple devices and non-ARM models fail`() {
        val mutations = listOf<(Fixture) -> Unit>(
            { it.matrix.writeText(it.matrix.readText().replace("FINISHED", "ERROR")) },
            { it.matrix.writeText(it.matrix.readText().replace("SUCCESS", "FAILURE")) },
            { it.matrix.writeText(it.matrix.readText().replace(FIREBASE_DEVICE_MODEL, "Pixel2")) },
            { it.matrix.writeText(it.matrix.readText().replace("]", ",{}]")) },
        )
        mutations.forEach { mutate ->
            withFixture { fixture ->
                mutate(fixture)
                fixture.rebuildEvidence()
                assertFailsWith<IllegalStateException> { fixture.verify() }
            }
        }
    }

    @Test
    fun `failed JUnit and tampered artifacts fail`() {
        listOf<(Fixture) -> Unit>(
            { it.report.writeText(reportXml("<failure/>", "")) },
            { it.app.appendText("tampered") },
            { it.test.appendText("tampered") },
            { it.aar.appendText("tampered") },
        ).forEach { mutate ->
            withFixture { fixture ->
                mutate(fixture)
                assertFailsWith<IllegalStateException> { fixture.verify() }
            }
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val directory = createTempDirectory("firebase-android-evidence").toFile()
        try {
            block(Fixture(directory))
        } finally {
            directory.deleteRecursively()
        }
    }

    private class Fixture(val root: File) {
        val runtime = root.resolve("runtime.so").apply { writeText("pinned runtime") }
        val matrix = root.resolve(FIREBASE_MATRIX_FILE).apply { writeText(matrixJson()) }
        val report = root.resolve(FIREBASE_ANDROID_REPORT).apply { writeText(reportXml("", "")) }
        val app = root.resolve(FIREBASE_APPLICATION_APK)
        val test = root.resolve(FIREBASE_TEST_APK)
        val aar = root.resolve(FIREBASE_RELEASE_AAR)
        val evidence = root.resolve(FIREBASE_ANDROID_EVIDENCE_FILE)

        init {
            archive(app, APK_RUNTIME_ENTRY, runtime.readBytes())
            archive(test, "classes.dex", "test".encodeToByteArray())
            archive(aar, AAR_RUNTIME_ENTRY, runtime.readBytes())
            rebuildEvidence()
        }

        fun rebuildEvidence() {
            val parsed = runCatching { parseFirebaseTestMatrix(matrix) }.getOrElse {
                FirebaseTestMatrix("matrix-test", "test-project", "gs://bucket/results", FIREBASE_DEVICE_MODEL,
                    FIREBASE_DEVICE_API, FIREBASE_DEVICE_LOCALE, FIREBASE_DEVICE_ORIENTATION)
            }
            evidence.atomicWriteJson(buildFirebaseAndroidEvidence(FirebaseAndroidEvidenceValues(
                COMMIT, parsed, matrix.releaseDigest(), report.releaseDigest(), app.releaseDigest(),
                test.releaseDigest(), aar.releaseDigest(), app.singleZipEntryDigest(APK_RUNTIME_ENTRY),
                aar.singleZipEntryDigest(AAR_RUNTIME_ENTRY),
            )))
        }

        fun replace(key: String, value: JsonPrimitive) {
            evidence.atomicWriteJson(JsonObject(evidence.readReleaseObject() + (key to value)))
        }

        fun verify(): FirebaseAndroidEvidenceVerification = verifyFirebaseAndroidRuntimeEvidenceArtifacts(
            evidence, root, COMMIT, runtime.releaseDigest(),
            { FIREBASE_APPLICATION_ID },
            { AndroidManifestIdentity(FIREBASE_TEST_APPLICATION_ID, FIREBASE_APPLICATION_ID) },
        )

        private fun archive(file: File, path: String, bytes: ByteArray) {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    companion object {
        private const val COMMIT = "0123456789abcdef0123456789abcdef01234567"

        private fun matrixJson(): String = """
            {
              "testMatrixId":"matrix-test","projectId":"test-project","state":"FINISHED",
              "resultStorage":{"googleCloudStorage":{"gcsPath":"gs://bucket/results"}},
              "testExecutions":[{
                "state":"FINISHED",
                "environment":{"androidDevice":{"androidModelId":"$FIREBASE_DEVICE_MODEL",
                  "androidVersionId":"$FIREBASE_DEVICE_API","locale":"$FIREBASE_DEVICE_LOCALE",
                  "orientation":"$FIREBASE_DEVICE_ORIENTATION"}},
                "toolExecution":{"outcome":{"summary":"SUCCESS"}}
              }]
            }
        """.trimIndent()

        private fun reportXml(firstBody: String, secondBody: String): String = """
            <testsuite tests="2" failures="0" errors="0" skipped="0">
              <testcase classname="$ANDROID_RUNTIME_TEST_CLASS"
                name="missingNonExecutableAndCorruptOverridesFailClosed">$firstBody</testcase>
              <testcase classname="$ANDROID_RUNTIME_TEST_CLASS"
                name="successfulRuntimeInstallsCertificatePrivacyAndCleanupPolicies">$secondBody</testcase>
            </testsuite>
        """.trimIndent()
    }
}
