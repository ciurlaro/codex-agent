import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.register

fun Project.registerJvmRuntimeEvidenceTask(
    distribution: DesktopCodexDistributionSpec,
    packagedClassifier: TaskProvider<PackageDesktopCodexRuntimeTask>,
    validateTarget: TaskProvider<Task>,
    packagedRunner: TaskProvider<Zip>,
    distributionManifest: RegularFile,
) {
    if (distribution.target == "linuxArm64") return
    val externalClassifier = providers.gradleProperty("codexAgent.jvmClassifierArchive").map(::file)
    val externalRunner = providers.gradleProperty("codexAgent.jvmRuntimeEvidenceRunner").map(::file)
    val classifier = layout.file(externalClassifier).orElse(packagedClassifier.flatMap { it.outputFile })
    val runner = layout.file(externalRunner).orElse(packagedRunner.flatMap { it.archiveFile })
    tasks.register<RecordJvmRuntimeEvidenceTask>(
        "recordJvmRuntime${distribution.target.replaceFirstChar(Char::uppercase)}Evidence",
    ) {
        group = "verification"
        description = "Runs and records JVM lifecycle evidence for ${distribution.target}."
        dependsOn(validateTarget)
        if (!providers.gradleProperty("codexAgent.jvmClassifierArchive").isPresent) {
            dependsOn(packagedClassifier)
        }
        if (!providers.gradleProperty("codexAgent.jvmRuntimeEvidenceRunner").isPresent) {
            dependsOn(packagedRunner)
        }
        candidateCommit.set(providers.gradleProperty("codexAgent.candidateCommit"))
        target.set(distribution.target)
        runnerOs.set(providers.environmentVariable("RUNNER_OS"))
        runnerArch.set(providers.environmentVariable("RUNNER_ARCH"))
        javaExecutable.set(providers.gradleProperty("codexAgent.javaExecutable").orElse("java"))
        this.distributionManifest.set(distributionManifest)
        classifierArchive.set(classifier)
        compiledJvmTestRuntime.set(runner)
        evidenceFile.set(layout.buildDirectory.file(
            "reports/jvm-runtime-evidence/${jvmRuntimeEvidenceFileName(distribution.target)}",
        ))
    }
}
