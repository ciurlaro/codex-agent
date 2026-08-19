import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

fun Project.registerProtectedJvmRuntimeCandidate(
    candidateCommit: Provider<String>,
    candidateEvidence: Provider<Directory>,
    candidateReports: Provider<Directory>,
    distributionManifest: RegularFile,
    classifierArchives: FileCollection,
    prepareCandidate: TaskProvider<PrepareProtectedCandidateTask>,
    verifyCentralStaging: TaskProvider<VerifyMavenStagingTask>,
    generateManifest: TaskProvider<GenerateCandidateManifestTask>,
    verifyManifest: TaskProvider<VerifyProtectedCandidateManifestTask>,
) {
    val evidenceDirectory = providers.gradleProperty("codexAgent.jvmEvidenceDirectory")
    val evidenceFiles = objects.fileCollection().apply {
        desktopRuntimeEvidenceTargets.keys.forEach { target ->
            from(evidenceDirectory.map { directory -> file("$directory/${jvmRuntimeEvidenceFileName(target)}") })
        }
    }
    val runner = layout.file(providers.gradleProperty("codexAgent.portableRuntimeArtifactsDirectory").map {
        file("$it/$JVM_RUNTIME_RUNNER_ARCHIVE")
    }).orElse(layout.projectDirectory.file(
        "codex-agent-runtime-desktop/build/distributions/$JVM_RUNTIME_RUNNER_ARCHIVE",
    ))
    prepareCandidate.configure {
        jvmEvidence.from(evidenceFiles)
        jvmRuntimeRunner.set(runner)
    }
    val verifyEvidence = tasks.register<VerifyJvmRuntimeEvidenceTask>("verifyImportedJvmRuntimeEvidence") {
        dependsOn(verifyCentralStaging)
        expectedCommit.set(candidateCommit)
        this.evidenceFiles.from(evidenceFiles)
        this.distributionManifest.set(distributionManifest)
        this.classifierArchives.from(classifierArchives)
        compiledJvmTestRuntime.set(runner)
        verificationFile.set(candidateReports.map { it.file("jvm-runtime-evidence-verification.json") })
    }
    val stagedEvidence = objects.fileCollection()
    val copies = desktopRuntimeEvidenceTargets.keys.map { target ->
        tasks.register<CopyCandidateFileTask>(
            "stageProtectedJvmRuntime${target.replaceFirstChar(Char::uppercase)}Evidence",
        ) {
            dependsOn(verifyEvidence)
            sourceFile.set(layout.file(evidenceDirectory.map { directory ->
                file("$directory/${jvmRuntimeEvidenceFileName(target)}")
            }))
            outputFile.set(candidateEvidence.map { it.file(jvmRuntimeEvidenceFileName(target)) })
        }.also { stagedEvidence.from(it.flatMap(CopyCandidateFileTask::outputFile)) }
    }
    val stageRunner = tasks.register<CopyCandidateFileTask>("stageProtectedJvmRuntimeRunner") {
        dependsOn(verifyEvidence)
        sourceFile.set(runner)
        outputFile.set(candidateEvidence.map { it.file(JVM_RUNTIME_RUNNER_ARCHIVE) })
    }
    tasks.register("stageProtectedJvmRuntimeEvidence") { dependsOn(copies, stageRunner) }
    val stagedRunner = stageRunner.flatMap(CopyCandidateFileTask::outputFile)
    generateManifest.configure {
        jvmEvidence.from(stagedEvidence)
        jvmRuntimeRunner.set(stagedRunner)
    }
    verifyManifest.configure {
        jvmEvidence.from(stagedEvidence)
        jvmRuntimeRunner.set(stagedRunner)
    }
}
