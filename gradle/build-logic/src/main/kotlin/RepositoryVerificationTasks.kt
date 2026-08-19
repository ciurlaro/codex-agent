import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

internal val repositoryVerificationTaskPaths = listOf(
    ":codex-agent-client:jvmTest",
    ":codex-agent-client:compileAndroidMain",
    ":codex-agent-client:compileKotlinJs",
    ":codex-agent-client:compileKotlinWasmJs",
    ":codex-agent-client:compileKotlinMacosArm64",
    ":codex-agent-client:compileKotlinMacosX64",
    ":codex-agent-client:compileKotlinLinuxArm64",
    ":codex-agent-client:compileKotlinLinuxX64",
    ":codex-agent-client:compileKotlinMingwX64",
    ":codex-agent-client:verifyProtocolSource",
    ":codex-agent-runtime-android:testDebugUnitTest",
    ":codex-agent-runtime-android:lintRelease",
    ":codex-agent-runtime-android:assembleRelease",
    ":codex-agent-runtime-desktop:jvmTest",
    ":codex-agent-runtime-desktop:macosArm64Test",
    ":codex-agent-runtime-desktop:compileKotlinMacosX64",
    ":codex-agent-runtime-desktop:compileKotlinLinuxArm64",
    ":codex-agent-runtime-desktop:compileKotlinLinuxX64",
    ":codex-agent-runtime-desktop:compileKotlinMingwX64",
    ":codex-agent-runtime-node:jsNodeTest",
    ":codex-agent-runtime-node:wasmJsNodeTest",
    ":tooling:protocol-generator:test",
)

fun Project.registerRepositoryVerificationTasks() {
    tasks.register("verifyRepository") {
        group = "verification"
        description = "Runs all client compilations, desktop/Android runtime checks, protocol, and build-logic checks."
        dependsOn(repositoryVerificationTaskPaths)
    }
    tasks.register("verifyIosRuntime") {
        group = "verification"
        description = "Runs the embedded iOS runtime, XCFramework, and Swift consumer gates on macOS."
        dependsOn(":codex-agent-runtime-ios:verifyIosRuntime")
    }
}
