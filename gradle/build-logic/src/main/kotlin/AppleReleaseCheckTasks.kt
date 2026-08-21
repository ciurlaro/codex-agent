import java.io.File
import java.nio.file.Files

internal data class DeploymentTargetRecord(val member: String, val platform: Int, val minimum: String)

internal data class AppleArtifactMetrics(
    val compressedXcframeworkBytes: Long,
    val deviceFrameworkBytes: Long,
    val sampleAppInstallBytes: Long,
)

internal const val IOS_GIBIBYTE_BYTES = 1024L * 1024L * 1024L

internal fun requireIosFreeDiskSpace(availableBytes: Long, minimumFreeGiB: Long): Long {
    require(minimumFreeGiB > 0) { "Minimum iOS free disk space must be positive" }
    val requiredBytes = Math.multiplyExact(minimumFreeGiB, IOS_GIBIBYTE_BYTES)
    check(availableBytes >= requiredBytes) {
        "iOS verification requires $minimumFreeGiB GiB free, but only " +
            "${availableBytes / IOS_GIBIBYTE_BYTES} GiB is available. " +
            "Run :codex-agent-runtime-ios:clean before trying again."
    }
    return requiredBytes
}

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
