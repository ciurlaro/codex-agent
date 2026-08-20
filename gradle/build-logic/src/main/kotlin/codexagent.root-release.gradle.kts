import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
val candidateCommitValue = providers.gradleProperty("codexAgent.candidateCommit")
val candidateReleaseTag = providers.gradleProperty("codexAgent.releaseTag")
val candidatePathCommit = candidateCommitValue.orElse("UNBOUND")
val candidateRoot = layout.buildDirectory.dir(candidatePathCommit.map { "protected-candidate/$it" })
val candidateArtifacts = candidateRoot.map { it.dir("artifacts") }
val candidateEvidence = candidateRoot.map { it.dir("evidence") }
val candidateReports = candidateRoot.map { it.dir("reports") }
private val reuseVerifiedApple = providers.gradleProperty(IOS_VERIFIED_DISTRIBUTION_PROPERTY).isPresent
val importedMavenRepository = providers.gradleProperty("codexAgent.mavenRepositoryDirectory")
val centralStagingDirectory = layout.dir(importedMavenRepository.map(::file))
    .orElse(candidateRoot.map { it.dir("maven-repository") })
val stagedConsumerTargets = listOf(
    "common", "android", "desktop", "ios-device", "ios-simulator", "node-js", "node-wasm",
)
val stagedConsumerRepositoryNames = stagedConsumerTargets.associateWith { target ->
    "CONSUMER_${target.replace('-', '_').uppercase()}_STAGING"
}
val stagedConsumerRepositories = stagedConsumerTargets.associateWith { target ->
    if (importedMavenRepository.isPresent) centralStagingDirectory else candidateRoot.map { root ->
        if (target == "common") root.dir("payload/maven") else root.dir("consumer-maven/$target")
    }
}
subprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "CENTRAL_STAGING"
                url = centralStagingDirectory.get().asFile.toURI()
            }
            if (!importedMavenRepository.isPresent) {
                stagedConsumerTargets.forEach { target ->
                    repositories.maven {
                        name = stagedConsumerRepositoryNames.getValue(target)
                        url = stagedConsumerRepositories.getValue(target).get().asFile.toURI()
                    }
                }
            }
        }
    }
}
allprojects {
    group = CodexAgentBuild.MAVEN_GROUP
    version = "0.2.0"
}
rootProject.extensions.extraProperties["codexAgent.repositoryUrl"] =
    "https://github.com/${CodexAgentBuild.REPOSITORY}"
