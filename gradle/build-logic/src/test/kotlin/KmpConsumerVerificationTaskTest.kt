import kotlin.io.path.createTempDirectory
import org.gradle.testkit.runner.GradleRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KmpConsumerVerificationTaskTest {
    @Test
    fun `consumer commands bind exact staged repository version and only their target`() {
        val tasks = stagedConsumerBuildTasks.getValue("node-js")
        val arguments = stagedConsumerArguments(
            java.io.File("/consumer"), java.io.File("/staging"), "0.2.0", "node-js", tasks,
        )
        assertTrue("-PCENTRAL_STAGING=/staging" in arguments)
        assertTrue("-PcodexAgent.version=0.2.0" in arguments)
        assertTrue("-PcodexAgent.consumerTarget=node-js" in arguments)
        assertEquals(listOf("compileKotlinJs"), tasks)
        assertFalse("compileKotlinWasmJs" in arguments)
        assertFalse("compileAndroidMain" in arguments)
        assertEquals(tasks, arguments.takeLast(tasks.size))
        assertTrue("--no-configuration-cache" in arguments)
        val verifiedArguments = stagedConsumerArguments(
            java.io.File("/consumer"), java.io.File("/staging"), "0.2.0", "node-js", tasks,
            java.io.File("/outcomes.init.gradle.kts"),
        )
        assertTrue(listOf("--init-script", "/outcomes.init.gradle.kts") in verifiedArguments.windowed(2))
        assertEquals(stagedConsumerOutcomeTask, verifiedArguments.last())
        assertEquals(
            setOf("common", "android", "desktop", "ios-device", "ios-simulator", "node-js", "node-wasm"),
            stagedConsumerBuildTasks.keys,
        )
        assertEquals(listOf("compileKotlinJvm"), stagedConsumerBuildTasks.getValue("common"))
    }

    @Test
    fun `outcome verifier rejects a silently skipped requested task`() {
        val root = createTempDirectory("kmp-consumer-outcome").toFile()
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name = \"outcome-test\"\n")
            root.resolve("build.gradle.kts").writeText("""
                tasks.register("compiled") { doLast { println("compiled") } }
                tasks.register("unsupported") { onlyIf { false } }
            """.trimIndent())
            val initScript = root.resolve("outcomes.init.gradle.kts").apply {
                writeText(stagedConsumerOutcomeInitScript(listOf("compiled", "unsupported")))
            }
            val failure = assertFailsWith<org.gradle.testkit.runner.UnexpectedBuildFailure> {
                GradleRunner.create().withProjectDir(root).withArguments(
                    "compiled", "unsupported", stagedConsumerOutcomeTask,
                    "--init-script", initScript.absolutePath, "--console=plain",
                ).build()
            }
            assertTrue("Staged consumer tasks did not execute" in failure.buildResult.output)
            assertTrue("unsupported" in failure.buildResult.output)
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `outcome verifier accepts executed and up-to-date requested tasks`() {
        val root = createTempDirectory("kmp-consumer-outcome").toFile()
        try {
            root.resolve("settings.gradle.kts").writeText("rootProject.name = \"outcome-test\"\n")
            root.resolve("build.gradle.kts").writeText("""
                tasks.register("compiled") {
                    val proof = layout.buildDirectory.file("compiled.txt")
                    outputs.file(proof)
                    doLast { proof.get().asFile.writeText("compiled") }
                }
            """.trimIndent())
            val initScript = root.resolve("outcomes.init.gradle.kts").apply {
                writeText(stagedConsumerOutcomeInitScript(listOf("compiled")))
            }
            repeat(2) {
                GradleRunner.create().withProjectDir(root).withArguments(
                    "compiled", stagedConsumerOutcomeTask,
                    "--init-script", initScript.absolutePath, "--console=plain",
                ).build()
            }
        } finally { root.deleteRecursively() }
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
