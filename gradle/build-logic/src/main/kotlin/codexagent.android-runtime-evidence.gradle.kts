import java.io.File as JavaFile

val runtimeProject = project(":codex-agent-runtime-android")
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
private val importedArtifacts = firebaseImportedArtifacts(
    providers.gradleProperty(FIREBASE_APPLICATION_APK_PROPERTY).orNull?.let(rootProject::file),
    providers.gradleProperty(FIREBASE_TEST_APK_PROPERTY).orNull?.let(rootProject::file),
    providers.gradleProperty(FIREBASE_RELEASE_AAR_PROPERTY).orNull?.let(rootProject::file),
)

tasks.register<RecordFirebaseAndroidRuntimeEvidenceTask>("recordFirebaseAndroidRuntimeEvidence") {
    group = "verification"
    description = "Records exact Firebase ARM64 instrumentation evidence for the Android runtime."
    candidateCommit.set(providers.gradleProperty("codexAgent.candidateCommit"))
    if (importedArtifacts == null) {
        dependsOn("assembleDebug", "assembleDebugAndroidTest", ":codex-agent-runtime-android:assembleRelease")
        pinnedRuntimeSha256.set(providers.gradleProperty(CodexAgentBuild.Properties.CODEX_BINARY_SHA256))
        applicationApk.set(layout.file(layout.buildDirectory.file("outputs/apk/debug/output-metadata.json").map {
            resolveSingleApk(it.asFile, "application")
        }))
        testApk.set(layout.file(layout.buildDirectory.file("outputs/apk/androidTest/debug/output-metadata.json").map {
            resolveSingleApk(it.asFile, "instrumentation test")
        }))
        releaseAar.set(rootProject.layout.projectDirectory.file(
            "codex-agent-runtime-android/build/outputs/aar/codex-agent-runtime-android-release.aar",
        ))
    } else {
        applicationApk.set(importedArtifacts.applicationApk)
        testApk.set(importedArtifacts.testApk)
        releaseAar.set(importedArtifacts.releaseAar)
    }
    matrixFile.set(layout.file(firebaseMatrixFile))
    testResults.set(layout.dir(firebaseResultsDirectory))
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
    pinnedRuntimeSha256.set(providers.gradleProperty(CodexAgentBuild.Properties.CODEX_BINARY_SHA256))
    evidenceDirectory.set(layout.dir(importedEvidenceDirectory))
    apkanalyzerExecutable.set(layout.file(androidSdkPath.map {
        JavaFile(it, "cmdline-tools/latest/bin/apkanalyzer")
    }))
    verificationFile.set(layout.buildDirectory.file(
        "reports/firebase-android-runtime-verification.json",
    ))
}
