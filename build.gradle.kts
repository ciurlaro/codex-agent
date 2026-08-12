import java.io.File
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
plugins {
    base
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
}
val candidateCommitValue = providers.gradleProperty("codexAgent.candidateCommit")
val candidateReleaseTag = providers.gradleProperty("codexAgent.releaseTag")
val candidatePathCommit = candidateCommitValue.orElse("UNBOUND")
val candidateRoot = layout.buildDirectory.dir(candidatePathCommit.map { "protected-candidate/$it" })
val candidateArtifacts = candidateRoot.map { it.dir("artifacts") }
val candidateEvidence = candidateRoot.map { it.dir("evidence") }
val candidateReports = candidateRoot.map { it.dir("reports") }
val centralStagingDirectory = candidateRoot.map { it.dir("maven-repository") }
subprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "CENTRAL_STAGING"
                url = centralStagingDirectory.get().asFile.toURI()
            }
        }
    }
}
allprojects {
    group = "io.github.ciurlaro"
    version = "0.2.0"
}
registerRepositoryVerificationTasks()
tasks.register<VerifyReleaseMetadataTask>("verifyReleaseMetadata") {
    group = "verification"
    projectVersion.set(project.version.toString())
    releaseTag.set(providers.gradleProperty("codexAgent.releaseTag").orElse("v${project.version}"))
    swiftPackageManifest.set(layout.projectDirectory.file("Package.swift"))
    remoteConsumerManifest.set(layout.projectDirectory.file("codex-agent-runtime-ios/apple/RemoteConsumer/Package.swift"))
}
val publicationApprovals = layout.projectDirectory.file("release/publication-approvals.json")
val privacyManifestFile = layout.projectDirectory.file(
    "codex-agent-runtime-ios/apple/Sources/CodexAgentAuthentication/PrivacyInfo.xcprivacy",
)
val privacyDataFlowReviewFile = layout.projectDirectory.file("release/privacy-data-flow-review.json")
val privacyRequiredReasonReviewTemplate = layout.projectDirectory.file("release/privacy-required-reason-review.json")
val privacyRequiredReasonReviewOverride =
    layout.file(providers.gradleProperty("codexAgent.privacyRequiredReasonReview").map { File(it) })
val generatedPrivacyRequiredReasonReview = layout.projectDirectory.file(
    "codex-agent-runtime-ios/build/reports/ios-release/privacy/privacy-required-reason-review.json")
val privacyRequiredReasonReview = privacyRequiredReasonReviewOverride.orElse(generatedPrivacyRequiredReasonReview)
val desktopDistributionManifestFile =
    layout.projectDirectory.file("codex-agent-runtime-desktop/codex-app-server-distributions.json")
val desktopBundledLicenseFile =
    layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt")
val desktopBundledNoticeFile =
    layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt")
tasks.register<VerifyPublicationReadinessTask>("verifyPublicationReadiness") {
    group = "verification"
    approvalsFile.set(publicationApprovals)
    privacyManifest.set(privacyManifestFile)
    privacyInventory.set(privacyDataFlowReviewFile)
    desktopDistributionManifest.set(desktopDistributionManifestFile)
    desktopBundledLicense.set(desktopBundledLicenseFile)
    desktopBundledNotice.set(desktopBundledNoticeFile)
}
val androidEvidenceFile = layout.file(providers.gradleProperty("codexAgent.androidEvidenceFile").map(::file))
val androidEvidenceDirectory = layout.dir(androidEvidenceFile.map { it.asFile.parentFile })
val desktopEvidenceDirectory = providers.gradleProperty("codexAgent.desktopEvidenceDirectory")
val desktopEvidenceFiles = objects.fileCollection().apply {
    desktopRuntimeEvidenceTargets.keys.forEach { target ->
        from(desktopEvidenceDirectory.map { directory -> file("$directory/${desktopRuntimeEvidenceFileName(target)}") })
    }
}
val prepareProtectedCandidate = tasks.register<PrepareProtectedCandidateTask>("prepareProtectedCandidate") {
    dependsOn("verifyReleaseMetadata")
    version.set(project.version.toString())
    releaseTag.set(candidateReleaseTag)
    candidateCommit.set(candidateCommitValue)
    parallelExecution.set(gradle.startParameter.isParallelProjectExecutionEnabled)
    androidEvidence.set(androidEvidenceFile)
    desktopEvidence.from(desktopEvidenceFiles)
    repositoryDirectory.set(layout.projectDirectory)
    candidateDirectory.set(candidateRoot)
}
val stageCentralRepository = tasks.register("stageCentralRepository") {
    group = "publishing"
    dependsOn(
        ":codex-agent-client:publishAllPublicationsToCENTRAL_STAGINGRepository",
        ":codex-agent-runtime-android:publishAllPublicationsToCENTRAL_STAGINGRepository",
        ":codex-agent-runtime-desktop:publishAllPublicationsToCENTRAL_STAGINGRepository",
        ":codex-agent-runtime-ios:publishAllPublicationsToCENTRAL_STAGINGRepository",
    )
}

