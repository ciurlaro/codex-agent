import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

internal data class DeploymentTargetRecord(val member: String, val platform: Int, val minimum: String)

internal data class AppleArtifactMetrics(
    val compressedXcframeworkBytes: Long,
    val deviceFrameworkBytes: Long,
    val sampleAppInstallBytes: Long,
)

internal fun verifyAppleToolchainOutput(
    xcode: String,
    swift: String,
    expectedXcodeVersion: String,
    expectedXcodeBuild: String,
    expectedSwiftVersion: String,
) {
    check(xcode.lineSequence().any { it == "Xcode $expectedXcodeVersion" }) { "Unexpected Xcode version" }
    check(xcode.lineSequence().any { it == "Build version $expectedXcodeBuild" }) { "Unexpected Xcode build" }
    check("Apple Swift version $expectedSwiftVersion" in swift) { "Unexpected Swift version" }
}

internal fun parseDeploymentTargets(output: String): List<DeploymentTargetRecord> {
    val records = mutableListOf<DeploymentTargetRecord>()
    var member: String? = null
    var readingBuildVersion = false
    var platform: Int? = null
    output.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (line.firstOrNull()?.isWhitespace() == false && line.endsWith("):")) {
            member = line.substringAfterLast('(').removeSuffix("):")
        } else if (trimmed == "cmd LC_BUILD_VERSION") {
            readingBuildVersion = true
            platform = null
        } else if (readingBuildVersion && trimmed.startsWith("platform ")) {
            platform = trimmed.substringAfter("platform ").trim().toInt()
        } else if (readingBuildVersion && trimmed.startsWith("minos ")) {
            records += DeploymentTargetRecord(
                checkNotNull(member) { "Deployment target has no archive member" },
                checkNotNull(platform) { "Deployment target has no platform" },
                trimmed.substringAfter("minos ").trim(),
            )
            readingBuildVersion = false
        }
    }
    return records
}

private val rustMinimum14Prefixes = setOf(
    "std", "panic_unwind", "object", "memchr", "addr2line", "gimli", "cfg_if", "rustc_demangle",
    "std_detect", "hashbrown", "rustc_std_workspace_alloc", "miniz_oxide", "adler2", "unwind", "libc",
    "rustc_std_workspace_core", "alloc", "core", "compiler_builtins", "ad3ac4dcdcbf93cb", "b6006474dd997b0d",
    "f3c5cc7ab326d4d0",
)

internal fun verifyDeploymentTargets(
    records: List<DeploymentTargetRecord>,
    expectedPlatform: Int,
    minimumIosVersion: String,
) {
    check(records.isNotEmpty()) { "No deployment targets were found" }
    check(records.any {
        it.member == "CodexAgent.framework.o" && it.platform == expectedPlatform && it.minimum == minimumIosVersion
    }) { "CodexAgent framework deployment target is missing" }
    records.forEach { record ->
        check(record.platform == expectedPlatform) { "Unexpected deployment platform: $record" }
        val acceptedRust14 = record.minimum == "14.0" && rustMinimum14Prefixes.any {
            record.member.startsWith("$it-")
        }
        check(record.minimum == minimumIosVersion || acceptedRust14) { "Unexpected deployment target: $record" }
    }
}

internal fun measureAppleArtifacts(archive: File, deviceBinary: File, application: File): AppleArtifactMetrics {
    check(archive.isFile && deviceBinary.isFile && application.isDirectory) { "Apple release artifacts are incomplete" }
    val applicationBytes = Files.walk(application.toPath()).use { paths ->
        paths.filter(Files::isRegularFile).mapToLong(Files::size).sum()
    }
    return AppleArtifactMetrics(archive.length(), deviceBinary.length(), applicationBytes)
}

internal fun verifyAppleArtifactBudgets(metrics: AppleArtifactMetrics, policy: File) {
    val limits = policy.readReleaseObject().releaseObject("artifactBytes")
    check(metrics.compressedXcframeworkBytes <= limits.releaseLong("compressedXcframeworkMaximum")) {
        "Compressed XCFramework exceeds its release budget"
    }
    check(metrics.deviceFrameworkBytes <= limits.releaseLong("deviceFrameworkMaximum")) {
        "Device framework exceeds its release budget"
    }
    check(metrics.sampleAppInstallBytes <= limits.releaseLong("sampleAppInstallMaximum")) {
        "Sample application exceeds its release budget"
    }
}

internal fun requireSuccessfulReleaseProcess(
    command: List<String>,
    exitCode: Int,
    output: String,
    errors: String,
): String {
    val details = listOf(output.trim(), errors.trim()).filter(String::isNotEmpty).joinToString("\n")
    check(exitCode == 0) { "${command.joinToString(" ")} failed ($exitCode): $details" }
    return output
}

