import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

internal data class SimulatorSelection(val runtimeIdentifier: String, val udid: String, val state: String)
internal data class SwiftTestSummary(val total: Int, val failed: Int)

internal fun selectSimulator(
    runtimesJson: String,
    devicesJson: String,
    runtimeName: String,
    deviceTypeIdentifier: String,
): SimulatorSelection {
    val runtimes = (releaseJson.parseToJsonElement(runtimesJson) as? JsonObject)
        ?.releaseArray("runtimes") ?: error("simctl runtimes JSON is invalid")
    val runtime = runtimes.map { it as? JsonObject ?: error("simctl runtime is invalid") }
        .firstOrNull { it.releaseString("name") == runtimeName && it.releaseBoolean("isAvailable") }
        ?: error("Required available simulator runtime was not found: $runtimeName")
    val runtimeIdentifier = runtime.releaseString("identifier")
    val devices = (releaseJson.parseToJsonElement(devicesJson) as? JsonObject)
        ?.releaseObject("devices")?.get(runtimeIdentifier) as? JsonArray
        ?: error("No devices exist for simulator runtime $runtimeIdentifier")
    val device = devices.map { it as? JsonObject ?: error("simctl device is invalid") }
        .firstOrNull {
            it.releaseBoolean("isAvailable") && it.releaseString("deviceTypeIdentifier") == deviceTypeIdentifier
        } ?: error("Required available simulator device was not found: $deviceTypeIdentifier")
    return SimulatorSelection(runtimeIdentifier, device.releaseString("udid"), device.releaseString("state"))
}

internal fun parseSwiftTestSummary(json: String): SwiftTestSummary {
    val summary = releaseJson.parseToJsonElement(json) as? JsonObject ?: error("xcresult summary is invalid")
    return SwiftTestSummary(
        summary.releaseInt("totalTestCount"),
        summary["failedTests"]?.jsonPrimitive?.intOrNull ?: 0,
    )
}

internal fun verifySwiftTestSummary(summary: SwiftTestSummary, expectedTestCount: Int) {
    check(summary.total == expectedTestCount) {
        "Expected $expectedTestCount Swift authentication tests, executed ${summary.total}"
    }
    check(summary.total > 0) { "Swift authentication test target was empty" }
    check(summary.failed == 0) { "Swift authentication tests failed: ${summary.failed}" }
}

internal fun swiftAuthenticationXcodebuildCommand(
    simulatorId: String,
    derivedData: File,
    resultBundle: File,
) = listOf(
    "xcodebuild",
    "-scheme", "CodexAgent-Package",
    "-destination", "platform=iOS Simulator,id=$simulatorId",
    "-derivedDataPath", derivedData.absolutePath,
    "-resultBundlePath", resultBundle.absolutePath,
    "CODE_SIGNING_ALLOWED=NO",
    "test",
)

@DisableCachingByDefault(because = "Boots a selected simulator and executes XCTest")
abstract class VerifySwiftAuthenticationTestsTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val packageDirectory: DirectoryProperty
    @get:Input abstract val runtimeName: Property<String>
    @get:Input abstract val deviceTypeIdentifier: Property<String>
    @get:Input abstract val expectedTestCount: Property<Int>
    @get:LocalState abstract val derivedDataDirectory: DirectoryProperty
    @get:OutputFile abstract val simulatorDevicesFile: RegularFileProperty
    @get:OutputDirectory abstract val resultBundleDirectory: DirectoryProperty
    @get:OutputFile abstract val summaryFile: RegularFileProperty

    @TaskAction fun verify() {
        val runtimes = processes.captureReleaseProcess(
            listOf("/usr/bin/xcrun", "simctl", "list", "-j", "runtimes"),
        )
        val devices = processes.captureReleaseProcess(
            listOf("/usr/bin/xcrun", "simctl", "list", "-j", "devices", "available"),
        )
        simulatorDevicesFile.get().asFile.apply {
            Files.createDirectories(toPath().parent)
            writeText(devices)
        }
        val simulator = selectSimulator(runtimes, devices, runtimeName.get(), deviceTypeIdentifier.get())
        if (simulator.state != "Booted") {
            processes.captureReleaseProcess(listOf("/usr/bin/xcrun", "simctl", "boot", simulator.udid))
        }
        processes.captureReleaseProcess(
            listOf("/usr/bin/xcrun", "simctl", "bootstatus", simulator.udid, "-b"),
        )
        val resultBundle = resultBundleDirectory.get().asFile
        deleteReleaseTree(resultBundle)
        val testOutput = processes.captureReleaseProcess(
            swiftAuthenticationXcodebuildCommand(
                simulator.udid,
                derivedDataDirectory.get().asFile,
                resultBundle,
            ),
            packageDirectory.get().asFile,
        )
        if (testOutput.isNotBlank()) logger.lifecycle(testOutput.trimEnd())
        val summaryJson = processes.captureReleaseProcess(
            listOf(
                "/usr/bin/xcrun", "xcresulttool", "get", "test-results", "summary",
                "--path", resultBundle.absolutePath, "--format", "json",
            ),
        )
        summaryFile.get().asFile.apply {
            Files.createDirectories(toPath().parent)
            writeText(summaryJson)
        }
        val summary = parseSwiftTestSummary(summaryJson)
        verifySwiftTestSummary(summary, expectedTestCount.get())
        logger.lifecycle("Swift authentication tests executed: ${summary.total}")
    }
}
