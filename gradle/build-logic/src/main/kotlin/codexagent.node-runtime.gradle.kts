import java.io.File

val desktopManifest = rootProject.layout.projectDirectory.file(
    "codex-agent-runtime-desktop/codex-app-server-distributions.json",
)
tasks.register<GenerateNodeDistributionSourceTask>("generateNodeDistributionSource") {
    distributionManifest.set(desktopManifest)
    libraryVersion.set(project.version.toString())
    outputDirectory.set(layout.buildDirectory.dir("generated/distributions/kotlin"))
}

val nodeRuntimeEvidenceRunnerArchive = layout.file(
    providers.gradleProperty("codexAgent.nodeRuntimeEvidenceRunnerArchive").map(::File),
)
val nodeWasmRuntimeEvidenceRunnerArchive = layout.file(
    providers.gradleProperty("codexAgent.nodeWasmRuntimeEvidenceRunnerArchive").map(::File),
)
val nodeClassifierArchive = layout.file(
    providers.gradleProperty("codexAgent.nodeClassifierArchive").map(::File),
)
val nodeEvidenceRunners = listOf(
    Triple("nodeRuntime", "js", nodeRuntimeEvidenceRunnerArchive),
    Triple("nodeWasmRuntime", "wasm", nodeWasmRuntimeEvidenceRunnerArchive),
)
listOf("macosArm64", "macosX64", "linuxArm64", "linuxX64", "mingwX64").forEach { target ->
    nodeEvidenceRunners.forEach { (taskPrefix, backend, runnerArchive) ->
        tasks.register<RecordNodeRuntimeEvidenceTask>(
            "$taskPrefix${target.replaceFirstChar(Char::uppercase)}Test",
        ) {
            group = "verification"
            description = "Runs the exact $target Node $backend App Server lifecycle evidence."
            candidateCommit.set(providers.gradleProperty("codexAgent.candidateCommit"))
            this.target.set(target)
            runtimeBackend.set(backend)
            runnerOs.set(providers.environmentVariable("RUNNER_OS"))
            runnerArch.set(providers.environmentVariable("RUNNER_ARCH"))
            nodeExecutable.set(providers.gradleProperty("codexAgent.nodeExecutable").orElse("node"))
            distributionManifest.set(desktopManifest)
            classifierArchive.set(nodeClassifierArchive)
            compiledNodeTestRuntime.set(runnerArchive)
            evidenceFile.set(layout.buildDirectory.file(
                "reports/node-runtime-evidence/${nodeRuntimeEvidenceFileName(target, backend)}",
            ))
            testReport.set(layout.buildDirectory.file(
                "test-results/node-runtime-evidence/${nodeRuntimeTestReportFileName(target, backend)}",
            ))
        }
    }
}
