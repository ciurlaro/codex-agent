import java.io.File as JavaFile
import java.io.Serializable
import org.gradle.api.provider.Provider
import org.gradle.process.CommandLineArgumentProvider

class DesktopEvidenceArgumentProvider(
    private val action: String,
    private val values: List<Provider<out Any>>,
) : CommandLineArgumentProvider, Serializable {
    override fun asArguments() = listOf(action) + values.map { provider ->
        when (val value = provider.get()) {
            is JavaFile -> value.absolutePath
            else -> value.toString()
        }
    }
}

plugins {
    `kotlin-dsl`
}
repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit"))
}

val candidateCommit = providers.gradleProperty("codexAgent.candidateCommit")
val linuxArm64Bundle = providers.gradleProperty("codexAgent.linuxArm64ExecutionBundle")
    .map(::JavaFile).orElse(layout.buildDirectory.file("linux-arm64-evidence/linux-arm64-execution.zip").map { it.asFile })

tasks.register<JavaExec>("stageLinuxArm64DesktopEvidenceBundle") {
    group = "verification"
    description = "Stages the cross-built Linux ARM64 runtime evidence inputs."
    val testExecutable = providers.gradleProperty("codexAgent.linuxArm64TestExecutable").map(::JavaFile)
    val classifierArchive = providers.gradleProperty("codexAgent.linuxArm64ClassifierArchive").map(::JavaFile)
    val distributionsDirectory = providers.gradleProperty("codexAgent.linuxArm64DistributionsDirectory").map(::JavaFile)
    val classifierInput = classifierArchive.orElse(distributionsDirectory)
    dependsOn(tasks.named("classes")); classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("LinuxArm64DesktopEvidenceBundleKt")
    inputs.property("candidateCommit", candidateCommit); inputs.file(testExecutable); inputs.files(classifierInput)
    outputs.file(linuxArm64Bundle)
    argumentProviders.add(DesktopEvidenceArgumentProvider(
        "stage", listOf(candidateCommit, testExecutable, classifierInput, linuxArm64Bundle),
    ))
}

tasks.register<JavaExec>("executeLinuxArm64DesktopEvidenceBundle") {
    group = "verification"
    description = "Executes the staged smoke on a real Linux ARM64 runner and records schema-v2 evidence."
    val evidence = providers.gradleProperty("codexAgent.desktopEvidenceOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file("reports/desktop-runtime-evidence/desktop-runtime-linuxArm64.json").map { it.asFile },
    )
    val report = providers.gradleProperty("codexAgent.desktopTestReportOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file("test-results/linuxArm64SplitTest/TEST-linuxArm64Test." +
            "io.github.ciurlaro.codexmobile.appserver.runtime.DesktopCodexRuntimeTest.xml").map { it.asFile },
    )
    dependsOn(tasks.named("classes")); classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("LinuxArm64DesktopEvidenceBundleKt")
    inputs.property("candidateCommit", candidateCommit); inputs.file(linuxArm64Bundle)
    outputs.file(evidence); outputs.file(report); outputs.upToDateWhen { false }
    argumentProviders.add(DesktopEvidenceArgumentProvider(
        "execute", listOf(candidateCommit, linuxArm64Bundle, evidence, report),
    ))
}

val nodeArm64Bundle = providers.gradleProperty("codexAgent.linuxArm64NodeExecutionBundle")
    .map(::JavaFile).orElse(layout.buildDirectory.file(
        "linux-arm64-node-evidence/linux-arm64-node-execution.zip",
    ).map { it.asFile })
val desktopDistributionManifest = providers.gradleProperty("codexAgent.desktopDistributionManifest")
    .map(::JavaFile).orElse(layout.projectDirectory.file(
        "../codex-agent-runtime-desktop/codex-app-server-distributions.json",
    ).asFile)
val nodeExecutable = providers.gradleProperty("codexAgent.nodeExecutable").orElse("node")

tasks.register<JavaExec>("stageLinuxArm64NodeRuntimeEvidenceBundle") {
    group = "verification"
    description = "Stages the prebuilt Node runtime smoke and existing Linux ARM64 classifier."
    val compiled = providers.gradleProperty("codexAgent.nodeRuntimeEvidenceRunnerArchive").map(::JavaFile)
    val classifierArchive = providers.gradleProperty("codexAgent.linuxArm64ClassifierArchive").map(::JavaFile)
    val distributionsDirectory = providers.gradleProperty("codexAgent.linuxArm64DistributionsDirectory").map(::JavaFile)
    val classifierInput = classifierArchive.orElse(distributionsDirectory)
    dependsOn(tasks.named("classes")); classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("NodeRuntimeEvidenceLinuxArm64Kt")
    inputs.property("candidateCommit", candidateCommit); inputs.file(compiled); inputs.files(classifierInput)
    inputs.file(desktopDistributionManifest); outputs.file(nodeArm64Bundle)
    argumentProviders.add(DesktopEvidenceArgumentProvider(
        "stage", listOf(candidateCommit, compiled, classifierInput, desktopDistributionManifest, nodeArm64Bundle),
    ))
}

tasks.register<JavaExec>("executeLinuxArm64NodeRuntimeEvidenceBundle") {
    group = "verification"
    description = "Runs the prebuilt Node smoke on Linux ARM64 and records exact evidence."
    val evidence = providers.gradleProperty("codexAgent.nodeEvidenceOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file("reports/node-runtime-evidence/node-runtime-linuxArm64.json").map { it.asFile },
    )
    val report = providers.gradleProperty("codexAgent.nodeTestReportOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file(
            "test-results/linuxArm64NodeSplitTest/" +
                "TEST-nodeRuntimeLinuxArm64Test." +
                "io.github.ciurlaro.codexmobile.appserver.runtime.NodeCodexRuntimeTest.xml",
        ).map { it.asFile },
    )
    dependsOn(tasks.named("classes")); classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("NodeRuntimeEvidenceLinuxArm64Kt")
    inputs.property("candidateCommit", candidateCommit); inputs.file(nodeArm64Bundle)
    inputs.file(desktopDistributionManifest); inputs.property("nodeExecutable", nodeExecutable)
    inputs.property("runnerOs", providers.environmentVariable("RUNNER_OS"))
    inputs.property("runnerArch", providers.environmentVariable("RUNNER_ARCH"))
    outputs.file(evidence); outputs.file(report); outputs.upToDateWhen { false }
    argumentProviders.add(DesktopEvidenceArgumentProvider(
        "execute", listOf(
            candidateCommit, nodeArm64Bundle, desktopDistributionManifest, nodeExecutable, evidence, report,
        ),
    ))
}
