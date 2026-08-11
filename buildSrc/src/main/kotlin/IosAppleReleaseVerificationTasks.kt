import java.io.File
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.register

data class IosAppleReleaseVerificationTasks(
    val verifyCodexAgentRemoteSwiftPackage: TaskProvider<VerifySwiftPackageBinaryTask>,
    val verifyIosDeploymentTargets: TaskProvider<VerifyIosDeploymentTargetsTask>,
    val verifyIosPrivacyManifest: TaskProvider<VerifyIosPrivacyAuditTask>,
    val verifyIosReleaseBudgets: TaskProvider<VerifyIosReleaseBudgetsTask>,
)

fun Project.registerAppleToolchainVerificationTask(
    expectedXcodeVersion: String,
    expectedXcodeBuild: String,
    expectedSwiftVersion: String,
) = tasks.register<VerifyAppleToolchainTask>("verifyAppleToolchain") {
    this.expectedXcodeVersion.set(expectedXcodeVersion)
    this.expectedXcodeBuild.set(expectedXcodeBuild)
    this.expectedSwiftVersion.set(expectedSwiftVersion)
    reportDirectory.set(layout.buildDirectory.dir("reports/ios-release/toolchain"))
}

fun Project.registerIosAppleReleaseVerificationTasks(
    distribution: IosAppleDistributionTasks,
    minimumIosVersion: String,
    pinnedRustToolchain: String,
): IosAppleReleaseVerificationTasks {
    val swiftPackageArchiveName = "CodexAgent-${project.version}.xcframework.zip"
    val packageCodexAgentSwiftPackageBinary = tasks.register<Zip>("packageCodexAgentSwiftPackageBinary") {
        dependsOn(distribution.prepareCodexAgentReleaseXCFramework)
        archiveFileName.set(swiftPackageArchiveName)
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
        from(distribution.releaseXCFrameworkDirectory) { into("CodexAgent.xcframework") }
    }

    val verifyIosDeploymentTargets = tasks.register<VerifyIosDeploymentTargetsTask>("verifyIosDeploymentTargets") {
        dependsOn(distribution.prepareCodexAgentReleaseXCFramework)
        xcframeworkDirectory.set(distribution.releaseXCFrameworkDirectory)
        this.minimumIosVersion.set(minimumIosVersion)
        reportFile.set(layout.buildDirectory.file("reports/ios-release/deployment-targets.txt"))
    }

    val rustSysroot = providers.exec {
        commandLine("rustup", "run", pinnedRustToolchain, "rustc", "--print", "sysroot")
    }.standardOutput.asText.map { it.trim() }
    val rustHost = providers.exec {
        commandLine("rustup", "run", pinnedRustToolchain, "rustc", "-vV")
    }.standardOutput.asText.map { output ->
        output.lineSequence().single { it.startsWith("host: ") }.removePrefix("host: ")
    }
    val llvmNm = layout.file(providers.provider {
        File("${rustSysroot.get()}/lib/rustlib/${rustHost.get()}/bin/llvm-nm")
    })
    val xcodeStrings = layout.file(providers.exec {
        commandLine("/usr/bin/xcrun", "--find", "strings")
    }.standardOutput.asText.map { File(it.trim()) })
    val collectIosPrivacyEvidence = tasks.register<GenerateIosPrivacyEvidenceTask>("collectIosPrivacyEvidence") {
        dependsOn(distribution.prepareCodexAgentReleaseXCFramework, distribution.verifyCodexAgentSwiftPackage)
        val reportDirectory = layout.buildDirectory.dir("reports/ios-release/privacy")
        xcframeworkDirectory.set(distribution.releaseXCFrameworkDirectory)
        archivedApplicationDirectory.set(layout.buildDirectory.dir("CodexAgentTestApp.xcarchive"))
        privacyManifest.set(distribution.privacyManifestFile)
        dataFlowReview.set(rootProject.layout.projectDirectory.file("release/privacy-data-flow-review.json"))
        llvmNmExecutable.set(llvmNm)
        stringsExecutable.set(xcodeStrings)
        policyFile.set(reportDirectory.map { it.file("policy.json") })
        evidenceFile.set(reportDirectory.map { it.file("evidence.json") })
    }

    val verifyIosPrivacyManifest = tasks.register<VerifyIosPrivacyAuditTask>("verifyIosPrivacyManifest") {
        dependsOn(collectIosPrivacyEvidence)
        policyFile.set(collectIosPrivacyEvidence.flatMap { it.policyFile })
        evidenceFile.set(collectIosPrivacyEvidence.flatMap { it.evidenceFile })
        val reviewPath = providers.gradleProperty("codexAgent.privacyRequiredReasonReview").orNull
            ?: "release/privacy-required-reason-review.json"
        reviewFile.set(rootProject.layout.projectDirectory.file(reviewPath))
        auditFile.set(layout.buildDirectory.file("reports/ios-release/privacy/audit.json"))
    }

    val verifyIosReleaseBudgets = tasks.register<VerifyIosReleaseBudgetsTask>("verifyIosReleaseBudgets") {
        dependsOn(packageCodexAgentSwiftPackageBinary, distribution.verifyCodexAgentSwiftPackage)
        policyFile.set(rootProject.layout.projectDirectory.file("release/ios-resource-policy.json"))
        archiveFile.set(packageCodexAgentSwiftPackageBinary.flatMap { it.archiveFile })
        deviceBinary.set(distribution.releaseXCFrameworkDirectory.map {
            it.file("ios-arm64/CodexAgent.framework/CodexAgent")
        })
        applicationDirectory.set(
            layout.buildDirectory.dir("CodexAgentTestApp.xcarchive/Products/Applications/CodexAgentTestApp.app"),
        )
        reportFile.set(layout.buildDirectory.file("reports/ios-release/artifact-metrics.json"))
    }

    val swiftPackageChecksumFile = layout.buildDirectory.file("distributions/$swiftPackageArchiveName.sha256")
    val generateCodexAgentSwiftPackageChecksum =
        tasks.register<GenerateSha256Task>("generateCodexAgentSwiftPackageChecksum") {
            dependsOn(packageCodexAgentSwiftPackageBinary)
            inputFile.set(packageCodexAgentSwiftPackageBinary.flatMap { it.archiveFile })
            outputFile.set(swiftPackageChecksumFile)
        }

    val clean = tasks.named("clean")
    val toolchain = tasks.named("verifyAppleToolchain")
    packageCodexAgentSwiftPackageBinary.configure { mustRunAfter(clean) }
    toolchain.configure { mustRunAfter(clean) }
    tasks.register<RecordSwiftPackageBaselineTask>("recordCodexAgentSwiftPackageBaseline") {
        dependsOn(clean, toolchain, generateCodexAgentSwiftPackageChecksum)
        expectedCommit.set(providers.gradleProperty("codexAgent.commitA"))
        version.set(project.version.toString())
        expectedUrl.set(
            "https://github.com/ciurlaro/codex-agent/releases/download/v${project.version}/$swiftPackageArchiveName",
        )
        repositoryDirectory.set(rootProject.layout.projectDirectory)
        archiveFile.set(packageCodexAgentSwiftPackageBinary.flatMap { it.archiveFile })
        checksumFile.set(swiftPackageChecksumFile)
        manifestFile.set(rootProject.layout.projectDirectory.file("Package.swift"))
        provenanceFile.set(layout.projectDirectory.file("native/provenance.json"))
        xcodeVersionFile.set(layout.buildDirectory.file("reports/ios-release/toolchain/xcode.txt"))
        swiftVersionFile.set(layout.buildDirectory.file("reports/ios-release/toolchain/swift.txt"))
        proofFile.set(
            rootProject.layout.file(
                providers.gradleProperty("codexAgent.swiftPmBaselineProof").map(rootProject::file),
            ),
        )
    }

    tasks.register<VerifySwiftPackageABTask>("verifyCodexAgentSwiftPackageAB") {
        expectedCommit.set(providers.gradleProperty("codexAgent.candidateCommit"))
        version.set(project.version.toString())
        expectedUrl.set(
            "https://github.com/ciurlaro/codex-agent/releases/download/v${project.version}/$swiftPackageArchiveName",
        )
        repositoryDirectory.set(rootProject.layout.projectDirectory)
        baselineProof.set(
            rootProject.layout.file(
                providers.gradleProperty("codexAgent.swiftPmBaselineProof").map(rootProject::file),
            ),
        )
        archiveFile.set(layout.buildDirectory.file("distributions/$swiftPackageArchiveName"))
        checksumFile.set(swiftPackageChecksumFile)
        manifestFile.set(rootProject.layout.projectDirectory.file("Package.swift"))
        provenanceFile.set(layout.projectDirectory.file("native/provenance.json"))
        xcodeVersionFile.set(layout.buildDirectory.file("reports/ios-release/toolchain/xcode.txt"))
        swiftVersionFile.set(layout.buildDirectory.file("reports/ios-release/toolchain/swift.txt"))
        proofFile.set(
            rootProject.layout.file(
                providers.gradleProperty("codexAgent.swiftPmABProof").map(rootProject::file),
            ),
        )
    }

    val verifyCodexAgentRemoteSwiftPackage =
        tasks.register<VerifySwiftPackageBinaryTask>("verifyCodexAgentRemoteSwiftPackage") {
            group = "verification"
            description = "Verifies the public SwiftPM manifest URL and binary checksum."
            dependsOn(generateCodexAgentSwiftPackageChecksum)
            manifest.set(rootProject.layout.projectDirectory.file("Package.swift"))
            checksumFile.set(swiftPackageChecksumFile)
            expectedUrl.set(
                "https://github.com/ciurlaro/codex-agent/releases/download/v${project.version}/$swiftPackageArchiveName",
            )
        }

    return IosAppleReleaseVerificationTasks(
        verifyCodexAgentRemoteSwiftPackage = verifyCodexAgentRemoteSwiftPackage,
        verifyIosDeploymentTargets = verifyIosDeploymentTargets,
        verifyIosPrivacyManifest = verifyIosPrivacyManifest,
        verifyIosReleaseBudgets = verifyIosReleaseBudgets,
    )
}
