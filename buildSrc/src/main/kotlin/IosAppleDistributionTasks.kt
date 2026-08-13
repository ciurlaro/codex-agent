import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.register

data class IosAppleDistributionTasks(
    val appleDistributionDirectory: Provider<Directory>,
    val releaseXCFrameworkDirectory: Provider<Directory>,
    val privacyManifestFile: RegularFile,
    val prepareCodexAgentReleaseXCFramework: TaskProvider<PrepareCodexAgentReleaseXCFrameworkTask>,
    val packageCodexAgentAppleDistribution: TaskProvider<Zip>,
    val verifyCodexAgentSwiftPackage: TaskProvider<Exec>,
    val verifyCodexAgentSwiftAuthenticationTests: TaskProvider<VerifySwiftAuthenticationTestsTask>,
    val verifyIosLicensePackaging: TaskProvider<VerifyIosLicensePackagingTask>,
)

fun Project.registerIosAppleDistributionTasks(
    expectedSwiftTestCount: Int,
    pinnedRustToolchain: String,
): IosAppleDistributionTasks {
    val appleDistributionDirectory = layout.buildDirectory.dir("apple-distribution")
    val assembledXCFrameworkDirectory = layout.buildDirectory.dir("XCFrameworks/release/CodexAgent.xcframework")
    val releaseXCFrameworkDirectory = layout.buildDirectory.dir("release-xcframework/CodexAgent.xcframework")
    val privacyManifestFile = layout.projectDirectory.file("apple/Sources/CodexAgentAuthentication/PrivacyInfo.xcprivacy")
    val licenseFile = rootProject.layout.projectDirectory.file("LICENSE")
    val thirdPartyNotices = rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")
    val codexLicense = rootProject.layout.projectDirectory.file(
        "codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt",
    )
    val codexNotice = rootProject.layout.projectDirectory.file(
        "codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt",
    )

    val prepareCodexAgentReleaseXCFramework =
        tasks.register<PrepareCodexAgentReleaseXCFrameworkTask>("prepareCodexAgentReleaseXCFramework") {
            dependsOn("assembleCodexAgentReleaseXCFramework")
            this.assembledXCFrameworkDirectory.set(assembledXCFrameworkDirectory)
            privacyManifest.set(privacyManifestFile)
            forbiddenAbsolutePathPrefixes.set(iosReleaseAbsolutePathPrefixes(pinnedRustToolchain))
            this.releaseXCFrameworkDirectory.set(releaseXCFrameworkDirectory)
        }

    val stageCodexAgentAppleDistribution =
        tasks.register<StageCodexAgentAppleDistributionTask>("stageCodexAgentAppleDistribution") {
            dependsOn(prepareCodexAgentReleaseXCFramework)
            packageManifest.set(layout.projectDirectory.file("apple/Package.swift"))
            sourcesDirectory.set(layout.projectDirectory.dir("apple/Sources"))
            testsDirectory.set(layout.projectDirectory.dir("apple/Tests"))
            xcframeworkDirectory.set(releaseXCFrameworkDirectory)
            this.licenseFile.set(licenseFile)
            this.thirdPartyNotices.set(thirdPartyNotices)
            this.codexLicense.set(codexLicense)
            this.codexNotice.set(codexNotice)
            testApplication.set(layout.projectDirectory.dir("apple/TestApp"))
            distributionDirectory.set(appleDistributionDirectory)
        }

    val verifyCodexAgentSwiftPackage = tasks.register<Exec>("verifyCodexAgentSwiftPackage") {
        dependsOn(stageCodexAgentAppleDistribution)
        workingDir(appleDistributionDirectory.map { it.dir("CodexAgentTestApp") })
        commandLine(
            "xcodebuild",
            "-project", "CodexAgentTestApp.xcodeproj",
            "-scheme", "CodexAgentTestApp",
            "-configuration", "Release",
            "-destination", "generic/platform=iOS",
            "-derivedDataPath", layout.buildDirectory.dir("swift-consumer-derived-data").get().asFile.absolutePath,
            "-archivePath", layout.buildDirectory.file("CodexAgentTestApp.xcarchive").get().asFile.absolutePath,
            "ARCHS=arm64",
            "CODE_SIGNING_ALLOWED=NO",
            "SKIP_INSTALL=NO",
            "clean",
            "archive",
        )
    }

    val verifyCodexAgentSwiftAuthenticationTests =
        tasks.register<VerifySwiftAuthenticationTestsTask>("verifyCodexAgentSwiftAuthenticationTests") {
            dependsOn(stageCodexAgentAppleDistribution)
            packageDirectory.set(appleDistributionDirectory.map { it.dir("CodexAgentPackage") })
            runtimeName.set("iOS 26.5")
            deviceTypeIdentifier.set("com.apple.CoreSimulator.SimDeviceType.iPhone-17")
            this.expectedTestCount.set(expectedSwiftTestCount)
            derivedDataDirectory.set(layout.buildDirectory.dir("swift-tests-derived-data"))
            simulatorDevicesFile.set(layout.buildDirectory.file("simulator-devices.json"))
            resultBundleDirectory.set(layout.buildDirectory.dir("swift-authentication-tests.xcresult"))
            summaryFile.set(layout.buildDirectory.file("swift-authentication-tests-summary.json"))
        }

    val packageCodexAgentAppleDistribution = tasks.register<Zip>("packageCodexAgentAppleDistribution") {
        dependsOn(stageCodexAgentAppleDistribution)
        archiveFileName.set("CodexAgentPackage-${project.version}.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(appleDistributionDirectory.map { it.dir("CodexAgentPackage") })
    }

    val verifyIosLicensePackaging = tasks.register<VerifyIosLicensePackagingTask>("verifyIosLicensePackaging") {
        dependsOn(stageCodexAgentAppleDistribution)
        packageDirectory.set(appleDistributionDirectory.map { it.dir("CodexAgentPackage") })
        this.licenseFile.set(licenseFile)
        this.thirdPartyNotices.set(thirdPartyNotices)
        this.codexLicense.set(codexLicense)
        this.codexNotice.set(codexNotice)
        buildScript.set(layout.projectDirectory.file("build.gradle.kts"))
        reportFile.set(layout.buildDirectory.file("reports/ios-release/license-packaging.txt"))
    }

    return IosAppleDistributionTasks(
        appleDistributionDirectory,
        releaseXCFrameworkDirectory,
        privacyManifestFile,
        prepareCodexAgentReleaseXCFramework,
        packageCodexAgentAppleDistribution,
        verifyCodexAgentSwiftPackage,
        verifyCodexAgentSwiftAuthenticationTests,
        verifyIosLicensePackaging,
    )
}
