import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmExtension
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsRootPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.npm.WasmNpmExtension

plugins {
    base
    alias(libs.plugins.android.kmp.library) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.maven.publish) apply false
    id("codexagent.root-release")
}

rootProject.plugins.withType<NodeJsRootPlugin> {
    rootProject.extensions.getByType(NpmExtension::class.java).lockFileDirectory.set(
        rootProject.layout.projectDirectory.dir("gradle/kotlin-js-store"),
    )
}
rootProject.plugins.withType<WasmNodeJsRootPlugin> {
    rootProject.extensions.getByType(WasmNpmExtension::class.java).lockFileDirectory.set(
        rootProject.layout.projectDirectory.dir("gradle/kotlin-js-store/wasm"),
    )
}
