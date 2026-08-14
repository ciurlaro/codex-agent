import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal data class IosVerifiedDistributionTasks(
    val validateImported: TaskProvider<ImportAppleVerifiedDistributionTask>?,
)

internal fun Project.registerIosVerifiedDistributionTasks(
    distribution: IosAppleDistributionTasks,
    release: IosAppleReleaseVerificationTasks,
    runtimeMetrics: Provider<RegularFile>,
): IosVerifiedDistributionTasks {
    val candidateCommit = providers.gradleProperty("codexAgent.candidateCommit")
    val nativeEvidencePath = providers.gradleProperty("codexAgent.iosNativeEvidenceDirectory")
    val nativeEvidence = layout.dir(nativeEvidencePath.map(rootProject::file))
    val nativeReceipt = layout.buildDirectory.file("imported-rust/ios-native-evidence.json")
    val provenance = layout.projectDirectory.file("native/provenance.json")
    val packageSwift = rootProject.layout.projectDirectory.file("Package.swift")
    val xcode = layout.buildDirectory.file("reports/ios-release/toolchain/xcode.txt")
    val swift = layout.buildDirectory.file("reports/ios-release/toolchain/swift.txt")
    val reportFiles = files(
        release.verifyIosReleaseBudgets.flatMap { it.reportFile },
        release.verifyIosDeploymentTargets.flatMap { it.reportFile },
        distribution.verifyIosLicensePackaging.flatMap { it.reportFile },
        runtimeMetrics,
        release.verifyIosPrivacyManifest.flatMap { it.auditFile },
        release.verifyIosPrivacyManifest.flatMap { it.evidenceFile },
        release.verifyIosPrivacyManifest.flatMap { it.policyFile },
        release.verifyIosPrivacyManifest.flatMap { it.reviewFile },
        distribution.verifyCodexAgentSwiftAuthenticationTests.flatMap { it.summaryFile },
    )
    val importedPath = providers.gradleProperty(IOS_VERIFIED_DISTRIBUTION_PROPERTY)
    tasks.register<ExportAppleVerifiedDistributionTask>("exportCodexAgentIosVerifiedDistribution") {
        dependsOn("verifyIosRuntime", "validateImportedCodexAgentIosNativeEvidence")
        this.candidateCommit.set(candidateCommit)
        version.set(project.version.toString())
        freshSemanticVerification.set(importedPath.map { false }.orElse(true))
        applePackageArchive.set(distribution.packageCodexAgentAppleDistribution.flatMap { it.archiveFile })
        swiftPackageArchive.set(release.packageCodexAgentSwiftPackageBinary.flatMap { it.archiveFile })
        swiftPackageChecksum.set(release.generateCodexAgentSwiftPackageChecksum.flatMap { it.outputFile })
        privacyReviewFile.set(release.verifyIosPrivacyManifest.flatMap { it.reviewFile })
        this.reportFiles.from(reportFiles)
        xcodeVersionFile.set(xcode); swiftVersionFile.set(swift)
        nativeEvidenceDirectory.set(nativeEvidence); nativeEvidenceReceipt.set(nativeReceipt)
        nativeProvenance.set(provenance); this.packageSwift.set(packageSwift)
        repositoryDirectory.set(rootProject.layout.projectDirectory)
        canonicalBuildDirectory.set(layout.buildDirectory)
        outputDirectory.set(layout.buildDirectory.dir("apple-verified-distribution"))
    }
    if (!importedPath.isPresent) return IosVerifiedDistributionTasks(null)
    val validate = tasks.register<ImportAppleVerifiedDistributionTask>(
        "validateImportedCodexAgentIosVerifiedDistribution",
    ) {
        dependsOn("verifyAppleToolchain", "validateImportedCodexAgentIosNativeEvidence")
        this.candidateCommit.set(candidateCommit)
        version.set(project.version.toString())
        evidenceDirectory.set(layout.dir(importedPath.map(rootProject::file)))
        nativeEvidenceDirectory.set(nativeEvidence); nativeEvidenceReceipt.set(nativeReceipt)
        nativeProvenance.set(provenance); this.packageSwift.set(packageSwift)
        currentXcodeVersionFile.set(xcode); currentSwiftVersionFile.set(swift)
        repositoryDirectory.set(rootProject.layout.projectDirectory)
        canonicalBuildDirectory.set(layout.buildDirectory)
        verificationReceipt.set(layout.buildDirectory.file(
            "imported-verified-apple/verified-distribution-receipt.json",
        ))
    }
    distribution.packageCodexAgentAppleDistribution.configure {
        setDependsOn(listOf(validate)); onlyIf { false }
    }
    release.packageCodexAgentSwiftPackageBinary.configure {
        setDependsOn(listOf(validate)); onlyIf { false }
    }
    release.generateCodexAgentSwiftPackageChecksum.configure { setDependsOn(listOf(validate)) }
    return IosVerifiedDistributionTasks(validate)
}
