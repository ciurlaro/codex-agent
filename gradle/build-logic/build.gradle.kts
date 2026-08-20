import java.io.File as JavaFile
import java.io.Serializable
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.jvm.tasks.Jar
import org.gradle.api.tasks.testing.Test
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

plugins { `kotlin-dsl` }
repositories { google(); mavenCentral(); gradlePluginPortal() }
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    testImplementation(gradleTestKit())
    testImplementation(kotlin("test-junit"))
}
tasks.withType<Test>().configureEach { maxParallelForks = 2 }

val releaseToolingRuntime by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = true
}
dependencies.add(releaseToolingRuntime.name, libs.kotlinx.serialization.json)
val releaseToolingClasses = listOf(
    "AndroidRuntimeEvidenceFilesKt",
    "AndroidRuntimeEvidenceSupportKt",
    "AppleArtifactMetrics",
    "AppleNativeTestCommand",
    "AppleNativeTestsIdentity",
    "AppleReleaseCheckTasksKt",
    "AppleRustEvidenceIdentity",
    "AppleRustSliceModelKt",
    "AppleRustSliceSpec",
    "BoundProducedEvidence",
    "BoundRuntimeEvidence",
    "CandidateCiProvenanceKt",
    "CandidateInputFiles",
    "CandidateIosNativeEvidenceKt",
    "CandidateManifestValidationKt",
    "CandidateModelKt",
    "CandidatePayloadTasksKt",
    "CandidateRuntimeEvidenceKt",
    "CentralBundleTasksKt",
    "CentralDeployment",
    "CentralExpectedFile",
    "CentralIdentity",
    "CentralPortalHttpKt",
    "CentralPortalRecordKt",
    "CentralPortalRequest",
    "CentralPortalResponse",
    "CentralPortalTaskKt",
    "CentralPortalVerificationKt",
    "DeploymentTargetRecord",
    "DesktopClassifierInspectionKt",
    "DesktopClassifierProof",
    "DesktopCodexDistributionSpec",
    "DesktopCodexManifest",
    "DesktopRuntimeEvidenceTarget",
    "DesktopRuntimeEvidenceTasksKt",
    "DesktopRuntimeEvidenceValues",
    "DesktopRuntimeExecutables",
    "DesktopRuntimePackageTaskKt",
    "FirebaseAndroidEvidenceValues",
    "FirebaseAndroidRuntimeEvidenceModelKt",
    "FirebaseTestMatrix",
    "InspectedDesktopClassifier",
    "IosPrivacyAuditVerificationKt",
    "IosPrivacyCategory",
    "IosPrivacyHit",
    "IosPrivacyPolicy",
    "IosPrivacyPolicyKt",
    "IosPrivacyReviewBindingKt",
    "IosPrivacySignals",
    "JdkCentralPortalSender",
    "JvmRuntimeEvidenceModelKt",
    "JvmRuntimeEvidenceValues",
    "KmpConsumerVerificationTaskKt",
    "MavenArtifactSpec",
    "MavenRepositoryTasksKt",
    "NodeRuntimeEvidenceModelKt",
    "NodeRuntimeEvidenceValues",
    "PrivacyReleaseVerificationTasksKt",
    "PromotedCandidateInputs",
    "PromotedCandidateTasksKt",
    "PromotedIosEvidence",
    "PromotedLane",
    "PromotedRuntimeEvidence",
    "PromotedRustProof",
    "ReleaseIoKt",
    "ReleaseToolingArguments",
    "ReleaseToolingCliKt",
    "ReviewedPrivacyApi",
    "TransportProducerIdentity",
    "TransportedRuntimeEvidence",
)
val releaseToolingJar = tasks.register<Jar>("releaseToolingJar") {
    group = "build"
    description = "Packages the no-Gradle release CLI used by candidate and publication runners."
    dependsOn(tasks.named("classes"))
    archiveFileName.set("codex-agent-release-tooling.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest.attributes["Main-Class"] = "ReleaseToolingCliKt"
    from(sourceSets.main.get().output) {
        include(releaseToolingClasses.map { "$it*.class" })
    }
    from(provider {
        releaseToolingRuntime.filter { dependency ->
            dependency.name.startsWith("kotlin-stdlib-") ||
                dependency.name.startsWith("kotlinx-serialization-core-jvm-") ||
                dependency.name.startsWith("kotlinx-serialization-json-jvm-")
        }.map(::zipTree)
    })
    exclude(
        "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/gradle-plugins/**",
        "gradle/**", "Codexagent_*",
    )
}
tasks.withType<Test>().configureEach {
    dependsOn(releaseToolingJar)
    doFirst {
        systemProperty("codexAgent.releaseToolingJar", releaseToolingJar.get().archiveFile.get().asFile)
    }
}

val candidateCommit = providers.gradleProperty("codexAgent.candidateCommit")
val bundle = providers.gradleProperty("codexAgent.linuxArm64RuntimeEvidenceBundle").map(::JavaFile)
    .orElse(layout.buildDirectory.file("linux-arm64-runtime-evidence/linux-arm64-runtime-evidence.zip").map { it.asFile })
val manifest = providers.gradleProperty("codexAgent.desktopDistributionManifest").map(::JavaFile)
    .orElse(layout.projectDirectory.file("../../codex-agent-runtime-desktop/codex-app-server-distributions.json").asFile)
val classifierArchive = providers.gradleProperty("codexAgent.linuxArm64ClassifierArchive").map(::JavaFile)
val classifierDirectory = providers.gradleProperty("codexAgent.linuxArm64DistributionsDirectory").map(::JavaFile)
val classifier = classifierArchive.orElse(classifierDirectory)
tasks.register<JavaExec>("stageLinuxArm64RuntimeEvidenceBundle") {
    group = "verification"
    description = "Stages one hash-manifested Linux ARM64 runtime evidence bundle."
    val test = providers.gradleProperty("codexAgent.linuxArm64TestExecutable").map(::JavaFile)
    val jvm = providers.gradleProperty("codexAgent.jvmRuntimeEvidenceRunner").map(::JavaFile)
    val js = providers.gradleProperty("codexAgent.nodeRuntimeEvidenceRunnerArchive").map(::JavaFile)
    val wasm = providers.gradleProperty("codexAgent.nodeWasmRuntimeEvidenceRunnerArchive").map(::JavaFile)
    dependsOn(tasks.named("classes")); classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("LinuxArm64RuntimeEvidenceBundleKt")
    inputs.property("candidateCommit", candidateCommit); inputs.file(test); inputs.files(classifier)
    inputs.file(manifest); inputs.file(jvm); inputs.file(js); inputs.file(wasm); outputs.file(bundle)
    argumentProviders.add(DesktopEvidenceArgumentProvider(
        "stage", listOf(candidateCommit, test, classifier, manifest, jvm, js, wasm, bundle),
    ))
}

tasks.register<JavaExec>("executeLinuxArm64RuntimeEvidenceBundle") {
    group = "verification"
    description = "Executes all four Linux ARM64 runtime backends from one extracted bundle."
    val java = providers.gradleProperty("codexAgent.javaExecutable")
    val node = providers.gradleProperty("codexAgent.nodeExecutable").orElse("node")
    val desktopEvidence = providers.gradleProperty("codexAgent.desktopEvidenceOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file("reports/desktop-runtime-evidence/desktop-runtime-linuxArm64.json").map { it.asFile })
    val desktopReport = providers.gradleProperty("codexAgent.desktopTestReportOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file("test-results/linuxArm64SplitTest/TEST-linuxArm64Test." +
            "io.github.codex_agent_labs.codexmobile.appserver.runtime.DesktopCodexRuntimeTest.xml").map { it.asFile })
    val jvmEvidence = providers.gradleProperty("codexAgent.jvmEvidenceOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file("reports/jvm-runtime-evidence/jvm-runtime-linuxArm64.json").map { it.asFile })
    val nodeEvidence = providers.gradleProperty("codexAgent.nodeEvidenceOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file("reports/node-runtime-evidence/node-runtime-linuxArm64.json").map { it.asFile })
    val nodeReport = providers.gradleProperty("codexAgent.nodeTestReportOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file("test-results/linuxArm64NodeSplitTest/TEST-nodeRuntimeLinuxArm64Test." +
            "io.github.codex_agent_labs.codexmobile.appserver.runtime.NodeCodexRuntimeTest.xml").map { it.asFile })
    val wasmEvidence = providers.gradleProperty("codexAgent.nodeWasmEvidenceOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file("reports/node-runtime-evidence/node-wasm-runtime-linuxArm64.json").map { it.asFile })
    val wasmReport = providers.gradleProperty("codexAgent.nodeWasmTestReportOutput").map(::JavaFile).orElse(
        layout.buildDirectory.file("test-results/linuxArm64NodeWasmSplitTest/TEST-nodeWasmRuntimeLinuxArm64Test." +
            "io.github.codex_agent_labs.codexmobile.appserver.runtime.NodeCodexRuntimeTest.xml").map { it.asFile })
    dependsOn(tasks.named("classes")); classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("LinuxArm64RuntimeEvidenceBundleKt")
    inputs.property("candidateCommit", candidateCommit); inputs.file(bundle)
    inputs.property("javaExecutable", java); inputs.property("nodeExecutable", node)
    inputs.property("runnerOs", providers.environmentVariable("RUNNER_OS"))
    inputs.property("runnerArch", providers.environmentVariable("RUNNER_ARCH"))
    outputs.files(desktopEvidence, desktopReport, jvmEvidence, nodeEvidence, nodeReport, wasmEvidence, wasmReport)
    outputs.upToDateWhen { false }
    argumentProviders.add(DesktopEvidenceArgumentProvider(
        "execute", listOf(candidateCommit, bundle, java, node, desktopEvidence, desktopReport,
            jvmEvidence, nodeEvidence, nodeReport, wasmEvidence, wasmReport),
    ))
}
