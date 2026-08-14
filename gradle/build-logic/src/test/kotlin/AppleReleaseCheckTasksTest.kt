import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class AppleReleaseCheckTasksTest {
    @Test
    fun `toolchain output is checked without shell parsing`() {
        verifyAppleToolchainOutput(
            "Xcode 16.4\nBuild version 16F6\n",
            "Apple Swift version 6.1.2 effective-5.10\n",
            "16.4",
            "16F6",
            "6.1.2",
        )
        assertFailsWith<IllegalStateException> {
            verifyAppleToolchainOutput("Xcode 16.3", "Apple Swift version 6.1.2", "16.4", "16F6", "6.1.2")
        }
    }

    @Test
    fun `otool deployment records retain Rust sysroot exception`() {
        val records = parseDeploymentTargets(
            """
            archive(CodexAgent.framework.o):
                  cmd LC_BUILD_VERSION
             platform 2
                minos 15.0
            archive(std-abc.o):
                  cmd LC_BUILD_VERSION
             platform 2
                minos 14.0
            """.trimIndent(),
        )
        assertEquals(2, records.size)
        verifyDeploymentTargets(records, 2, "15.0")
        assertFailsWith<IllegalStateException> { verifyDeploymentTargets(records, 7, "15.0") }
    }

    @Test
    fun `artifact metrics use JDK recursive file aggregation and JSON budgets`() {
        val root = createTempDirectory("apple-metrics").toFile()
        try {
            val archive = root.resolve("archive.zip").apply { writeBytes(ByteArray(3)) }
            val device = root.resolve("CodexAgent").apply { writeBytes(ByteArray(5)) }
            val app = root.resolve("App.app").apply { mkdirs() }
            app.resolve("one").writeBytes(ByteArray(7))
            app.resolve("nested/two").apply { parentFile.mkdirs(); writeBytes(ByteArray(11)) }
            val policy = root.resolve("policy.json").apply { atomicWriteJson(buildJsonObject {
                put("artifactBytes", buildJsonObject {
                    put("compressedXcframeworkMaximum", JsonPrimitive(3))
                    put("deviceFrameworkMaximum", JsonPrimitive(5))
                    put("sampleAppInstallMaximum", JsonPrimitive(18))
                })
            }) }
            val metrics = measureAppleArtifacts(archive, device, app)
            assertEquals(AppleArtifactMetrics(3, 5, 18), metrics)
            verifyAppleArtifactBudgets(metrics, policy)
            policy.writeText(policy.readText().replace(": 18", ": 17"))
            assertFailsWith<IllegalStateException> { verifyAppleArtifactBudgets(metrics, policy) }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `live release tasks contain no compound shell implementation`() {
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { it.resolve("gradle/build-logic/src/main/kotlin/IosAppleReleaseVerificationTasks.kt").isFile }
        val source = Files.walk(repository.resolve("gradle/build-logic/src/main/kotlin").toPath()).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .sorted().map(Files::readString).toList().joinToString("\n")
        }
        listOf(
            "/bin/bash", "\"bash\", \"-c\"", "\"sh\", \"-c\"", "python3", "\"python\",",
            "\"jq\"", "\"find\"", "\"awk\"", "\"stat\"",
        ).forEach { forbidden ->
            assertFalse(forbidden in source, forbidden)
        }
        assertTrue("VerifyIosReleaseBudgetsTask" in source)
        assertTrue("xcodebuild" in source && "/usr/bin/xcrun" in source)
    }
}
