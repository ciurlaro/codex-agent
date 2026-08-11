import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KmpConsumerVerificationTaskTest {
    @Test
    fun `consumer command binds exact staged repository version and four targets`() {
        val arguments = stagedConsumerArguments(java.io.File("/consumer"), java.io.File("/staging"), "0.2.0")
        assertTrue("-PCENTRAL_STAGING=/staging" in arguments)
        assertTrue("-PcodexAgent.version=0.2.0" in arguments)
        assertEquals(stagedConsumerBuildTasks, arguments.takeLast(4))
        assertTrue("--no-configuration-cache" in arguments)
    }

    @Test
    fun `consumer preparation is clean and writes only host SDK state`() {
        val root = createTempDirectory("kmp-consumer").toFile()
        try {
            val template = root.resolve("template").apply { mkdirs(); resolve("settings.gradle.kts").writeText("settings") }
            val consumer = root.resolve("consumer").apply { mkdirs(); resolve("stale").writeText("stale") }
            prepareStagedConsumer(template, consumer, "/sdk")
            assertFalse(consumer.resolve("stale").exists())
            assertEquals("settings", consumer.resolve("settings.gradle.kts").readText())
            assertEquals("sdk.dir=/sdk\n", consumer.resolve("local.properties").readText())
        } finally { root.deleteRecursively() }
    }
}
