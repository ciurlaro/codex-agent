import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder

class ReleaseResourceTasksTest {
    @Test
    fun `accumulator preserves start minima peaks and tracked paths`() {
        val accumulator = ReleaseResourceAccumulator()
        accumulator.accept(ReleaseResourceSnapshot(100, 40, 60, 200, 120, 10, mapOf("a" to 2)))
        accumulator.accept(ReleaseResourceSnapshot(100, 55, 45, 200, 90, 30, mapOf("a" to 7)))
        val report = accumulator.report("phase", listOf("command"), 0, 1.2345)
        assertEquals(2, report.releaseInt("sampleCount"))
        assertEquals(15, report.releaseObject("disk").releaseLong("peakIncreaseBytes"))
        assertEquals(30, report.releaseObject("memory").releaseLong("peakCommandProcessTreeResidentBytes"))
        assertEquals(7, report.releaseObject("trackedPathLogicalPeakBytes").releaseLong("a"))
    }

    @Test
    fun `resource runner writes success evidence and propagates failure after evidence`() {
        val root = createTempDirectory("resource-runner").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            fun task(name: String, executable: String) = project.tasks.register(name, MeasureReleaseCommandTask::class.java).get().apply {
                phase.set(name)
                commandExecutable.set(executable)
                commandArguments.set(emptyList())
                environmentVariables.set(emptyMap())
                workspace.set(root)
                workingDirectory.set(root)
                outputFile.set(root.resolve("$name.json"))
            }
            val success = task("success", "/usr/bin/true")
            success.measure()
            assertEquals(0, root.resolve("success.json").readReleaseObject().releaseInt("exitCode"))
            val result = root.resolve("result.txt")
            val environment = task("environment", "/bin/sh").apply {
                commandArguments.set(listOf("-c", "test \"\$CODEX_RESOURCE_TEST\" = bound && printf ok > result.txt"))
                environmentVariables.put("CODEX_RESOURCE_TEST", "bound")
                resultFiles.from(result)
            }
            environment.measure()
            assertEquals(result.releaseDigest(), root.resolve("environment.json").readReleaseObject()
                .releaseArray("results").single().let { it as kotlinx.serialization.json.JsonObject }.releaseString("sha256"))
            val failure = task("failure", "/usr/bin/false")
            assertFailsWith<GradleException> { failure.measure() }
            assertTrue(root.resolve("failure.json").isFile)
            assertEquals(1, root.resolve("failure.json").readReleaseObject().releaseInt("exitCode"))
        } finally { root.deleteRecursively() }
    }

    @Test
    fun `consumer validates and hashes the single existing simulator report without a process`() {
        val root = createTempDirectory("resource-consumer").toFile()
        try {
            val metrics = root.resolve("runtime-metrics.json")
            writeRuntimeMetrics(metrics)
            val project = ProjectBuilder.builder().withProjectDir(root).build()
            val task = project.tasks.register(
                "consume", ConsumeReleaseResourceReportTask::class.java,
            ).get().apply {
                phase.set("ios-runtime-benchmark")
                producerTaskPath.set(":codex-agent-runtime-ios:iosSimulatorArm64Test")
                metricsFile.set(metrics)
                workspace.set(root)
                outputFile.set(root.resolve("resource.json"))
            }
            task.consume()
            val report = root.resolve("resource.json").readReleaseObject()
            assertEquals(0, report.releaseInt("exitCode"))
            assertEquals(metrics.readReleaseObject(), report.releaseObject("runtimeMetrics"))
            assertEquals(metrics.releaseDigest(), report.releaseArray("results").single()
                .let { it as kotlinx.serialization.json.JsonObject }.releaseString("sha256"))
            assertTrue(ConsumeReleaseResourceReportTask::class.java.declaredConstructors
                .all { it.parameterCount == 0 })
            writeRuntimeMetrics(metrics, startup = 30_000)
            assertFailsWith<IllegalStateException> { task.consume() }
        } finally { root.deleteRecursively() }
    }

    private fun writeRuntimeMetrics(file: java.io.File, startup: Long = 10) = file.atomicWriteJson(buildJsonObject {
        put("warmupCycles", JsonPrimitive(1)); put("measuredCycles", JsonPrimitive(5))
        put("coldStartupMilliseconds", JsonPrimitive(10))
        put("startupMilliseconds", buildJsonArray { repeat(5) { add(JsonPrimitive(startup)) } })
        put("startupMedianMilliseconds", JsonPrimitive(startup)); put("startupMaximumMilliseconds", JsonPrimitive(startup))
        put("shutdownMilliseconds", buildJsonArray { repeat(5) { add(JsonPrimitive(10)) } })
        put("shutdownMedianMilliseconds", JsonPrimitive(10)); put("shutdownMaximumMilliseconds", JsonPrimitive(10))
        put("memoryMeasurement", JsonPrimitive("mach_task_basic_info.current_resident_size"))
        put("idleCurrentResidentBytes", JsonPrimitive(1)); put("recursiveSearchCurrentResidentBytes", JsonPrimitive(2))
        put("authenticatedTurnPeakResidentBytes", kotlinx.serialization.json.JsonNull)
    })
}
