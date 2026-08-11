import com.sun.management.OperatingSystemMXBean
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.round
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

internal data class ReleaseResourceSnapshot(
    val diskTotal: Long,
    val diskUsed: Long,
    val diskAvailable: Long,
    val memoryTotal: Long,
    val memoryAvailable: Long,
    val processTreeRss: Long,
    val trackedBytes: Map<String, Long>,
)

internal class ReleaseResourceAccumulator {
    var samples = 0
        private set
    private var first: ReleaseResourceSnapshot? = null
    private var peakDiskUsed = 0L
    private var minimumDiskAvailable = Long.MAX_VALUE
    private var minimumMemoryAvailable = Long.MAX_VALUE
    private var peakProcessRss = 0L
    private val trackedPeaks = sortedMapOf<String, Long>()

    @Synchronized
    fun accept(sample: ReleaseResourceSnapshot) {
        if (first == null) first = sample
        samples++
        peakDiskUsed = maxOf(peakDiskUsed, sample.diskUsed)
        minimumDiskAvailable = minOf(minimumDiskAvailable, sample.diskAvailable)
        minimumMemoryAvailable = minOf(minimumMemoryAvailable, sample.memoryAvailable)
        peakProcessRss = maxOf(peakProcessRss, sample.processTreeRss)
        sample.trackedBytes.forEach { (path, bytes) -> trackedPeaks[path] = maxOf(trackedPeaks[path] ?: 0, bytes) }
    }

    @Synchronized
    fun report(phase: String, command: List<String>, exitCode: Int, durationSeconds: Double) = buildJsonObject {
        val start = checkNotNull(first) { "Resource measurement has no samples" }
        put("schemaVersion", JsonPrimitive(2))
        put("phase", JsonPrimitive(phase))
        put("command", buildJsonArray { command.forEach { add(JsonPrimitive(it)) } })
        put("exitCode", JsonPrimitive(exitCode))
        put("durationSeconds", JsonPrimitive(round(durationSeconds * 1000) / 1000))
        put("samplingIntervalSeconds", JsonPrimitive(1.0))
        put("sampleCount", JsonPrimitive(samples))
        put("disk", buildJsonObject {
            put("filesystemTotalBytes", JsonPrimitive(start.diskTotal))
            put("startUsedBytes", JsonPrimitive(start.diskUsed))
            put("startAvailableBytes", JsonPrimitive(start.diskAvailable))
            put("peakUsedBytes", JsonPrimitive(peakDiskUsed))
            put("peakIncreaseBytes", JsonPrimitive(peakDiskUsed - start.diskUsed))
            put("minimumAvailableBytes", JsonPrimitive(minimumDiskAvailable))
        })
        put("memory", buildJsonObject {
            put("systemTotalBytes", JsonPrimitive(start.memoryTotal))
            put("startSystemAvailableBytes", JsonPrimitive(start.memoryAvailable))
            put("minimumSystemAvailableBytes", JsonPrimitive(minimumMemoryAvailable))
            put("peakCommandProcessTreeResidentBytes", JsonPrimitive(peakProcessRss))
        })
        put("trackedPathLogicalPeakBytes", buildJsonObject {
            trackedPeaks.forEach { (path, bytes) -> put(path, JsonPrimitive(bytes)) }
        })
    }
}

internal fun releaseResourceSnapshot(
    workspace: File,
    tracked: Set<File>,
    processTreeRss: Long,
): ReleaseResourceSnapshot {
    val store = Files.getFileStore(workspace.toPath())
    val total = store.totalSpace
    val available = store.usableSpace
    val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    return ReleaseResourceSnapshot(
        total, total - available, available, os.totalMemorySize, os.freeMemorySize, processTreeRss,
        tracked.associate { it.absolutePath to it.treeBytes() },
    )
}

internal fun validateIosRuntimeMetrics(metrics: JsonObject) {
    fun durations(name: String) = metrics.releaseArray(name).map { value ->
        (value as? JsonPrimitive)?.longOrNull ?: error("Invalid $name value")
    }
    val startup = durations("startupMilliseconds")
    val shutdown = durations("shutdownMilliseconds")
    check(metrics.releaseInt("warmupCycles") == 1 && metrics.releaseInt("measuredCycles") == 5) {
        "iOS runtime metrics use the wrong cycle counts"
    }
    check(startup.size == 5 && startup.all { it in 0L until 30_000L }) { "iOS runtime startup gate failed" }
    check(shutdown.size == 5 && shutdown.all { it in 0L until 5_000L }) { "iOS runtime shutdown gate failed" }
    check(metrics.releaseLong("coldStartupMilliseconds") in 0L until 30_000L) { "iOS cold startup gate failed" }
    check(metrics.releaseLong("startupMaximumMilliseconds") == startup.max()) { "iOS startup maximum mismatch" }
    check(metrics.releaseLong("shutdownMaximumMilliseconds") == shutdown.max()) { "iOS shutdown maximum mismatch" }
    check(metrics.releaseLong("idleCurrentResidentBytes") >= 0L) { "iOS idle memory is invalid" }
    check(metrics.releaseLong("recursiveSearchCurrentResidentBytes") >= 0L) { "iOS search memory is invalid" }
}

