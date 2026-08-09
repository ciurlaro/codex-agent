plugins {
    kotlin("multiplatform") version "2.3.10"
    id("com.android.kotlin.multiplatform.library") version "9.2.1"
}

kotlin {
    jvm()
    android {
        namespace = "io.github.ciurlaro.codexagent.stagedconsumer"
        compileSdk = 37
        minSdk = 26
    }
    val device = iosArm64()
    val simulator = iosSimulatorArm64()
    listOf(device, simulator).forEach { target ->
        target.binaries.framework { baseName = "StagedConsumer" }
    }

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.ciurlaro:codex-agent-client:0.2.0")
        }
        androidMain.dependencies {
            implementation("io.github.ciurlaro:codex-agent-runtime-android:0.2.0")
        }
        iosMain.dependencies {
            implementation("io.github.ciurlaro:codex-agent-runtime-ios:0.2.0")
        }
    }
}
