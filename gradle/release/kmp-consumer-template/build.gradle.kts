@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec

plugins {
    kotlin("multiplatform") version "2.3.10"
    id("com.android.kotlin.multiplatform.library") version "9.2.1" apply false
}

val codexAgentVersion = providers.gradleProperty("codexAgent.version").get()
val consumerTarget = providers.gradleProperty("codexAgent.consumerTarget").get()
check(consumerTarget in setOf(
    "common", "android", "desktop", "ios-device", "ios-simulator", "node-js", "node-wasm",
)) { "Unsupported staged consumer target: $consumerTarget" }
if (consumerTarget == "android") pluginManager.apply("com.android.kotlin.multiplatform.library")

kotlin {
    when (consumerTarget) {
        "common" -> jvm()
        "android" -> (targets.getByName("android") as KotlinMultiplatformAndroidLibraryTarget).apply {
            namespace = "io.github.codex_agent_labs.codexagent.stagedconsumer"
            compileSdk = 37
            minSdk = 26
        }
        "desktop" -> {
            jvm()
            macosArm64()
            macosX64()
            linuxArm64()
            linuxX64()
            mingwX64()
        }
        "ios-device" -> iosArm64().binaries.framework { baseName = "StagedConsumer" }
        "ios-simulator" -> iosSimulatorArm64().binaries.framework { baseName = "StagedConsumer" }
        "node-js" -> js { nodejs() }
        "node-wasm" -> wasmJs { nodejs() }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.codex-agent-labs:codex-agent-client:$codexAgentVersion")
        }
        when (consumerTarget) {
            "common" -> Unit
            "android" -> androidMain.dependencies {
                implementation("io.github.codex-agent-labs:codex-agent-runtime-android:$codexAgentVersion")
            }
            "desktop" -> {
                jvmMain {
                    kotlin.srcDir("src/desktopMain/kotlin")
                    dependencies {
                        implementation("io.github.codex-agent-labs:codex-agent-runtime-desktop:$codexAgentVersion")
                    }
                }
                listOf(macosMain, linuxMain, mingwX64Main).forEach { sourceSet ->
                    sourceSet.configure {
                        kotlin.srcDir("src/desktopMain/kotlin")
                        dependencies {
                            implementation("io.github.codex-agent-labs:codex-agent-runtime-desktop:$codexAgentVersion")
                        }
                    }
                }
            }
            "ios-device", "ios-simulator" -> iosMain.dependencies {
                implementation("io.github.codex-agent-labs:codex-agent-runtime-ios:$codexAgentVersion")
            }
            "node-js" -> jsMain.dependencies {
                implementation("io.github.codex-agent-labs:codex-agent-runtime-node:$codexAgentVersion")
            }
            "node-wasm" -> wasmJsMain {
                kotlin.srcDir("src/jsMain/kotlin")
                dependencies {
                    implementation("io.github.codex-agent-labs:codex-agent-runtime-node:$codexAgentVersion")
                }
            }
        }
    }
}

if (consumerTarget == "node-js") extensions.configure<NodeJsEnvSpec> { download.set(false) }
if (consumerTarget == "node-wasm") extensions.configure<WasmNodeJsEnvSpec> { download.set(false) }
