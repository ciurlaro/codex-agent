import java.io.File
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

private val codexRevision = "25af12f7e61572b0bc18ddb1008be543b91519b0"
private val codexArchiveSha256 = "42f627a7b32db41582c73a8eafd9ec4b35d6c3ff81bd3d4455cfd6224d79d329"
private val codexCargoLockSha256 = "e0843448b5767ff36a2a3b15212feb480cd4eaafe8a0c0ca08547e3c7da03a05"
private val resolvedCargoLockSha256 = layout.projectDirectory.file("native/provenance.json").asFile
    .readReleaseObject().releaseString("preparedCargoLockSha256")
private val libsqlite3SysVersion = "0.37.0"
private val libsqlite3SysArchiveSha256 = "b1f111c8c41e7c61a49cd34e44c7619462967221a6443b0ec299e0ac30cfb9b1"
private val expectedSqliteSourceSha256 = "9512509b1bccb7461f79bea8aad6280ae4699e925fa4804381b71f59e7efb0c5"
private val expectedPatchedSqliteSourceSha256 = "a0b50ae286c86c1890c2144641682820a42aa38021ad5fa9457d99c636f0d057"
private val pinnedRustToolchain = "1.95.0"
private val rustLibrary = "libcodex_agent_ios_bridge.a"
private val minimumIosVersion = "15.0"
private val expectedSwiftTestCount = 3
private val pinnedSqliteArchiveSha256 = "b1f111c8c41e7c61a49cd34e44c7619462967221a6443b0ec299e0ac30cfb9b1"
private val sqliteArchiveBytes = 5_295_554L
private val pinnedReleaseLto = "thin"
private val pinnedReleaseCodegenUnits = "8"
private val pinnedReleaseRustFlags = "-Cdebuginfo=0"
private val pinnedReleaseRustPathRemapPolicy = linkedMapOf(
    "releaseRustFlagsTransport" to "CARGO_ENCODED_RUSTFLAGS",
    "releaseRustPathRemapOrder" to "builderHome,cargoHome,rustSysroot,projectRoot,preparedCodexSource",
    "releaseRustBuilderHomePrefix" to "/codex-agent/builder-home",
    "releaseRustCargoHomePrefix" to "/codex-agent/cargo-home",
    "releaseRustSysrootPrefix" to "/codex-agent/rust-sysroot",
    "releaseRustProjectRootPrefix" to "/codex-agent/project",
    "releaseRustPreparedSourcePrefix" to "/codex-agent/prepared-source",
)
private val pinnedXcodeVersion = "26.6"
private val pinnedXcodeBuild = "17F113"
private val pinnedSwiftVersion = "6.3.3"

val nativeTasks = registerIosNativeTasks(
    IosNativeTaskConfiguration(
        codexRevision = codexRevision,
        codexArchiveSha256 = codexArchiveSha256,
        codexCargoLockSha256 = codexCargoLockSha256,
        resolvedCargoLockSha256 = resolvedCargoLockSha256,
        libsqlite3SysVersion = libsqlite3SysVersion,
        libsqlite3SysArchiveSha256 = libsqlite3SysArchiveSha256,
        expectedSqliteSourceSha256 = expectedSqliteSourceSha256,
        expectedPatchedSqliteSourceSha256 = expectedPatchedSqliteSourceSha256,
        pinnedRustToolchain = pinnedRustToolchain,
        pinnedRustSrcComponent = "required",
        rustLibrary = rustLibrary,
        minimumIosVersion = minimumIosVersion,
        pinnedSqliteArchiveSha256 = pinnedSqliteArchiveSha256,
        sqliteArchiveBytes = sqliteArchiveBytes,
        pinnedReleaseLto = pinnedReleaseLto,
        pinnedReleaseCodegenUnits = pinnedReleaseCodegenUnits,
        pinnedReleaseRustFlags = pinnedReleaseRustFlags,
        pinnedReleaseRustPathRemapPolicy = pinnedReleaseRustPathRemapPolicy,
    ),
)

val xcframework = XCFramework("CodexAgent")
extensions.configure<KotlinMultiplatformExtension> {
    val device = iosArm64()
    val simulator = iosSimulatorArm64()
    listOf(device, simulator).forEach { target ->
        val rustTask = if (target == device) nativeTasks.prepareCodexAgentIosArm64RustSlice
            else nativeTasks.prepareCodexAgentIosSimulatorArm64RustSlice
        val rustArchive = if (target == device) nativeTasks.iosArm64RustArchive
            else nativeTasks.iosSimulatorArm64RustArchive
        target.compilations.getByName("main").cinterops.create("codexAgentIos") {
            defFile(layout.projectDirectory.file("src/nativeInterop/cinterop/codex_agent_ios.def"))
            includeDirs(layout.projectDirectory.dir("native/include"))
            extraOpts(
                "-libraryPath",
                rustArchive.get().asFile.parentFile.absolutePath,
                "-staticLibrary",
                rustLibrary,
            )
            tasks.named(interopProcessingTaskName).configure {
                dependsOn(rustTask)
                inputs.file(rustArchive)
            }
        }
        target.binaries.all {
            freeCompilerArgs +=
                "-Xoverride-konan-properties=osVersionMin.${target.konanTarget.name}=$minimumIosVersion"
        }
        target.binaries.framework {
            baseName = "CodexAgent"
            isStatic = true
            export(project(":codex-agent-client"))
            xcframework.add(this)
        }
    }
}