val mavenInventoryFile = candidateReports.map { it.file("maven-inventory.json") }
val verifyCentralStaging = tasks.register<VerifyMavenStagingTask>("verifyCentralStaging") {
    group = "verification"
    description = "Verifies the exact signed 22-coordinate staged KMP repository and materializes checksums."
    dependsOn(stageCentralRepository)
    repositoryDirectory.set(centralStagingDirectory)
    groupId.set(project.group.toString())
    version.set(project.version.toString())
    requireSignatures.set(true)
    inventoryFile.set(mavenInventoryFile)
}
val rootLocalProperties = layout.projectDirectory.file("local.properties")
val rootAndroidSdkDirectory = providers.environmentVariable("ANDROID_HOME").orElse(
    providers.fileContents(rootLocalProperties).asText.map { contents ->
        contents.lineSequence().single { it.startsWith("sdk.dir=") }.substringAfter('=')
    },
)
val cleanKmpConsumerResult = candidateReports.map { it.file("clean-kmp-consumer.json") }
val verifyStagedKmpConsumer = tasks.register<VerifyStagedKmpConsumerTask>("verifyStagedKmpConsumer") {
    group = "verification"
    description = "Builds an isolated consumer for every published KMP target from CENTRAL_STAGING only."
    dependsOn(verifyCentralStaging)
    repositoryDirectory.set(centralStagingDirectory)
    templateDirectory.set(layout.projectDirectory.dir("release/kmp-consumer-template"))
    mavenInventory.set(mavenInventoryFile)
    gradleWrapper.set(layout.projectDirectory.file("gradlew"))
    projectVersion.set(project.version.toString())
    androidSdkDirectory.set(rootAndroidSdkDirectory)
    consumerDirectory.set(candidateRoot.map { it.dir("clean-consumer") })
    resultFile.set(cleanKmpConsumerResult)
}
val centralBundleFile = candidateArtifacts.map { it.file("codex-agent-${project.version}-central.zip") }
val centralBundleInventory = candidateReports.map { it.file("central-bundle.json") }
val packageCentralBundle = tasks.register<BuildCentralBundleTask>("packageCentralBundle") {
    group = "publishing"
    description = "Builds and inventories the exact deterministic Central Portal bundle."
    dependsOn(verifyStagedKmpConsumer)
    repositoryDirectory.set(centralStagingDirectory)
    mavenInventory.set(mavenInventoryFile)
    maximumBytes.set(1_000_000_000L)
    bundleFile.set(centralBundleFile)
    inventoryFile.set(centralBundleInventory)
}
val stagedAndroidDirectory = candidateEvidence.map { it.dir("android") }
val stagedAndroidEvidence = stagedAndroidDirectory.map { it.file("android-runtime-evidence.json") }
val stageAndroidEvidence = tasks.register<StageAndroidEvidenceTask>("stageAndroidRuntimeEvidence") {
    evidenceFile.set(androidEvidenceFile)
    evidenceDirectory.set(androidEvidenceDirectory)
    outputDirectory.set(stagedAndroidDirectory)
}
val stagedAndroidAar = centralStagingDirectory.map {
    it.file("io/github/ciurlaro/codex-agent-runtime-android/${project.version}/" +
        "codex-agent-runtime-android-${project.version}.aar")
}
val androidEvidenceVerification = tasks.register<VerifyAndroidRuntimeEvidenceTask>("verifyAndroidRuntimeEvidence") {
    dependsOn(verifyCentralStaging, stageAndroidEvidence)
    expectedCommit.set(candidateCommitValue)
    pinnedRuntimeSha256.set(providers.gradleProperty("codexAgent.codexBinarySha256"))
    evidenceFile.set(stagedAndroidEvidence)
    evidenceDirectory.set(stagedAndroidDirectory)
    stagedAar.set(stagedAndroidAar)
    apkanalyzerExecutable.set(layout.file(rootAndroidSdkDirectory.map { File(it, "cmdline-tools/latest/bin/apkanalyzer") }))
    verificationFile.set(candidateReports.map { it.file("android-runtime-verification.json") })
}

