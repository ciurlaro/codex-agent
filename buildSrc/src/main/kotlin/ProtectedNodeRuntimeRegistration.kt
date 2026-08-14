import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

fun Project.registerProtectedNodeRuntimeCandidate(
    candidateCommit: Provider<String>,
    candidateRoot: Provider<Directory>,
    candidateEvidence: Provider<Directory>,
    candidateReports: Provider<Directory>,
    centralStagingDirectory: Provider<Directory>,
    distributionManifest: RegularFile,
    prepareCandidate: TaskProvider<PrepareProtectedCandidateTask>,
    verifyCentralStaging: TaskProvider<VerifyMavenStagingTask>,
    generateManifest: TaskProvider<GenerateCandidateManifestTask>,
    verifyManifest: TaskProvider<VerifyProtectedCandidateManifestTask>,
) {
    val evidenceDirectory = providers.gradleProperty("codexAgent.nodeEvidenceDirectory")
    val supervisorPackage = layout.file(
        providers.gradleProperty("codexAgent.windowsNodeSupervisorPackage").map(::file),
    )
    val supervisorIdentity = layout.file(
        providers.gradleProperty("codexAgent.windowsNodeSupervisorIdentityFile").map(::file),
    )
    val supervisorSource = layout.projectDirectory.dir("codex-agent-runtime-node/src/windowsSupervisor")
    val evidenceFiles = objects.fileCollection().apply {
        desktopRuntimeEvidenceTargets.keys.forEach { target ->
            from(evidenceDirectory.map { directory -> file("$directory/${nodeRuntimeEvidenceFileName(target)}") })
        }
    }

    prepareCandidate.configure {
        nodeEvidence.from(evidenceFiles)
        windowsSupervisorPackage.set(supervisorPackage)
        windowsSupervisorIdentity.set(supervisorIdentity)
        windowsSupervisorSource.set(supervisorSource)
    }

    val extractedSupervisor = candidateReports.map { it.file(WINDOWS_SUPERVISOR_FILE_NAME) }
    val extractSupervisor = tasks.register<ExtractCandidateWindowsSupervisorTask>(
        "extractProtectedWindowsNodeSupervisor",
    ) {
        dependsOn(prepareCandidate)
        packageFile.set(supervisorPackage)
        identityFile.set(supervisorIdentity)
        sourceDirectory.set(supervisorSource)
        candidateDirectory.set(candidateRoot)
        outputFile.set(extractedSupervisor)
    }
    val runner = layout.projectDirectory.file(
        "codex-agent-runtime-node/build/distributions/codex-agent-node-runtime-evidence-runner.zip",
    )
    val classifierArchives = objects.fileCollection().apply {
        desktopRuntimeEvidenceTargets.values.forEach { target ->
            from(centralStagingDirectory.map { repository -> repository.file(
                "io/github/ciurlaro/codex-agent-runtime-desktop/$version/" +
                    "codex-agent-runtime-desktop-$version-${target.classifier}.zip",
            ) })
        }
    }
    val verifyEvidence = tasks.register<VerifyNodeRuntimeEvidenceTask>("verifyImportedNodeRuntimeEvidence") {
        dependsOn(verifyCentralStaging, extractSupervisor)
        expectedCommit.set(candidateCommit)
        this.evidenceFiles.from(evidenceFiles)
        this.distributionManifest.set(distributionManifest)
        this.classifierArchives.from(classifierArchives)
        compiledNodeTestRuntime.set(runner)
        windowsSupervisor.set(extractedSupervisor)
        verificationFile.set(candidateReports.map { it.file("node-runtime-evidence-verification.json") })
    }

    val stagedSupervisorIdentity = candidateEvidence.map { it.file(WINDOWS_SUPERVISOR_IDENTITY_FILE_NAME) }
    tasks.register<CopyCandidateFileTask>("stageProtectedWindowsSupervisorIdentity") {
        dependsOn(extractSupervisor)
        sourceFile.set(supervisorIdentity)
        outputFile.set(stagedSupervisorIdentity)
    }
    val stagedEvidenceFiles = objects.fileCollection()
    val stagedRunner = candidateEvidence.map { it.file("codex-agent-node-runtime-evidence-runner.zip") }
    val stageRunner = tasks.register<CopyCandidateFileTask>("stageProtectedNodeRuntimeRunner") {
        dependsOn(verifyEvidence)
        sourceFile.set(runner)
        outputFile.set(stagedRunner)
    }
    val stageEvidence = desktopRuntimeEvidenceTargets.keys.map { target ->
        val staged = candidateEvidence.map { it.file(nodeRuntimeEvidenceFileName(target)) }
        tasks.register<CopyCandidateFileTask>(
            "stageProtectedNodeRuntime${target.replaceFirstChar(Char::uppercase)}Evidence",
        ) {
            dependsOn(verifyEvidence)
            sourceFile.set(layout.file(evidenceDirectory.map { directory ->
                file("$directory/${nodeRuntimeEvidenceFileName(target)}")
            }))
            outputFile.set(staged)
        }.also { stagedEvidenceFiles.from(it.flatMap(CopyCandidateFileTask::outputFile)) }
    }
    tasks.register("stageProtectedNodeRuntimeEvidence") {
        dependsOn(stageEvidence, stageRunner)
    }

    generateManifest.configure {
        nodeEvidence.from(stagedEvidenceFiles)
        nodeClassifierArchives.from(classifierArchives)
        nodeRuntimeRunner.set(stagedRunner)
        windowsSupervisorPackage.set(supervisorPackage)
        windowsSupervisorIdentity.set(stagedSupervisorIdentity)
        windowsSupervisorExecutable.set(extractedSupervisor)
        windowsSupervisorSource.set(supervisorSource)
    }
    verifyManifest.configure {
        nodeEvidence.from(stagedEvidenceFiles)
        nodeClassifierArchives.from(classifierArchives)
        nodeRuntimeRunner.set(stagedRunner)
        windowsSupervisorPackage.set(supervisorPackage)
        windowsSupervisorIdentity.set(stagedSupervisorIdentity)
        windowsSupervisorExecutable.set(extractedSupervisor)
        windowsSupervisorSource.set(supervisorSource)
    }
}
