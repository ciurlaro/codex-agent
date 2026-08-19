import com.android.build.api.dsl.ApplicationExtension

plugins {
    id("com.android.application")
    id("codexagent.android-runtime-evidence")
}

extensions.configure<ApplicationExtension> {
    namespace = "io.github.ciurlaro.codexagent.androidruntimeevidence"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.ciurlaro.codexagent.androidruntimeevidence"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging.jniLibs {
        keepDebugSymbols += "**/libcodex_app_server.so"
        useLegacyPackaging = true
    }
    testOptions.animationsDisabled = true
}

dependencies {
    implementation(project(":codex-agent-runtime-android"))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.sqlite.framework)
    androidTestImplementation(libs.kotlinx.coroutines.core)
}
