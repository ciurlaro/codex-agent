@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec

plugins {
    kotlin("multiplatform") version "2.3.10"
    id("com.android.kotlin.multiplatform.library") version "9.2.1"
}

val codexAgentVersion = providers.gradleProperty("codexAgent.version").get()

kotlin {
    jvm()
    android {
        namespace = "io.github.ciurlaro.codexagent.stagedconsumer"
        compileSdk = 37
        minSdk = 26
    }
    val device = iosArm64()
    val simulator = iosSimulatorArm64()
    macosArm64()
    macosX64()
    linuxArm64()
    linuxX64()
    mingwX64()
    js { browser(); nodejs() }
    wasmJs { browser(); nodejs() }
    listOf(device, simulator).forEach { target ->
        target.binaries.framework { baseName = "StagedConsumer" }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.ciurlaro:codex-agent-client:$codexAgentVersion")
        }
        androidMain.dependencies {
            implementation("io.github.ciurlaro:codex-agent-runtime-android:$codexAgentVersion")
        }
        iosMain.dependencies {
            implementation("io.github.ciurlaro:codex-agent-runtime-ios:$codexAgentVersion")
        }
        macosMain {
            kotlin.srcDir("src/desktopMain/kotlin")
            dependencies { implementation("io.github.ciurlaro:codex-agent-runtime-desktop:$codexAgentVersion") }
        }
        linuxMain {
            kotlin.srcDir("src/desktopMain/kotlin")
            dependencies { implementation("io.github.ciurlaro:codex-agent-runtime-desktop:$codexAgentVersion") }
        }
        mingwX64Main {
            kotlin.srcDir("src/desktopMain/kotlin")
            dependencies { implementation("io.github.ciurlaro:codex-agent-runtime-desktop:$codexAgentVersion") }
        }
    }
}

extensions.configure<NodeJsEnvSpec> { download.set(false) }
extensions.configure<WasmNodeJsEnvSpec> { download.set(false) }