val iosRuntimeMetrics = layout.buildDirectory.file("reports/ios-release/runtime-metrics.json")
iosRuntimeMetrics.get().asFile.parentFile.mkdirs()
tasks.named<KotlinNativeTest>("iosSimulatorArm64Test") {
    val metricsFile = iosRuntimeMetrics.get().asFile
    environment("CODEX_AGENT_IOS_METRICS_PATH", metricsFile.absolutePath)
    environment("SIMCTL_CHILD_CODEX_AGENT_IOS_METRICS_PATH", metricsFile.absolutePath)
    outputs.file(metricsFile)
    doLast("verifyIosRuntimeMetrics") {
        check(metricsFile.isFile) { "iOS runtime metrics were not recorded" }
    }
}

val verifyAppleToolchain = registerAppleToolchainVerificationTask(
    pinnedXcodeVersion,
    pinnedXcodeBuild,
    pinnedSwiftVersion,
)
val importedDeviceFramework = providers.gradleProperty("codexAgent.iosDeviceFrameworkDirectory").orNull?.let {
    tasks.register<ImportCodexAgentFrameworkTask>("importCodexAgentIosDeviceFramework") {
        frameworkDirectory.set(layout.dir(providers.provider { file(it) }))
        platformName.set("iphoneos")
        importedFrameworkDirectory.set(layout.buildDirectory.dir("imported-frameworks/device/CodexAgent.framework"))
    }
}
val importedSimulatorFramework = providers.gradleProperty("codexAgent.iosSimulatorFrameworkDirectory").orNull?.let {
    tasks.register<ImportCodexAgentFrameworkTask>("importCodexAgentIosSimulatorFramework") {
        frameworkDirectory.set(layout.dir(providers.provider { file(it) }))
        platformName.set("iphonesimulator")
        importedFrameworkDirectory.set(layout.buildDirectory.dir("imported-frameworks/simulator/CodexAgent.framework"))
    }
}
tasks.register<VerifyIosFreeDiskSpaceTask>("preflightIosRuntime") {
    group = "verification"
    description = "Requires enough free disk and the pinned Apple toolchain before the full iOS gate."
    dependsOn(verifyAppleToolchain)
    minimumFreeGiB.set(
        providers.gradleProperty("codexAgent.iosMinimumFreeDiskGiB").map { value -> value.toLong() }.orElse(40L),
    )
    workspaceDirectory.set(rootProject.layout.projectDirectory)
    reportFile.set(layout.buildDirectory.file("reports/ios-development/preflight.json"))
}
tasks.register<VerifySwiftSimulatorCompilationTask>("verifyCodexAgentSwiftSimulatorCompilation") {
    group = "verification"
    description = "Compiles the Swift package and tests against only the simulator framework."
    dependsOn(verifyAppleToolchain)
    if (importedSimulatorFramework != null) dependsOn(importedSimulatorFramework)
    else dependsOn("linkDebugFrameworkIosSimulatorArm64")
    packageManifest.set(layout.projectDirectory.file("apple/Package.swift"))
    sourcesDirectory.set(layout.projectDirectory.dir("apple/Sources"))
    testsDirectory.set(layout.projectDirectory.dir("apple/Tests"))
    simulatorFrameworkDirectory.set(
        importedSimulatorFramework?.flatMap { it.importedFrameworkDirectory }
            ?: layout.buildDirectory.dir("bin/iosSimulatorArm64/debugFramework/CodexAgent.framework"),
    )
    this.expectedXcodeVersion.set(pinnedXcodeVersion)
    this.expectedXcodeBuild.set(pinnedXcodeBuild)
    this.expectedSwiftVersion.set(pinnedSwiftVersion)
    derivedDataDirectory.set(layout.buildDirectory.dir("swift-simulator-compilation-derived-data"))
    compiledProductsDirectory.set(layout.buildDirectory.dir("swift-simulator-compilation-products"))
    reportFile.set(layout.buildDirectory.file("reports/ios-development/swift-simulator-compilation.json"))
}
val appleDistributionTasks = registerIosAppleDistributionTasks(
    expectedSwiftTestCount,
    pinnedRustToolchain,
    importedDeviceFramework,
    importedSimulatorFramework,
)
val appleReleaseTasks = registerIosAppleReleaseVerificationTasks(
    appleDistributionTasks,
    minimumIosVersion,
    pinnedRustToolchain,
)
private val verifiedDistributionTasks = registerIosVerifiedDistributionTasks(
    appleDistributionTasks,
    appleReleaseTasks,
    iosRuntimeMetrics,
)

tasks.register("verifyIosRuntime") {
    group = "verification"
    description = "Builds and tests the embedded iOS runtime and clean Swift Package consumer."
    val imported = verifiedDistributionTasks.validateImported
    if (imported != null) dependsOn(imported) else {
        if (!providers.gradleProperty("codexAgent.iosNativeEvidenceDirectory").isPresent) {
            dependsOn(nativeTasks.testCodexIosBridge, nativeTasks.testCodexIosDirectToolMode)
        }
        dependsOn(
            verifyAppleToolchain,
            "compileKotlinIosArm64",
            "iosSimulatorArm64Test",
            appleDistributionTasks.packageCodexAgentAppleDistribution,
            appleReleaseTasks.verifyCodexAgentRemoteSwiftPackage,
            appleDistributionTasks.verifyCodexAgentSwiftPackage,
            appleDistributionTasks.verifyCodexAgentSwiftAuthenticationTests,
            appleReleaseTasks.verifyIosDeploymentTargets,
            appleDistributionTasks.verifyIosLicensePackaging,
            appleReleaseTasks.verifyIosPrivacyManifest,
            appleReleaseTasks.verifyIosReleaseBudgets,
        )
    }
}
