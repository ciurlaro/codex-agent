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
    ),
    listOf(
        ":codex-agent-runtime-ios:compileKotlinIosArm64",
        ":codex-agent-runtime-ios:iosSimulatorArm64Test", ":codex-agent-runtime-ios:verifyCodexAgentSwiftPackage",
        ":codex-agent-runtime-ios:verifyCodexAgentSwiftAuthenticationTests",
    ),
    listOf(
        ":codex-agent-runtime-ios:packageCodexAgentSwiftPackageBinary",
        ":codex-agent-runtime-ios:generateCodexAgentSwiftPackageChecksum",
        ":codex-agent-runtime-ios:verifyCodexAgentRemoteSwiftPackage",
        ":codex-agent-runtime-ios:verifyIosDeploymentTargets", ":codex-agent-runtime-ios:verifyIosLicensePackaging",
        ":codex-agent-runtime-ios:verifyIosReleaseBudgets", ":codex-agent-runtime-ios:recordCodexAgentSwiftPackageProof",
        ":stageProtectedSwiftPackage", ":stageProtectedSwiftChecksum",
    ),
    listOf(":codex-agent-runtime-ios:verifyIosPrivacyManifest", ":stageProtectedPrivacyAudit"),
    listOf(
        ":stageCentralRepository", ":verifyCentralStaging", ":extractProtectedWindowsNodeSupervisor",
        ":verifyImportedNodeRuntimeEvidence", ":stageProtectedWindowsSupervisorIdentity",
        ":stageProtectedNodeRuntimeEvidence",
    ),
    listOf(":verifyStagedKmpConsumer"),
    listOf(":packageCentralBundle", ":measureProtectedCandidateResources"),
    listOf(":generateCandidateManifest", ":verifyCandidateManifest"),
)

fun Project.registerProtectedCandidatePhases(
    prepare: TaskProvider<PrepareProtectedCandidateTask>,
): ProtectedCandidatePhases {
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
            generated.map { it.desktopEvidence.files },
            generated.map { it.nodeEvidence.files }, generated.flatMap { it.nodeRuntimeRunner },
            generated.flatMap { it.windowsSupervisorIdentity },
            generated.flatMap { it.iosNativeEvidence },
            generated.flatMap { it.privacyAudit }, generated.flatMap { it.artifactMetrics },
            generated.map { it.resourceReports }, generated.flatMap { it.approvalsFile },
            generated.flatMap { it.privacyManifest }, generated.flatMap { it.privacyDataFlowReview },
            generated.map { it.privacyReviews.orNull?.asFile }, generated.flatMap { it.packageSwift },
            generated.flatMap { it.desktopDistributionManifest }, generated.flatMap { it.desktopBundledLicense },
            generated.flatMap { it.desktopBundledNotice },
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
        markers.zip(listOf(prepare) + markers.dropLast(1)).zip(protectedCandidatePhaseGatePaths)
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
