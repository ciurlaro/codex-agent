import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

internal data class SimulatorSelection(val runtimeIdentifier: String, val udid: String, val state: String)
internal data class SimulatorStatus(val available: Boolean, val state: String)
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

internal fun simulatorStatus(devicesJson: String, runtimeIdentifier: String, udid: String): SimulatorStatus? {
    val root = releaseJson.parseToJsonElement(devicesJson) as? JsonObject
        ?: error("simctl devices JSON is invalid")
    val devices = root.releaseObject("devices")[runtimeIdentifier] ?: return null
    check(devices is JsonArray) { "Devices for simulator runtime $runtimeIdentifier are invalid" }
    val device = devices.map { it as? JsonObject ?: error("simctl device is invalid") }
        .firstOrNull { it.releaseString("udid") == udid } ?: return null
    return SimulatorStatus(device.releaseBoolean("isAvailable"), device.releaseString("state"))
}

internal fun shouldRetryDisappearedSimulator(
    devicesJson: String?,
    runtimeIdentifier: String,
    udid: String,
    attempt: Int,
): Boolean = attempt == 0 && devicesJson != null && runCatching {
    simulatorStatus(devicesJson, runtimeIdentifier, udid) == null
}.getOrDefault(false)

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

internal fun swiftSimulatorXCFrameworkCommand(framework: File, output: File) = listOf(
    "xcodebuild", "-create-xcframework", "-framework", framework.absolutePath,
    "-output", output.absolutePath,
)

internal fun swiftSimulatorBuildForTestingCommand(derivedData: File) = listOf(
    "xcodebuild",
    "-scheme", "CodexAgent-Package",
    "-destination", "generic/platform=iOS Simulator",
    "-derivedDataPath", derivedData.absolutePath,
    "ARCHS=arm64",
    "ONLY_ACTIVE_ARCH=YES",
    "CODE_SIGNING_ALLOWED=NO",
    "build-for-testing",
)

@CacheableTask
abstract class VerifySwiftSimulatorCompilationTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val packageManifest: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourcesDirectory: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val testsDirectory: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val simulatorFrameworkDirectory: DirectoryProperty
    @get:Input abstract val expectedXcodeVersion: Property<String>
    @get:Input abstract val expectedXcodeBuild: Property<String>
    @get:Input abstract val expectedSwiftVersion: Property<String>
    @get:LocalState abstract val derivedDataDirectory: DirectoryProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun verify() {
        val packageRoot = temporaryDir.resolve("CodexAgentPackage")
        deleteReleaseTree(packageRoot)
        try {
            Files.createDirectories(packageRoot.toPath())
            Files.copy(packageManifest.get().asFile.toPath(), packageRoot.resolve("Package.swift").toPath())
            copyReleaseTree(sourcesDirectory.get().asFile, packageRoot.resolve("Sources"))
            copyReleaseTree(testsDirectory.get().asFile, packageRoot.resolve("Tests"))
            val xcframework = packageRoot.resolve("CodexAgent.xcframework")
            processes.captureReleaseProcess(swiftSimulatorXCFrameworkCommand(
                simulatorFrameworkDirectory.get().asFile,
                xcframework,
            ))
            processes.captureReleaseProcess(
                swiftSimulatorBuildForTestingCommand(derivedDataDirectory.get().asFile),
                packageRoot,
            )
            reportFile.get().asFile.atomicWriteJson(buildJsonObject {
                put("schemaVersion", JsonPrimitive(1))
                put("result", JsonPrimitive("passed"))
                put("target", JsonPrimitive("iosSimulatorArm64"))
                put("xcodeVersion", JsonPrimitive(expectedXcodeVersion.get()))
                put("xcodeBuild", JsonPrimitive(expectedXcodeBuild.get()))
                put("swiftVersion", JsonPrimitive(expectedSwiftVersion.get()))
            })
        } finally {
            deleteReleaseTree(packageRoot)
        }
    }
}

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
        val resultBundle = resultBundleDirectory.get().asFile
        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            val runtimes = processes.captureReleaseProcess(
                listOf("/usr/bin/xcrun", "simctl", "list", "-j", "runtimes"),
            )
            val devices = processes.captureReleaseProcess(
                listOf("/usr/bin/xcrun", "simctl", "list", "-j", "devices", "available"),
            )
            val simulator = selectSimulator(runtimes, devices, runtimeName.get(), deviceTypeIdentifier.get())
            try {
                if (simulator.state != "Booted") {
                    processes.captureReleaseProcess(listOf("/usr/bin/xcrun", "simctl", "boot", simulator.udid))
                }
                processes.captureReleaseProcess(
                    listOf("/usr/bin/xcrun", "simctl", "bootstatus", simulator.udid, "-b"),
                )
                val readyDevices = processes.captureReleaseProcess(
                    listOf("/usr/bin/xcrun", "simctl", "list", "-j", "devices", "available"),
                )
                check(simulatorStatus(readyDevices, simulator.runtimeIdentifier, simulator.udid) ==
                    SimulatorStatus(true, "Booted")) { "Selected simulator is not available and booted" }
                simulatorDevicesFile.get().asFile.apply {
                    Files.createDirectories(toPath().parent)
                    writeText(readyDevices)
                }
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
                return
            } catch (failure: Throwable) {
                val latestDevices = runCatching {
                    processes.captureReleaseProcess(
                        listOf("/usr/bin/xcrun", "simctl", "list", "-j", "devices", "available"),
                    )
                }.getOrNull()
                if (shouldRetryDisappearedSimulator(
                        latestDevices, simulator.runtimeIdentifier, simulator.udid, attempt,
                    )) {
                    lastFailure = failure
                    deleteReleaseTree(resultBundle)
                    logger.lifecycle("Selected simulator disappeared; retrying once with a fresh device")
                } else {
                    throw failure
                }
            }
        }
        throw checkNotNull(lastFailure)
    }
}