registerRepositoryVerificationTasks()
tasks.register<VerifyReleaseMetadataTask>("verifyReleaseMetadata") {
    group = "verification"
    projectVersion.set(project.version.toString())
    releaseTag.set(providers.gradleProperty("codexAgent.releaseTag").orElse("v${project.version}"))
    swiftPackageManifest.set(layout.projectDirectory.file("Package.swift"))
    remoteConsumerManifest.set(layout.projectDirectory.file("codex-agent-runtime-ios/apple/RemoteConsumer/Package.swift"))
}
val publicationApprovals = layout.projectDirectory.file("gradle/release/publication-approvals.json")
val privacyManifestFile = layout.projectDirectory.file(
    "codex-agent-runtime-ios/apple/Sources/CodexAgentAuthentication/PrivacyInfo.xcprivacy",
)
val privacyDataFlowReviewFile = layout.projectDirectory.file("gradle/release/privacy-data-flow-review.json")
val iosResourcePolicyFile = layout.projectDirectory.file("gradle/release/ios-resource-policy.json")
val privacyRequiredReasonReviewTemplate = layout.projectDirectory.file("gradle/release/privacy-required-reason-review.json")
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
val promotedArtifactsInput = layout.dir(
    providers.gradleProperty("codexAgent.promotedArtifactsDirectory").map(::file),
)
val signedMavenInput = layout.dir(
    providers.gradleProperty("codexAgent.signedMavenRepository").map(::file),
)
tasks.register<StagePromotedMavenPrimariesTask>("stagePromotedMavenPrimaries") {
    group = "publishing"
    description = "Forwards each canonical promoted Maven primary without rebuilding it."
    promotedArtifactsDirectory.set(promotedArtifactsInput)
    candidateCommit.set(candidateCommitValue)
    candidateVersion.set(project.version.toString())
    mavenRepository.set(signedMavenInput)
}
tasks.register<AssemblePromotedCandidateTask>("assemblePromotedCandidate") {
    group = "publishing"
    description = "Signs and inventories exact promoted bytes without compiling or testing production sources."
    promotedArtifactsDirectory.set(promotedArtifactsInput)
    signedMavenRepository.set(signedMavenInput)
    candidateVersion.set(project.version.toString())
    releaseTag.set(candidateReleaseTag)
    candidateCommit.set(candidateCommitValue)
    candidateTree.set(providers.gradleProperty("codexAgent.candidateTree"))
    promotionRunId.set(providers.gradleProperty("codexAgent.promotionRunId").map(String::toLong))
    promotionRunAttempt.set(providers.gradleProperty("codexAgent.promotionRunAttempt").map(String::toInt))
    approvalsFile.set(publicationApprovals)
    privacyManifest.set(privacyManifestFile)
    privacyDataFlowReview.set(privacyDataFlowReviewFile)
    privacyReviewTemplate.set(privacyRequiredReasonReviewTemplate)
    iosResourcePolicy.set(iosResourcePolicyFile)
    packageSwift.set(layout.projectDirectory.file("Package.swift"))
    remoteConsumerManifest.set(layout.projectDirectory.file("codex-agent-runtime-ios/apple/RemoteConsumer/Package.swift"))
    desktopDistributionManifest.set(desktopDistributionManifestFile)
    desktopBundledLicense.set(desktopBundledLicenseFile)
    desktopBundledNotice.set(desktopBundledNoticeFile)
    releaseTooling.set(layout.file(providers.gradleProperty("codexAgent.releaseTooling").map(::file)))
    repositoryDirectory.set(layout.projectDirectory)
    payloadDirectory.set(candidateRoot.map { it.dir("payload") })
}
tasks.register<VerifyPublicationReadinessTask>("verifyPublicationReadiness") {
    group = "verification"
    approvalsFile.set(publicationApprovals)
    privacyManifest.set(privacyManifestFile)
    privacyInventory.set(privacyDataFlowReviewFile)
    desktopDistributionManifest.set(desktopDistributionManifestFile)
    desktopBundledLicense.set(desktopBundledLicenseFile)
    desktopBundledNotice.set(desktopBundledNoticeFile)
}
val desktopEvidenceDirectory = providers.gradleProperty("codexAgent.desktopEvidenceDirectory")
val iosNativeEvidenceDirectoryPath = providers.gradleProperty("codexAgent.iosNativeEvidenceDirectory")
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
    desktopEvidence.from(desktopEvidenceFiles)
    iosNativeEvidenceDirectory.set(layout.dir(iosNativeEvidenceDirectoryPath.map(::file)))
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
        ":codex-agent-runtime-node:publishAllPublicationsToCENTRAL_STAGINGRepository",
    )
}
val generateRelocationPoms = tasks.register<GenerateMavenRelocationPomsTask>("generateMavenRelocationPoms") {
    outputDirectory.set(centralStagingDirectory.map { it.dir(OLD_MAVEN_GROUP.replace('.', '/')) })
    newGroup.set(CodexAgentBuild.MAVEN_GROUP)
    version.set(project.version.toString())
    mustRunAfter(stageCentralRepository)
}
val generateConsumerCommonRelocationPoms = tasks.register<GenerateMavenRelocationPomsTask>(
    "generateConsumerCommonMavenRelocationPoms",
) {
    outputDirectory.set(stagedConsumerRepositories.getValue("common").map {
        it.dir(OLD_MAVEN_GROUP.replace('.', '/'))
    })
    newGroup.set(CodexAgentBuild.MAVEN_GROUP)
    version.set(project.version.toString())
}

