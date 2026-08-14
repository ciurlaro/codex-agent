import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.Directory
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

private data class ProtectedNodeBackend(
    val evidence: ConfigurableFileCollection,
    val runner: Provider<RegularFile>,
)

fun Project.registerProtectedNodeRuntimeCandidate(
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
    fun registerBackend(backend: String): ProtectedNodeBackend {
        val wasm = backend == NODE_RUNTIME_WASM_BACKEND
        val label = if (wasm) "NodeWasmRuntime" else "NodeRuntime"
        val directoryProperty = if (wasm) {
            "codexAgent.nodeWasmEvidenceDirectory"
        } else {
            "codexAgent.nodeEvidenceDirectory"
        }
        val evidenceDirectory = providers.gradleProperty(directoryProperty)
        val importedEvidence = objects.fileCollection().apply {
            desktopRuntimeEvidenceTargets.keys.forEach { target ->
                from(evidenceDirectory.map { directory ->
                    file("$directory/${nodeRuntimeEvidenceFileName(target, backend)}")
                })
            }
        }
        val runnerName = nodeRuntimeRunnerArchiveName(backend)
        val importedRunner = layout.file(
            providers.gradleProperty("codexAgent.portableRuntimeArtifactsDirectory").map {
                file("$it/$runnerName")
            },
        ).orElse(layout.projectDirectory.file("codex-agent-runtime-node/build/distributions/$runnerName"))
        prepareCandidate.configure {
            if (wasm) {
                nodeWasmEvidence.from(importedEvidence)
                nodeWasmRuntimeRunner.set(importedRunner)
            } else {
                nodeEvidence.from(importedEvidence)
                nodeRuntimeRunner.set(importedRunner)
            }
        }
        val verifyEvidence = tasks.register<VerifyNodeRuntimeEvidenceTask>("verifyImported${label}Evidence") {
            dependsOn(verifyCentralStaging)
            expectedCommit.set(candidateCommit)
            runtimeBackend.set(backend)
            evidenceFiles.from(importedEvidence)
            this.distributionManifest.set(distributionManifest)
            this.classifierArchives.from(classifierArchives)
            compiledNodeTestRuntime.set(importedRunner)
            verificationFile.set(candidateReports.map {
                it.file("${if (wasm) "node-wasm" else "node"}-runtime-evidence-verification.json")
            })
        }
        val stagedEvidence = objects.fileCollection()
        val copies = desktopRuntimeEvidenceTargets.keys.map { target ->
            val taskName = "stageProtected$label${target.replaceFirstChar(Char::uppercase)}Evidence"
            tasks.register<CopyCandidateFileTask>(taskName) {
                dependsOn(verifyEvidence)
                sourceFile.set(layout.file(evidenceDirectory.map { directory ->
                    file("$directory/${nodeRuntimeEvidenceFileName(target, backend)}")
                }))
                outputFile.set(candidateEvidence.map { it.file(nodeRuntimeEvidenceFileName(target, backend)) })
            }.also { stagedEvidence.from(it.flatMap(CopyCandidateFileTask::outputFile)) }
        }
        val stageRunner = tasks.register<CopyCandidateFileTask>("stageProtected${label}Runner") {
            dependsOn(verifyEvidence)
            sourceFile.set(importedRunner)
            outputFile.set(candidateEvidence.map { it.file(nodeRuntimeRunnerArchiveName(backend)) })
        }
        tasks.register("stageProtected${label}Evidence") { dependsOn(copies, stageRunner) }
        return ProtectedNodeBackend(stagedEvidence, stageRunner.flatMap(CopyCandidateFileTask::outputFile))
    }

    val js = registerBackend(NODE_RUNTIME_JS_BACKEND)
    val wasm = registerBackend(NODE_RUNTIME_WASM_BACKEND)
    generateManifest.configure {
        nodeEvidence.from(js.evidence); nodeRuntimeRunner.set(js.runner)
        nodeWasmEvidence.from(wasm.evidence); nodeWasmRuntimeRunner.set(wasm.runner)
    }
    verifyManifest.configure {
        nodeEvidence.from(js.evidence); nodeRuntimeRunner.set(js.runner)
        nodeWasmEvidence.from(wasm.evidence); nodeWasmRuntimeRunner.set(wasm.runner)
    }
}
