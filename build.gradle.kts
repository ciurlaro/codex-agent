import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip

plugins {
    base
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
}

val centralStagingDirectory = layout.buildDirectory.dir("central-staging")
val cleanCentralStaging = tasks.register<Delete>("cleanCentralStaging") {
    delete(centralStagingDirectory)
}

subprojects {
    pluginManager.withPlugin("maven-publish") {
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "CENTRAL_STAGING"
                url = rootProject.layout.buildDirectory.dir("central-staging").get().asFile.toURI()
            }
        }
        tasks.withType<PublishToMavenRepository>().configureEach {
            mustRunAfter(cleanCentralStaging)
        }
    }
}

allprojects {
    group = "io.github.ciurlaro"
    version = "0.2.0"
}

tasks.register("verifyRepository") {
    group = "verification"
    description = "Runs the portable client, Android runtime, protocol, and build-logic checks."
    dependsOn(
        ":codex-agent-client:jvmTest",
        ":codex-agent-client:compileAndroidMain",
        ":codex-agent-client:verifyProtocolSource",
        ":codex-agent-runtime-android:testDebugUnitTest",
        ":codex-agent-runtime-android:lint",
        ":codex-agent-runtime-android:assembleRelease",
        ":tooling:protocol-generator:test",
    )
}

tasks.register("verifyIosRuntime") {
    group = "verification"
    description = "Runs the embedded iOS runtime, XCFramework, and Swift consumer gates on macOS."
    dependsOn(":codex-agent-runtime-ios:verifyIosRuntime")
}

tasks.register<VerifyReleaseMetadataTask>("verifyReleaseMetadata") {
    group = "verification"
    description = "Verifies that the release tag and public consumer metadata match the project version."
    projectVersion.set(project.version.toString())
    releaseTag.set(providers.gradleProperty("codexAgent.releaseTag"))
    swiftPackageManifest.set(layout.projectDirectory.file("Package.swift"))
    remoteConsumerManifest.set(
        layout.projectDirectory.file("codex-agent-runtime-ios/apple/RemoteConsumer/Package.swift"),
    )
}

tasks.register<VerifyPublicationReadinessTask>("verifyPublicationReadiness") {
    group = "verification"
    description = "Requires external Apple privacy and static-framework GPL distribution approvals."
    approvalsFile.set(layout.projectDirectory.file("release/0.2.0-approvals.json"))
    privacyManifest.set(
        layout.projectDirectory.file(
            "codex-agent-runtime-ios/apple/Sources/CodexAgentAuthentication/PrivacyInfo.xcprivacy",
        ),
    )
    privacyInventory.set(layout.projectDirectory.file("release/privacy-data-flow-inventory-0.2.0.json"))
}

val stageCentralRepository = tasks.register("stageCentralRepository") {
    group = "publishing"
    description = "Stages the exact complete KMP repository without uploading it."
    dependsOn(
        cleanCentralStaging,
        ":codex-agent-client:publishAllPublicationsToCENTRAL_STAGINGRepository",
        ":codex-agent-runtime-android:publishAllPublicationsToCENTRAL_STAGINGRepository",
        ":codex-agent-runtime-ios:publishAllPublicationsToCENTRAL_STAGINGRepository",
    )
}

val generateCentralChecksums = tasks.register<Exec>("generateCentralChecksums") {
    dependsOn(stageCentralRepository)
    outputs.dir(centralStagingDirectory)
    commandLine(
        "/bin/bash", "-c",
        """
            set -euo pipefail
            root="${centralStagingDirectory.get().asFile.absolutePath}"
            find "${'$'}root" -type f ! -name '*.md5' ! -name '*.sha1' ! -name '*.sha256' ! -name '*.sha512' -print0 |
              while IFS= read -r -d '' file; do
                md5 -q "${'$'}file" > "${'$'}file.md5"
                shasum -a 1 "${'$'}file" | awk '{print ${'$'}1}' > "${'$'}file.sha1"
              done
        """.trimIndent(),
    )
}

val mavenInventoryFile = layout.buildDirectory.file("reports/release-candidate/maven-inventory.json")
val verifyCentralStaging = tasks.register<VerifyMavenStagingTask>("verifyCentralStaging") {
    dependsOn(generateCentralChecksums)
    repositoryDirectory.set(centralStagingDirectory)
    groupId.set(project.group.toString())
    version.set(project.version.toString())
    expectedArtifactIds.set(
        listOf(
            "codex-agent-client",
            "codex-agent-client-android",
            "codex-agent-client-iosarm64",
            "codex-agent-client-iossimulatorarm64",
            "codex-agent-client-jvm",
            "codex-agent-runtime-android",
            "codex-agent-runtime-ios",
            "codex-agent-runtime-ios-iosarm64",
            "codex-agent-runtime-ios-iossimulatorarm64",
        ),
    )
    rootMetadataArtifactIds.set(listOf("codex-agent-client", "codex-agent-runtime-ios"))
    requireSignatures.set(
        providers.gradleProperty("codexAgent.requireCentralSignatures").map(String::toBoolean).orElse(false),
    )
    inventoryFile.set(mavenInventoryFile)
}

val cleanKmpConsumerDirectory = layout.buildDirectory.dir("clean-kmp-consumer")
val rootLocalProperties = layout.projectDirectory.file("local.properties")
val androidSdkDirectory = providers.environmentVariable("ANDROID_HOME").orElse(
    providers.fileContents(rootLocalProperties).asText.map { contents ->
        contents.lineSequence().single { it.startsWith("sdk.dir=") }.substringAfter('=')
    },
)
val prepareCleanKmpConsumer = tasks.register<Sync>("prepareCleanKmpConsumer") {
    into(cleanKmpConsumerDirectory)
    from(layout.projectDirectory.dir("release/kmp-consumer-template"))
}

