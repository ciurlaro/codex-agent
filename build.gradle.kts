import java.io.File
import java.security.MessageDigest

plugins {
    base
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
}

private fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
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
    val privacyManifest = layout.projectDirectory.file(
        "codex-agent-runtime-ios/apple/Sources/CodexAgentAuthentication/PrivacyInfo.xcprivacy",
    )
    val dataFlowInventory = layout.projectDirectory.file("release/ios-data-flow-0.2.0.json")
    inputs.files(approvals, privacyManifest, dataFlowInventory)
    doLast {
        val contents = approvals.asFile.readText()
        fun hash(key: String): String = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"([0-9a-f]{64})\"")
            .find(contents)
            ?.groupValues
            ?.get(1)
            ?: error("Missing approval scope hash: $key")
        check(hash("privacyManifestSha256") == privacyManifest.asFile.sha256()) {
            "Privacy approval does not cover the current PrivacyInfo.xcprivacy"
        }
        check(hash("dataFlowInventorySha256") == dataFlowInventory.asFile.sha256()) {
            "Privacy approval does not cover the current iOS data-flow inventory"
        }
        check(Regex(""""privacyCollectedDataReviewApproved"\s*:\s*true""").containsMatchIn(contents)) {
            "Apple collected-data declarations require product approval before publication"
        }
        check(Regex(""""staticFrameworkGplDistributionApproved"\s*:\s*true""").containsMatchIn(contents)) {
            "Static-framework GPL distribution requires an external product decision before publication"
        }
    }
}