val swiftArchiveName = "CodexAgent-${project.version}.xcframework.zip"
val stagedSwiftZip = candidateArtifacts.map { it.file(swiftArchiveName) }
val stagedSwiftChecksum = candidateArtifacts.map { it.file("$swiftArchiveName.sha256") }
val stagedSwiftPmProof = candidateEvidence.map { it.file("swiftpm-proof.json") }
val stageSwiftZip = tasks.register<CopyCandidateFileTask>("stageProtectedSwiftPackage") {
    sourceFile.set(layout.projectDirectory.file("codex-agent-runtime-ios/build/distributions/$swiftArchiveName"))
    outputFile.set(stagedSwiftZip)
}
val stageSwiftChecksum = tasks.register<CopyCandidateFileTask>("stageProtectedSwiftChecksum") {
    sourceFile.set(layout.projectDirectory.file("codex-agent-runtime-ios/build/distributions/$swiftArchiveName.sha256"))
    outputFile.set(stagedSwiftChecksum)
}
val stagedPrivacyAudit = candidateEvidence.map { it.file("privacy-audit.json") }
val stagePrivacyAudit = tasks.register<CopyCandidateFileTask>("stageProtectedPrivacyAudit") {
    sourceFile.set(layout.projectDirectory.file("codex-agent-runtime-ios/build/reports/ios-release/privacy/audit.json"))
    outputFile.set(stagedPrivacyAudit)
}

val runtimeMetrics = layout.projectDirectory.file("codex-agent-runtime-ios/build/reports/ios-release/runtime-metrics.json")
val resourceEvidence = candidateEvidence.map { it.file("resource-measurement.json") }
val measureCandidateResources = tasks.register<ConsumeReleaseResourceReportTask>("measureProtectedCandidateResources") {
    phase.set("ios-runtime-benchmark")
    producerTaskPath.set(":codex-agent-runtime-ios:iosSimulatorArm64Test")
    metricsFile.set(runtimeMetrics)
    workspace.set(layout.projectDirectory)
    trackedPaths.from(candidateArtifacts, centralStagingDirectory)
    outputFile.set(resourceEvidence)
}

val candidateManifest = candidateRoot.map { it.file("candidate-manifest.json") }
val generateCandidateManifest = tasks.register<GenerateCandidateManifestTask>("generateCandidateManifest") {
    group = "publishing"
    description = "Generates the one canonical hash-bound protected-candidate manifest."
    candidateVersion.set(project.version.toString())
    releaseTag.set(candidateReleaseTag)
    candidateCommit.set(candidateCommitValue)
    swiftZip.set(stagedSwiftZip)
    swiftChecksum.set(stagedSwiftChecksum)
    swiftPmProof.set(stagedSwiftPmProof)
    centralBundle.set(centralBundleFile)
    centralInventory.set(centralBundleInventory)
    mavenInventory.set(mavenInventoryFile)
    kmpConsumer.set(cleanKmpConsumerResult)
    androidEvidence.set(stagedAndroidEvidence)
    desktopEvidence.from(desktopEvidenceFiles)
    privacyAudit.set(stagedPrivacyAudit); artifactMetrics.set(layout.projectDirectory.file("codex-agent-runtime-ios/build/reports/ios-release/artifact-metrics.json"))
    resourceReports.from(resourceEvidence)
    approvalsFile.set(publicationApprovals)
    privacyManifest.set(privacyManifestFile)
    privacyDataFlowReview.set(privacyDataFlowReviewFile)
    privacyReviews.set(privacyRequiredReasonReview)
    packageSwift.set(layout.projectDirectory.file("Package.swift"))
    desktopDistributionManifest.set(desktopDistributionManifestFile)
    desktopBundledLicense.set(desktopBundledLicenseFile)
    desktopBundledNotice.set(desktopBundledNoticeFile)
    outputFile.set(candidateManifest)
}
val verifyCandidateManifest = tasks.register<VerifyProtectedCandidateManifestTask>("verifyCandidateManifest") {
    dependsOn(generateCandidateManifest)
    manifestFile.set(candidateManifest)
    candidateVersion.set(project.version.toString())
    releaseTag.set(candidateReleaseTag)
    candidateCommit.set(candidateCommitValue)
    swiftZip.set(stagedSwiftZip)
    swiftChecksum.set(stagedSwiftChecksum)
    swiftPmProof.set(stagedSwiftPmProof)
    centralBundle.set(centralBundleFile)
    centralInventory.set(centralBundleInventory)
    mavenInventory.set(mavenInventoryFile)
    kmpConsumer.set(cleanKmpConsumerResult)
    androidEvidence.set(stagedAndroidEvidence)
    desktopEvidence.from(desktopEvidenceFiles)
    privacyAudit.set(stagedPrivacyAudit); artifactMetrics.set(layout.projectDirectory.file("codex-agent-runtime-ios/build/reports/ios-release/artifact-metrics.json"))
    resourceReports.from(resourceEvidence)
    approvalsFile.set(publicationApprovals)
    privacyManifest.set(privacyManifestFile)
    privacyDataFlowReview.set(privacyDataFlowReviewFile)
    privacyReviews.set(privacyRequiredReasonReview)
    packageSwift.set(layout.projectDirectory.file("Package.swift"))
    desktopDistributionManifest.set(desktopDistributionManifestFile)
    desktopBundledLicense.set(desktopBundledLicenseFile)
    desktopBundledNotice.set(desktopBundledNoticeFile)
}