val cleanKmpConsumerResult = layout.buildDirectory.file("reports/release-candidate/kmp-consumer.json")
val verifyStagedKmpConsumer = tasks.register<Exec>("verifyStagedKmpConsumer") {
    notCompatibleWithConfigurationCache("Kotlin/Native publication commonization uses project state at execution time")
    dependsOn(verifyCentralStaging, prepareCleanKmpConsumer)
    inputs.dir(centralStagingDirectory)
    inputs.dir(layout.projectDirectory.dir("release/kmp-consumer-template"))
    inputs.property("androidSdkDirectory", androidSdkDirectory)
    outputs.file(cleanKmpConsumerResult)
    commandLine(
        "/bin/bash", "-c",
        """
            set -euo pipefail
            printf 'sdk.dir=%s\n' '${androidSdkDirectory.get()}' > "${cleanKmpConsumerDirectory.get().asFile.absolutePath}/local.properties"
            "${layout.projectDirectory.file("gradlew").asFile.absolutePath}" \
              -p "${cleanKmpConsumerDirectory.get().asFile.absolutePath}" \
              --no-configuration-cache \
              -PCENTRAL_STAGING="${centralStagingDirectory.get().asFile.absolutePath}" \
              compileKotlinJvm compileAndroidMain \
              linkDebugFrameworkIosArm64 linkDebugFrameworkIosSimulatorArm64
            mkdir -p "${cleanKmpConsumerResult.get().asFile.parentFile.absolutePath}"
            printf '%s\n' '{"jvm":"passed","android":"passed","iosArm64":"passed","iosSimulatorArm64":"passed","codexAgentResolution":"CENTRAL_STAGING-only"}' > "${cleanKmpConsumerResult.get().asFile.absolutePath}"
        """.trimIndent(),
    )
}

val packageCentralBundle = tasks.register<Zip>("packageCentralBundle") {
    dependsOn(verifyStagedKmpConsumer)
    archiveFileName.set("codex-agent-${project.version}-central-bundle.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(centralStagingDirectory)
}

val centralBundleInventory = layout.buildDirectory.file("reports/release-candidate/central-bundle.json")
val generateCentralBundleInventory = tasks.register<GenerateBundleInventoryTask>("generateCentralBundleInventory") {
    dependsOn(packageCentralBundle)
    bundleFile.set(packageCentralBundle.flatMap { it.archiveFile })
    mavenInventory.set(mavenInventoryFile)
    maximumBytes.set(1_000_000_000L)
    outputFile.set(centralBundleInventory)
}

val candidateCommitValue = providers.gradleProperty("codexAgent.candidateCommit").orElse("UNCOMMITTED")
val androidEvidenceFile = providers.gradleProperty("codexAgent.androidEvidenceFile")
    .map { layout.projectDirectory.file(it) }
    .orElse(layout.projectDirectory.file("release/android-runtime-evidence-0.2.0.pending.json"))
val candidateManifest = layout.buildDirectory.file("reports/release-candidate/candidate-manifest.json")
val generateCandidateManifest = tasks.register<GenerateCandidateManifestTask>("generateCandidateManifest") {
    dependsOn(generateCentralBundleInventory, "verifyIosRuntime")
    candidateVersion.set(project.version.toString())
    candidateCommit.set(candidateCommitValue)
    swiftZip.set(
        layout.projectDirectory.file(
            "codex-agent-runtime-ios/build/distributions/CodexAgent-${project.version}.xcframework.zip",
        ),
    )
    swiftChecksum.set(
        layout.projectDirectory.file(
            "codex-agent-runtime-ios/build/distributions/CodexAgent-${project.version}.xcframework.zip.sha256",
        ),
    )
    centralBundle.set(packageCentralBundle.flatMap { it.archiveFile })
    centralInventory.set(centralBundleInventory)
    mavenInventory.set(mavenInventoryFile)
    approvalsFile.set(layout.projectDirectory.file("release/0.2.0-approvals.json"))
    privacyManifest.set(
        layout.projectDirectory.file(
            "codex-agent-runtime-ios/apple/Sources/CodexAgentAuthentication/PrivacyInfo.xcprivacy",
        ),
    )
    privacyInventory.set(layout.projectDirectory.file("release/privacy-data-flow-inventory-0.2.0.json"))
    privacyAudit.set(
        layout.projectDirectory.file("codex-agent-runtime-ios/build/reports/ios-release/privacy/audit.json"),
    )
    privacyReviews.set(layout.projectDirectory.file("release/privacy-required-reason-reviews-0.2.0.json"))
    androidEvidence.set(androidEvidenceFile)
    outputFile.set(candidateManifest)
}

val verifyAndroidRuntimeEvidence = tasks.register<VerifyAndroidRuntimeEvidenceTask>("verifyAndroidRuntimeEvidence") {
    expectedCommit.set(candidateCommitValue)
    evidenceFile.set(androidEvidenceFile)
}

tasks.register<VerifyCandidateManifestTask>("verifyProtectedCandidate") {
    dependsOn(generateCandidateManifest, verifyAndroidRuntimeEvidence, "verifyPublicationReadiness")
    manifestFile.set(candidateManifest)
}

registerCentralPortalTasks()