val mavenInventoryFile = candidateReports.map { it.file("maven-inventory.json") }
val verifyCentralStaging = tasks.register<VerifyMavenStagingTask>("verifyCentralStaging") {
    group = "verification"
    description = "Verifies the exact staged KMP repository, relocations, and required sidecars."
    if (!importedMavenRepository.isPresent) {
        dependsOn(stageCentralRepository, generateRelocationPoms)
    }
    repositoryDirectory.set(centralStagingDirectory)
    groupId.set(project.group.toString())
    version.set(project.version.toString())
    requireSignatures.set(
        providers.gradleProperty("signingInMemoryKey").isPresent ||
            providers.gradleProperty("signing.secretKeyRingFile").isPresent,
    )
    inventoryFile.set(mavenInventoryFile)
}
val rootLocalProperties = layout.projectDirectory.file("local.properties")
val rootAndroidSdkDirectory = providers.environmentVariable("ANDROID_HOME").orElse(
    providers.fileContents(rootLocalProperties).asText.map { contents ->
        contents.lineSequence().single { it.startsWith("sdk.dir=") }.substringAfter('=')
    },
)
fun publicationTask(module: String, publication: String, target: String) =
    ":$module:publish${publication}PublicationTo${stagedConsumerRepositoryNames.getValue(target)}Repository"
