import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

fun Project.registerProtectedRuntimeCandidates(
    candidateCommit: Provider<String>,
    candidateEvidence: Provider<Directory>,
    candidateReports: Provider<Directory>,
    centralStagingDirectory: Provider<Directory>,
    distributionManifest: RegularFile,
    prepareCandidate: TaskProvider<PrepareProtectedCandidateTask>,
    verifyCentralStaging: TaskProvider<VerifyMavenStagingTask>,
    generateManifest: TaskProvider<GenerateCandidateManifestTask>,
    verifyManifest: TaskProvider<VerifyProtectedCandidateManifestTask>,
) {
    val classifiers = objects.fileCollection().apply {
        desktopRuntimeEvidenceTargets.values.forEach { target ->
            from(centralStagingDirectory.map { repository -> repository.file(
                "${CodexAgentBuild.MAVEN_GROUP.replace('.', '/')}/codex-agent-runtime-desktop/$version/" +
                    "codex-agent-runtime-desktop-$version-${target.classifier}.zip",
            ) })
        }
    }
    val importedCiProvenance = layout.file(
        providers.gradleProperty("codexAgent.ciProvenance").map(::file),
    )
    val stagedCiProvenance = tasks.register<CopyCandidateFileTask>("stageProtectedCiProvenance") {
        dependsOn(prepareCandidate)
        sourceFile.set(importedCiProvenance)
        outputFile.set(candidateEvidence.map { it.file(CANDIDATE_CI_PROVENANCE_FILE) })
    }.flatMap(CopyCandidateFileTask::outputFile)
    generateManifest.configure { ciProvenance.set(stagedCiProvenance) }
    verifyManifest.configure { ciProvenance.set(stagedCiProvenance) }
    registerProtectedJvmRuntimeCandidate(
        candidateCommit, candidateEvidence, candidateReports, distributionManifest, classifiers,
        prepareCandidate, verifyCentralStaging, generateManifest, verifyManifest,
    )
    registerProtectedNodeRuntimeCandidate(
        candidateCommit, candidateEvidence, candidateReports, distributionManifest, classifiers,
        prepareCandidate, verifyCentralStaging, generateManifest, verifyManifest,
    )
    val importedAndroid = layout.dir(
        providers.gradleProperty(FIREBASE_ANDROID_EVIDENCE_DIRECTORY_PROPERTY).map(::file),
    )
    val importedAndroidFiles = objects.fileCollection().apply {
        protectedFirebaseAndroidRuntimeRawFiles.forEach { (_, fileName) ->
            from(importedAndroid.map { it.file(fileName) })
        }
    }
    prepareCandidate.configure { androidEvidence.from(importedAndroidFiles) }
    val android = registerProtectedFirebaseAndroidRuntimeEvidence(candidateEvidence)
    generateManifest.configure {
        desktopClassifierArchives.from(classifiers)
        androidEvidence.from(android.stagedFiles)
    }
    verifyManifest.configure {
        desktopClassifierArchives.from(classifiers)
        androidEvidence.from(android.stagedFiles)
    }
}
