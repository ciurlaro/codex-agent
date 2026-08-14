import com.android.build.api.dsl.ApplicationExtension
import java.io.File as JavaFile

plugins {
    id("com.android.application")
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

val runtimeProject = project(":codex-agent-runtime-android")
val prepareRuntime = runtimeProject.tasks.named<PrepareCodexRuntimeTask>("prepareCodexRuntime")
val localAndroidSdkPath = providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText.map { contents ->
        contents.lineSequence().singleOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter('=').orEmpty()
    }.filter(String::isNotBlank)
val androidSdkPath = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(localAndroidSdkPath)
val firebaseMatrixFile = providers.gradleProperty("codexAgent.firebaseMatrixFile").map(::JavaFile)
val firebaseResultsDirectory = providers.gradleProperty("codexAgent.firebaseResultsDirectory").map(::JavaFile)
val localEvidenceDirectory = layout.buildDirectory.dir("reports/firebase-android-runtime-evidence")
val importedEvidenceDirectory = providers.gradleProperty("codexAgent.androidRuntimeEvidenceDirectory")
    .map(::JavaFile).orElse(localEvidenceDirectory.map { it.asFile })

tasks.register<RecordFirebaseAndroidRuntimeEvidenceTask>("recordFirebaseAndroidRuntimeEvidence") {
    group = "verification"
    description = "Records exact Firebase ARM64 instrumentation evidence for the Android runtime."
    dependsOn("assembleDebug", "assembleDebugAndroidTest", ":codex-agent-runtime-android:assembleRelease")
    candidateCommit.set(providers.gradleProperty("codexAgent.candidateCommit"))
    pinnedRuntimeSha256.set(prepareRuntime.flatMap { it.binarySha256 })
    applicationOutputMetadata.set(layout.buildDirectory.file("outputs/apk/debug/output-metadata.json"))
    testOutputMetadata.set(layout.buildDirectory.file("outputs/apk/androidTest/debug/output-metadata.json"))
    matrixFile.set(layout.file(firebaseMatrixFile))
    testResults.set(layout.dir(firebaseResultsDirectory))
    releaseAar.set(rootProject.layout.projectDirectory.file(
        "codex-agent-runtime-android/build/outputs/aar/codex-agent-runtime-android-release.aar",
    ))
    apkanalyzerExecutable.set(layout.file(androidSdkPath.map {
        JavaFile(it, "cmdline-tools/latest/bin/apkanalyzer")
    }))
    repositoryDirectory.set(rootProject.layout.projectDirectory)
    evidenceDirectory.set(localEvidenceDirectory)
}

tasks.register<VerifyFirebaseAndroidRuntimeEvidenceTask>("verifyFirebaseAndroidRuntimeEvidence") {
    group = "verification"
    description = "Verifies downloaded Firebase ARM64 Android runtime evidence."
    expectedCommit.set(providers.gradleProperty("codexAgent.candidateCommit"))
    pinnedRuntimeSha256.set(prepareRuntime.flatMap { it.binarySha256 })
    evidenceDirectory.set(layout.dir(importedEvidenceDirectory))
    apkanalyzerExecutable.set(layout.file(androidSdkPath.map {
        JavaFile(it, "cmdline-tools/latest/bin/apkanalyzer")
    }))
    verificationFile.set(layout.buildDirectory.file(
        "reports/firebase-android-runtime-verification.json",
    ))
}