val protectedCandidatePhases = registerProtectedCandidatePhases(prepareProtectedCandidate)

gradle.projectsEvaluated {
    val ios = project(":codex-agent-runtime-ios").tasks
    val swiftPackageProof = ios.named<RecordSwiftPackageProofTask>("recordCodexAgentSwiftPackageProof") {
        proofFile.set(stagedSwiftPmProof)
    }
    stageSwiftZip.configure { dependsOn(swiftPackageProof) }
    stageSwiftChecksum.configure { dependsOn(swiftPackageProof) }
    stagePrivacyAudit.configure { dependsOn(ios.named("verifyIosPrivacyManifest")) }
    if (!providers.gradleProperty("codexAgent.privacyRequiredReasonReview").isPresent)
        generateCandidateManifest.configure { dependsOn(ios.named("generateIosPrivacyRequiredReasonReview")) }
    measureCandidateResources.configure { dependsOn(packageCentralBundle) }
    subprojects {
        tasks.withType<PublishToMavenRepository>().configureEach { mustRunAfter(protectedCandidatePhases.privacy) }
    }
}

tasks.register<VerifyCandidatePayloadTask>("verifyCandidatePayload") {
    githubOutputFile.set(providers.gradleProperty("codexAgent.githubOutputFile").map(layout.projectDirectory::file))
    group = "verification"
    description = "Verifies every transported candidate byte and repository policy binding."
    manifestFile.set(layout.file(providers.gradleProperty("codexAgent.candidateManifest").map(::file)))
    payloadDirectory.set(layout.dir(providers.gradleProperty("codexAgent.candidatePayload").map(::file)))
    expectedVersion.set(project.version.toString())
    expectedTag.set(candidateReleaseTag)
    expectedCommit.set(candidateCommitValue)
    approvalsFile.set(publicationApprovals)
    privacyManifest.set(privacyManifestFile)
    privacyDataFlowReview.set(privacyDataFlowReviewFile)
    privacyReviewTemplate.set(privacyRequiredReasonReviewTemplate)
    privacyReviews.set(privacyRequiredReasonReviewOverride)
    packageSwift.set(layout.projectDirectory.file("Package.swift"))
    desktopDistributionManifest.set(desktopDistributionManifestFile)
    desktopBundledLicense.set(desktopBundledLicenseFile)
    desktopBundledNotice.set(desktopBundledNoticeFile)
    outputFile.set(layout.buildDirectory.file("reports/release-candidate/payload-verification.json"))
}

tasks.register<VerifyPublicSwiftResolutionTask>("verifyPublicSwiftResolution") {
    group = "verification"
    description = "Verifies the public Swift asset bytes and clean SwiftPM resolution."
    assetUrl.set(
        "https://github.com/ciurlaro/codex-agent/releases/download/v${project.version}/" +
            "CodexAgent-${project.version}.xcframework.zip",
    )
    candidateManifest.set(layout.file(providers.gradleProperty("codexAgent.candidateManifest").map(::file)))
    consumerDirectory.set(layout.projectDirectory.dir("codex-agent-runtime-ios/apple/RemoteConsumer"))
    derivedDataDirectory.set(layout.buildDirectory.dir("public-swift-derived-data"))
    packagesDirectory.set(layout.buildDirectory.dir("public-swift-packages"))
    outputFile.set(layout.buildDirectory.file("reports/release-candidate/public-swift-resolution.json"))
}

registerCentralPortalTasks()
