plugins {
    base
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
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

tasks.register("verifyPublicationReadiness") {
    group = "verification"
    description = "Requires external Apple privacy and static-framework GPL distribution approvals."
    val approvals = layout.projectDirectory.file("release/0.2.0-approvals.json")
    inputs.file(approvals)
    doLast {
        val contents = approvals.asFile.readText()
        check(Regex(""""privacyCollectedDataReviewApproved"\s*:\s*true""").containsMatchIn(contents)) {
            "Apple collected-data declarations require product approval before publication"
        }
        check(Regex(""""staticFrameworkGplDistributionApproved"\s*:\s*true""").containsMatchIn(contents)) {
            "Static-framework GPL distribution requires an external product decision before publication"
        }
    }
}
