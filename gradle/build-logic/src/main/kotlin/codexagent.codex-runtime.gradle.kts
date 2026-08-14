import com.android.build.api.variant.LibraryAndroidComponentsExtension

val prepareRuntime = tasks.register<PrepareCodexRuntimeTask>("prepareCodexRuntime") {
    codexVersion.set(providers.gradleProperty(CodexAgentBuild.Properties.CODEX_VERSION))
    archiveSha256.set(providers.gradleProperty(CodexAgentBuild.Properties.CODEX_ARCHIVE_SHA256))
    binarySha256.set(providers.gradleProperty(CodexAgentBuild.Properties.CODEX_BINARY_SHA256))
    localArchive.set(
        providers.gradleProperty(CodexAgentBuild.Properties.CODEX_ARCHIVE_FILE)
            .map { layout.projectDirectory.file(it) },
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/codex-runtime/main"))
}

val importedReleaseAar = layout.file(
    providers.gradleProperty(IMPORTED_ANDROID_RELEASE_AAR_PROPERTY).map(rootProject::file),
)
if (importedReleaseAar.isPresent) {
    val candidateCommit = providers.gradleProperty("codexAgent.candidateCommit")
    val evidenceDirectory = providers.gradleProperty(FIREBASE_ANDROID_EVIDENCE_DIRECTORY_PROPERTY)
    check(candidateCommit.isPresent && evidenceDirectory.isPresent) {
        "$IMPORTED_ANDROID_RELEASE_AAR_PROPERTY requires candidate commit and Android evidence properties"
    }
    val verifyImportedReleaseAar = tasks.register<VerifyImportedAndroidReleaseAarTask>(
        "verifyImportedAndroidReleaseAar",
    ) {
        releaseAar.set(importedReleaseAar)
        firebaseEvidence.set(rootProject.layout.file(evidenceDirectory.map { directory ->
            rootProject.file(directory).resolve(FIREBASE_ANDROID_EVIDENCE_FILE)
        }))
        this.candidateCommit.set(candidateCommit)
        pinnedRuntimeSha256.set(providers.gradleProperty(CodexAgentBuild.Properties.CODEX_BINARY_SHA256))
        verificationFile.set(layout.buildDirectory.file("reports/imported-android-release-aar.json"))
    }
    pluginManager.withPlugin("com.android.library") {
        afterEvaluate {
            replaceAndroidReleaseComponentAar(importedReleaseAar, verifyImportedReleaseAar)
        }
    }
}

pluginManager.withPlugin("com.android.library") {
    extensions.configure<LibraryAndroidComponentsExtension> {
        onVariants { variant ->
            variant.sources.jniLibs?.addGeneratedSourceDirectory(
                prepareRuntime,
                PrepareCodexRuntimeTask::outputDirectory,
            )
        }
    }
}
