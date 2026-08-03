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