val stagedConsumerPublicationTasks = mapOf(
    "common" to listOf(
        publicationTask("codex-agent-client", "KotlinMultiplatform", "common"),
        publicationTask("codex-agent-client", "Jvm", "common"),
    ),
    "android" to listOf(
        publicationTask("codex-agent-client", "KotlinMultiplatform", "android"),
        publicationTask("codex-agent-client", "Android", "android"),
        publicationTask("codex-agent-runtime-android", "Maven", "android"),
    ),
    "desktop" to listOf("KotlinMultiplatform", "Jvm", "MacosArm64", "MacosX64", "LinuxArm64", "LinuxX64", "MingwX64")
        .flatMap { publication -> listOf(
            publicationTask("codex-agent-client", publication, "desktop"),
            publicationTask("codex-agent-runtime-desktop", publication, "desktop"),
        ) },
    "ios-device" to listOf(
        publicationTask("codex-agent-client", "KotlinMultiplatform", "ios-device"),
        publicationTask("codex-agent-client", "IosArm64", "ios-device"),
        publicationTask("codex-agent-runtime-ios", "KotlinMultiplatform", "ios-device"),
        publicationTask("codex-agent-runtime-ios", "IosArm64", "ios-device"),
    ),
    "ios-simulator" to listOf(
        publicationTask("codex-agent-client", "KotlinMultiplatform", "ios-simulator"),
        publicationTask("codex-agent-client", "IosSimulatorArm64", "ios-simulator"),
        publicationTask("codex-agent-runtime-ios", "KotlinMultiplatform", "ios-simulator"),
        publicationTask("codex-agent-runtime-ios", "IosSimulatorArm64", "ios-simulator"),
    ),
    "node-js" to listOf(
        publicationTask("codex-agent-client", "KotlinMultiplatform", "node-js"),
        publicationTask("codex-agent-client", "Js", "node-js"),
        publicationTask("codex-agent-runtime-node", "KotlinMultiplatform", "node-js"),
        publicationTask("codex-agent-runtime-node", "Js", "node-js"),
    ),
    "node-wasm" to listOf(
        publicationTask("codex-agent-client", "KotlinMultiplatform", "node-wasm"),
        publicationTask("codex-agent-client", "WasmJs", "node-wasm"),
        publicationTask("codex-agent-runtime-node", "KotlinMultiplatform", "node-wasm"),
        publicationTask("codex-agent-runtime-node", "WasmJs", "node-wasm"),
    ),
)
val stagedConsumerTasks = linkedMapOf(
    "common" to "verifyStagedKmpConsumerCommon",
    "android" to "verifyStagedKmpConsumerAndroid",
    "desktop" to "verifyStagedKmpConsumerDesktop",
    "ios-device" to "verifyStagedKmpConsumerIosDevice",
    "ios-simulator" to "verifyStagedKmpConsumerIosSimulator",
    "node-js" to "verifyStagedKmpConsumerNodeJs",
    "node-wasm" to "verifyStagedKmpConsumerNodeWasm",
).mapValues { (target, taskName) ->
    val repository = stagedConsumerRepositories.getValue(target)
    val targetInventory = candidateReports.map { it.file("maven-inventory-$target.json") }
    val inventoryTask = tasks.register("inventory${target.split('-').joinToString("") {
        it.replaceFirstChar(Char::uppercase)
    }}ConsumerMavenRepository") {
        if (!importedMavenRepository.isPresent) {
            dependsOn(stagedConsumerPublicationTasks.getValue(target))
            if (target == "common") dependsOn(generateConsumerCommonRelocationPoms)
        }
        inputs.dir(repository)
        outputs.file(targetInventory)
        doLast {
            val root = repository.get().asFile
            check(root.isDirectory) { "Staged $target Maven repository is missing" }
            val files = root.walkTopDown().filter(File::isFile).sortedBy {
                it.relativeTo(root).invariantSeparatorsPath
            }.toList()
            check(files.isNotEmpty()) { "Staged $target Maven repository is empty" }
            targetInventory.get().asFile.atomicWriteJson(buildJsonObject {
                put("schemaVersion", JsonPrimitive(1))
                put("groupId", JsonPrimitive(project.group.toString()))
                put("version", JsonPrimitive(project.version.toString()))
                put("target", JsonPrimitive(target))
                put("files", buildJsonArray {
                    files.forEach { file -> add(file.releaseRecord(file.relativeTo(root).invariantSeparatorsPath)) }
                })
            })
        }
    }
    tasks.register<VerifyStagedKmpConsumerTask>(taskName) {
        group = "verification"
        description = "Builds the isolated $target consumer from CENTRAL_STAGING only."
        dependsOn(inventoryTask)
        repositoryDirectory.set(repository)
        templateDirectory.set(layout.projectDirectory.dir("gradle/release/kmp-consumer-template"))
        mavenInventory.set(targetInventory)
        gradleWrapper.set(layout.projectDirectory.file("gradlew"))
        projectVersion.set(project.version.toString())
        androidSdkDirectory.set(rootAndroidSdkDirectory)
        targetName.set(target)
        buildTasks.set(stagedConsumerBuildTasks.getValue(target))
        consumerDirectory.set(candidateRoot.map { it.dir("clean-consumer-$target") })
        resultFile.set(candidateReports.map { it.file("kmp-consumer-$target.json") })
    }
}
val cleanKmpConsumerResult = candidateReports.map { it.file("clean-kmp-consumer.json") }
val verifyStagedKmpConsumer = tasks.register<AggregateStagedKmpConsumerTask>("verifyStagedKmpConsumer") {
    group = "verification"
    description = "Verifies every target-specific staged KMP consumer result."
    dependsOn(verifyCentralStaging, stagedConsumerTasks.values)
    targetResults.from(stagedConsumerTasks.values.map { it.flatMap(VerifyStagedKmpConsumerTask::resultFile) })
    projectVersion.set(project.version.toString())
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
val swiftArchiveName = "CodexAgent-${project.version}.xcframework.zip"
val stagedSwiftZip = candidateArtifacts.map { it.file(swiftArchiveName) }
val stagedSwiftChecksum = candidateArtifacts.map { it.file("$swiftArchiveName.sha256") }
val stagedSwiftPmProof = candidateEvidence.map { it.file("swiftpm-proof.json") }
val stagedIosNativeEvidence = candidateEvidence.map { it.file("ios-native-evidence.json") }
val stageIosNativeEvidence = tasks.register<CopyCandidateFileTask>("stageProtectedIosNativeEvidence") {
    dependsOn(":codex-agent-runtime-ios:validateImportedCodexAgentIosNativeEvidence")
    sourceFile.set(layout.projectDirectory.file(
        "codex-agent-runtime-ios/build/imported-rust/ios-native-evidence.json",
    ))
    outputFile.set(stagedIosNativeEvidence)
}
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
    desktopEvidence.from(desktopEvidenceFiles)
    iosNativeEvidence.set(stagedIosNativeEvidence)
    privacyAudit.set(stagedPrivacyAudit); artifactMetrics.set(layout.projectDirectory.file("codex-agent-runtime-ios/build/reports/ios-release/artifact-metrics.json"))
    iosRuntimeMetrics.set(runtimeMetrics)
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
    desktopEvidence.from(desktopEvidenceFiles)
    iosNativeEvidence.set(stagedIosNativeEvidence)
    privacyAudit.set(stagedPrivacyAudit); artifactMetrics.set(layout.projectDirectory.file("codex-agent-runtime-ios/build/reports/ios-release/artifact-metrics.json"))
    iosRuntimeMetrics.set(runtimeMetrics)
    approvalsFile.set(publicationApprovals)
    privacyManifest.set(privacyManifestFile)
    privacyDataFlowReview.set(privacyDataFlowReviewFile)
    privacyReviews.set(privacyRequiredReasonReview)
    packageSwift.set(layout.projectDirectory.file("Package.swift"))
    desktopDistributionManifest.set(desktopDistributionManifestFile)
    desktopBundledLicense.set(desktopBundledLicenseFile)
    desktopBundledNotice.set(desktopBundledNoticeFile)
}
registerProtectedRuntimeCandidates(
    candidateCommit = candidateCommitValue,
    candidateEvidence = candidateEvidence,
    candidateReports = candidateReports,
    centralStagingDirectory = centralStagingDirectory,
    distributionManifest = desktopDistributionManifestFile,
    prepareCandidate = prepareProtectedCandidate,
    verifyCentralStaging = verifyCentralStaging,
    generateManifest = generateCandidateManifest,
    verifyManifest = verifyCandidateManifest,
)