internal fun ExecOperations.captureReleaseProcess(
    command: List<String>,
    workingDirectory: File? = null,
    environmentVariables: Map<String, String> = mapOf("LC_ALL" to "C", "LANG" to "C"),
): String {
    val output = ByteArrayOutputStream()
    val errors = ByteArrayOutputStream()
    val result = exec {
        commandLine(command)
        workingDirectory?.let(::workingDir)
        environment(environmentVariables)
        standardOutput = output
        errorOutput = errors
        isIgnoreExitValue = true
    }
    return requireSuccessfulReleaseProcess(
        command,
        result.exitValue,
        output.toString(Charsets.UTF_8.name()),
        errors.toString(Charsets.UTF_8.name()),
    )
}

@DisableCachingByDefault(because = "Verifies the installed Apple toolchain")
abstract class VerifyAppleToolchainTask @Inject constructor(private val processes: ExecOperations) : DefaultTask() {
    @get:Input abstract val expectedXcodeVersion: Property<String>
    @get:Input abstract val expectedXcodeBuild: Property<String>
    @get:Input abstract val expectedSwiftVersion: Property<String>
    @get:OutputDirectory abstract val reportDirectory: DirectoryProperty

    @TaskAction fun verify() {
        val xcode = processes.captureReleaseProcess(listOf("xcodebuild", "-version"))
        val swift = processes.captureReleaseProcess(listOf("swift", "--version"))
        verifyAppleToolchainOutput(
            xcode, swift, expectedXcodeVersion.get(), expectedXcodeBuild.get(), expectedSwiftVersion.get(),
        )
        reportDirectory.file("xcode.txt").get().asFile.apply { parentFile.mkdirs(); writeText(xcode) }
        reportDirectory.file("swift.txt").get().asFile.writeText(swift)
    }
}

@DisableCachingByDefault(because = "Inspects binary metadata with the selected Xcode toolchain")
abstract class VerifyIosDeploymentTargetsTask @Inject constructor(private val processes: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val xcframeworkDirectory: DirectoryProperty
    @get:Input abstract val minimumIosVersion: Property<String>
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun verify() {
        val root = xcframeworkDirectory.get().asFile
        val binaries = Files.walk(root.toPath()).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.fileName.toString() == "CodexAgent" && it.parent.fileName.toString() == "CodexAgent.framework" }
                .map { it.toFile() }.sorted().toList()
        }
        check(binaries.size == 2) { "Expected exactly two XCFramework binaries, found ${binaries.size}" }
        val report = buildString {
            binaries.forEach { binary ->
                val path = binary.invariantSeparatorsPath
                val expectedPlatform = when {
                    "/ios-arm64-simulator/" in path -> 7
                    "/ios-arm64/" in path -> 2
                    else -> error("Unexpected XCFramework slice: $path")
                }
                val records = parseDeploymentTargets(
                    processes.captureReleaseProcess(listOf("/usr/bin/xcrun", "otool", "-l", binary.absolutePath)),
                )
                verifyDeploymentTargets(records, expectedPlatform, minimumIosVersion.get())
                val plist = binary.parentFile.resolve("Info.plist")
                val plistMinimum = processes.captureReleaseProcess(
                    listOf("/usr/bin/xcrun", "plutil", "-extract", "MinimumOSVersion", "raw", "-o", "-", plist.absolutePath),
                ).trim()
                check(plistMinimum == minimumIosVersion.get()) { "Framework Info.plist deployment target mismatch" }
                append("== ${binary.absolutePath} ==\n")
                records.map { "${it.platform}:${it.minimum}" }.toSortedSet().forEach { append(it).append('\n') }
            }
        }
        check("2:${minimumIosVersion.get()}" in report && "7:${minimumIosVersion.get()}" in report) {
            "Device and simulator deployment targets were not both verified"
        }
        reportFile.get().asFile.apply { parentFile.mkdirs(); writeText(report) }
    }
}

@DisableCachingByDefault(because = "Measures release artifacts produced by Xcode and Gradle")
abstract class VerifyIosReleaseBudgetsTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val policyFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archiveFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val deviceBinary: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val applicationDirectory: DirectoryProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun verify() {
        val metrics = measureAppleArtifacts(
            archiveFile.get().asFile, deviceBinary.get().asFile, applicationDirectory.get().asFile,
        )
        verifyAppleArtifactBudgets(metrics, policyFile.get().asFile)
        reportFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("compressedXcframeworkBytes", JsonPrimitive(metrics.compressedXcframeworkBytes))
            put("deviceFrameworkBytes", JsonPrimitive(metrics.deviceFrameworkBytes))
            put("sampleAppInstallBytes", JsonPrimitive(metrics.sampleAppInstallBytes))
        })
    }
}
