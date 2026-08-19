import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

data class ProtectedCandidatePhases(val privacy: TaskProvider<Task>)

internal val protectedCandidatePhaseGatePaths = listOf(
    listOf(
        ":codex-agent-runtime-ios:verifyAppleToolchain",
        ":codex-agent-runtime-ios:validateImportedCodexAgentIosNativeEvidence",
        ":codex-agent-runtime-ios:prepareCodexAgentIosArm64RustSlice",
        ":codex-agent-runtime-ios:prepareCodexAgentIosSimulatorArm64RustSlice",
        ":stageProtectedIosNativeEvidence",
        ":stageProtectedCiProvenance",
    ),
    listOf(
        ":codex-agent-runtime-ios:compileKotlinIosArm64",
        ":codex-agent-runtime-ios:iosSimulatorArm64Test",
        ":codex-agent-runtime-ios:verifyCodexAgentSwiftPackage",
        ":codex-agent-runtime-ios:verifyCodexAgentSwiftAuthenticationTests",
    ),
    listOf(
        ":codex-agent-runtime-ios:packageCodexAgentSwiftPackageBinary",
        ":codex-agent-runtime-ios:generateCodexAgentSwiftPackageChecksum",
        ":codex-agent-runtime-ios:verifyCodexAgentRemoteSwiftPackage",
        ":codex-agent-runtime-ios:verifyIosDeploymentTargets",
        ":codex-agent-runtime-ios:verifyIosLicensePackaging",
        ":codex-agent-runtime-ios:verifyIosReleaseBudgets",
        ":codex-agent-runtime-ios:recordCodexAgentSwiftPackageProof",
        ":stageProtectedSwiftPackage", ":stageProtectedSwiftChecksum",
    ),
    listOf(":codex-agent-runtime-ios:verifyIosPrivacyManifest", ":stageProtectedPrivacyAudit"),
    listOf(
        ":stageCentralRepository", ":verifyCentralStaging",
        ":verifyImportedJvmRuntimeEvidence", ":stageProtectedJvmRuntimeEvidence",
        ":verifyImportedNodeRuntimeEvidence", ":stageProtectedNodeRuntimeEvidence",
        ":verifyImportedNodeWasmRuntimeEvidence", ":stageProtectedNodeWasmRuntimeEvidence",
        FIREBASE_ANDROID_VERIFY_TASK_PATH, ":stageProtectedFirebaseAndroidRuntimeEvidence",
    ),
    listOf(":verifyStagedKmpConsumer"),
    listOf(":packageCentralBundle"),
    listOf(":generateCandidateManifest", ":verifyCandidateManifest"),
)

internal fun protectedCandidateGatePaths(reuseVerifiedApple: Boolean): List<List<String>> {
    if (!reuseVerifiedApple) return protectedCandidatePhaseGatePaths
    return protectedCandidatePhaseGatePaths.toMutableList().apply {
        this[1] = listOf(":codex-agent-runtime-ios:validateImportedCodexAgentIosVerifiedDistribution")
        this[2] = listOf(
            ":codex-agent-runtime-ios:generateCodexAgentSwiftPackageChecksum",
            ":codex-agent-runtime-ios:verifyCodexAgentRemoteSwiftPackage",
            ":codex-agent-runtime-ios:recordCodexAgentSwiftPackageProof",
            ":stageProtectedSwiftPackage", ":stageProtectedSwiftChecksum",
        )
        this[3] = listOf(":stageProtectedPrivacyAudit")
    }
}

fun Project.registerProtectedCandidatePhases(
    prepare: TaskProvider<PrepareProtectedCandidateTask>,
): ProtectedCandidatePhases {
    val phaseGatePaths = protectedCandidateGatePaths(
        providers.gradleProperty(IOS_VERIFIED_DISTRIBUTION_PROPERTY).isPresent,
    )
    val markers = listOf(
        tasks.register("protectedCandidateNative"),
        tasks.register("protectedCandidateIosTests"),
        tasks.register("protectedCandidateSwiftPackage"),
        tasks.register("protectedCandidatePrivacy"),
        tasks.register("protectedCandidateMaven"),
        tasks.register("protectedCandidateConsumer"),
        tasks.register("protectedCandidateBundle"),
        tasks.register("protectedCandidateManifest"),
    )
    val generated = tasks.named<GenerateCandidateManifestTask>("generateCandidateManifest")
    val payload = tasks.register<StageProtectedCandidatePayloadTask>("stageProtectedCandidatePayload") {
        dependsOn(tasks.named("verifyCandidateManifest"))
        manifestFile.set(generated.flatMap { it.outputFile })
        sourceFiles.from(
            generated.flatMap { it.swiftZip }, generated.flatMap { it.swiftPmProof },
            generated.flatMap { it.centralBundle }, generated.flatMap { it.centralInventory },
            generated.flatMap { it.mavenInventory }, generated.flatMap { it.kmpConsumer },
            generated.flatMap { it.ciProvenance },
            generated.map { it.desktopEvidence.files },
            generated.map { it.jvmEvidence.files }, generated.flatMap { it.jvmRuntimeRunner },
            generated.map { it.nodeEvidence.files }, generated.flatMap { it.nodeRuntimeRunner },
            generated.map { it.nodeWasmEvidence.files }, generated.flatMap { it.nodeWasmRuntimeRunner },
            generated.map { it.androidEvidence.files }, generated.flatMap { it.iosNativeEvidence },
            generated.flatMap { it.privacyAudit }, generated.flatMap { it.artifactMetrics },
            generated.flatMap { it.iosRuntimeMetrics }, generated.flatMap { it.approvalsFile },
            generated.flatMap { it.privacyManifest }, generated.flatMap { it.privacyDataFlowReview },
            generated.map { it.privacyReviews.orNull?.asFile }, generated.flatMap { it.packageSwift },
            generated.flatMap { it.desktopDistributionManifest },
            generated.flatMap { it.desktopBundledLicense }, generated.flatMap { it.desktopBundledNotice },
        )
        expectedVersion.set(generated.flatMap { it.candidateVersion })
        expectedTag.set(generated.flatMap { it.releaseTag })
        expectedCommit.set(generated.flatMap { it.candidateCommit })
        candidateDirectory.set(prepare.flatMap { it.candidateDirectory })
        outputDirectory.set(prepare.flatMap { it.candidateDirectory.dir("payload") })
        verificationFile.set(prepare.flatMap { it.candidateDirectory.file("reports/payload-verification.json") })
    }
    tasks.register("assembleProtectedCandidate") {
        group = "publishing"
        description = "Assembles one fresh commit-isolated technical candidate without rebuilding evidence."
        dependsOn(payload)
    }
    gradle.projectsEvaluated {
        markers.zip(listOf(prepare) + markers.dropLast(1)).zip(phaseGatePaths)
            .forEach { (phase, paths) ->
                phase.first.configure { dependsOn(phase.second); dependsOn(paths) }
                paths.forEach { path ->
                    val owner = project(path.substringBeforeLast(':').ifBlank { ":" })
                    owner.tasks.findByName(path.substringAfterLast(':'))?.mustRunAfter(phase.second)
                }
            }
        wireProtectedCandidatePhase(payload, markers.last(), emptyList())
    }
    return ProtectedCandidatePhases(markers[3])
}

fun wireProtectedCandidatePhase(
    marker: TaskProvider<out Task>,
    previous: TaskProvider<out Task>,
    gates: List<TaskProvider<out Task>>,
) {
    gates.forEach { it.configure { mustRunAfter(previous) } }
    marker.configure { dependsOn(previous); dependsOn(gates) }
}