val protectedCandidatePhases = registerProtectedCandidatePhases(prepareProtectedCandidate)

gradle.projectsEvaluated {
    val ios = project(":codex-agent-runtime-ios").tasks
    val swiftPackageProof = ios.named<RecordSwiftPackageProofTask>("recordCodexAgentSwiftPackageProof") {
        proofFile.set(stagedSwiftPmProof)
    }
    stageSwiftZip.configure { dependsOn(swiftPackageProof) }
    stageSwiftChecksum.configure { dependsOn(swiftPackageProof) }
    stagePrivacyAudit.configure {
        dependsOn(ios.named(
            if (reuseVerifiedApple) "validateImportedCodexAgentIosVerifiedDistribution"
            else "verifyIosPrivacyManifest",
        ))
    }
    if (!reuseVerifiedApple && !providers.gradleProperty("codexAgent.privacyRequiredReasonReview").isPresent)
        generateCandidateManifest.configure { dependsOn(ios.named("generateIosPrivacyRequiredReasonReview")) }
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
    iosResourcePolicy.set(iosResourcePolicyFile)
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
        "https://github.com/${CodexAgentBuild.REPOSITORY}/releases/download/v${project.version}/" +
            "CodexAgent-${project.version}.xcframework.zip",
    )
    candidateManifest.set(layout.file(providers.gradleProperty("codexAgent.candidateManifest").map(::file)))
    consumerDirectory.set(layout.projectDirectory.dir("codex-agent-runtime-ios/apple/RemoteConsumer"))
    derivedDataDirectory.set(layout.buildDirectory.dir("public-swift-derived-data"))
    packagesDirectory.set(layout.buildDirectory.dir("public-swift-packages"))
    outputFile.set(layout.buildDirectory.file("reports/release-candidate/public-swift-resolution.json"))
}

registerCentralPortalTasks()