@DisableCachingByDefault(because = "Candidate evidence consumes one fresh simulator test report")
abstract class ConsumeReleaseResourceReportTask : DefaultTask() {
    @get:Input abstract val phase: Property<String>
    @get:Input abstract val producerTaskPath: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val metricsFile: RegularFileProperty
    @get:Internal abstract val workspace: DirectoryProperty
    @get:Internal abstract val trackedPaths: ConfigurableFileCollection
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun consume() {
        val metrics = metricsFile.get().asFile.readReleaseObject()
        validateIosRuntimeMetrics(metrics)
        val accumulator = ReleaseResourceAccumulator().apply {
            accept(releaseResourceSnapshot(workspace.get().asFile, trackedPaths.files, 0L))
        }
        val report = accumulator.report(phase.get(), listOf(producerTaskPath.get()), 0, 0.0)
        outputFile.get().asFile.atomicWriteJson(buildJsonObject {
            report.forEach { (key, value) -> put(key, value) }
            put("results", buildJsonArray { add(metricsFile.get().asFile.releaseRecord()) })
            put("runtimeMetrics", metrics)
        })
    }
}

@DisableCachingByDefault(because = "Resource evidence must describe a fresh command execution")
abstract class MeasureReleaseCommandTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val phase: Property<String>
    @get:Input abstract val commandExecutable: Property<String>
    @get:Input abstract val commandArguments: ListProperty<String>
    @get:Input abstract val environmentVariables: MapProperty<String, String>
    @get:Internal abstract val workspace: DirectoryProperty
    @get:Internal abstract val workingDirectory: DirectoryProperty
    @get:Internal abstract val trackedPaths: ConfigurableFileCollection
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:OutputFiles abstract val resultFiles: ConfigurableFileCollection

    init { environmentVariables.convention(emptyMap()) }

    @TaskAction
    fun measure() {
        val accumulator = ReleaseResourceAccumulator()
        val running = AtomicBoolean(true)
        val baselinePids = processTreePids()
        fun sample() = accumulator.accept(releaseResourceSnapshot(
            workspace.get().asFile, trackedPaths.files, processTreeRss(baselinePids),
        ))
        sample()
        val sampler = Thread {
            while (running.get()) {
                try {
                    Thread.sleep(1_000)
                    if (running.get()) sample()
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply { isDaemon = true; name = "codex-agent-release-resource-sampler"; start() }
        val started = System.nanoTime()
        val result = try {
            exec.exec {
                workingDir(workingDirectory)
                executable(commandExecutable.get())
                args(commandArguments.get())
                environment(environmentVariables.get())
                isIgnoreExitValue = true
            }
        } finally {
            running.set(false)
            sampler.interrupt()
            sampler.join(2_000)
            sample()
        }
        val duration = (System.nanoTime() - started) / 1_000_000_000.0
        val command = listOf(commandExecutable.get()) + commandArguments.get()
        val measurement = accumulator.report(phase.get(), command, result.exitValue, duration)
        outputFile.get().asFile.atomicWriteJson(buildJsonObject {
            measurement.forEach { (key, value) -> put(key, value) }
            put("results", buildJsonArray {
                resultFiles.files.sortedBy(File::getName).forEach { file ->
                    check(file.isFile) { "Measured command result is missing: $file" }
                    add(file.releaseRecord())
                }
            })
        })
        if (result.exitValue != 0) throw GradleException("Measured command failed with exit code ${result.exitValue}")
    }

    private fun processTreePids(): Set<Long> =
        (sequenceOf(ProcessHandle.current()) + ProcessHandle.current().descendants().iterator().asSequence())
            .map(ProcessHandle::pid).toSet()

    private fun processTreeRss(baselinePids: Set<Long>): Long {
        val pids = processTreePids().minus(baselinePids).toList()
        if (File("/proc").isDirectory) return pids.sumOf { pid ->
            runCatching {
                File("/proc/$pid/status").useLines { lines ->
                    lines.firstOrNull { it.startsWith("VmRSS:") }
                        ?.substringAfter(':')?.trim()?.substringBefore(' ')?.toLong()?.times(1024) ?: 0
                }
            }.getOrDefault(0)
        }
        if (pids.isEmpty()) return 0
        val output = ByteArrayOutputStream()
        val result = exec.exec {
            commandLine("/bin/ps", "-o", "rss=", "-p", pids.joinToString(","))
            standardOutput = output
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return if (result.exitValue == 0) output.toString().lineSequence()
            .mapNotNull { it.trim().toLongOrNull() }.sum() * 1024 else 0
    }
}
