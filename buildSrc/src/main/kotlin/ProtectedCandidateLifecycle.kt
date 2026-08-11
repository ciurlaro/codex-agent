import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

internal data class ProtectedCandidatePreflight(
    val version: String,
    val releaseTag: String,
    val commit: String,
    val head: String,
    val trackedStatus: String,
    val parallel: Boolean,
    val repository: File,
    val candidateDirectory: File,
    val androidEvidence: File,
    val baselineProof: File,
)

internal val protectedCandidateStatusArguments =
    listOf("status", "--porcelain=v1", "--untracked-files=normal")

internal fun prepareProtectedCandidateDirectory(input: ProtectedCandidatePreflight) {
    check(input.commit.matches(Regex("[0-9a-f]{40}"))) { "Candidate commit must be 40 lowercase hexadecimal characters" }
    check(input.releaseTag == "v${input.version}") { "Candidate release tag must equal v${input.version}" }
    check(input.head == input.commit) { "Checked-out HEAD ${input.head} does not match candidate ${input.commit}" }
    check(input.trackedStatus.isBlank()) {
        "Protected candidate requires a clean checkout, including non-ignored untracked files"
    }
    check(!input.parallel) { "assembleProtectedCandidate must be invoked with --no-parallel" }
    check(input.baselineProof.isFile) { "SwiftPM Commit A baseline proof is missing: ${input.baselineProof}" }
    val evidenceErrors = validateAndroidEvidence(input.androidEvidence, input.commit)
    check(evidenceErrors.isEmpty()) { "Android runtime evidence is invalid: ${evidenceErrors.joinToString()}" }

    val repository = input.repository.canonicalFile
    val candidate = input.candidateDirectory.canonicalFile
    check(candidate == repository.resolve("build/protected-candidate/${input.commit}").canonicalFile) {
        "Protected candidate output must use the commit-isolated build directory"
    }
    check(!input.androidEvidence.canonicalFile.toPath().startsWith(candidate.toPath())) {
        "Android evidence must be external to protected candidate output"
    }
    check(!input.baselineProof.canonicalFile.toPath().startsWith(repository.toPath())) {
        "SwiftPM Commit A baseline proof must be outside the repository"
    }

    candidate.deleteRecursively()
    listOf("artifacts", "evidence", "maven-repository", "clean-consumer", "reports")
        .forEach { candidate.resolve(it).mkdirs() }
}

@DisableCachingByDefault(because = "Preflight validates live Git state and clears only fresh proof output")
abstract class PrepareProtectedCandidateTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val releaseTag: Property<String>
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val parallelExecution: Property<Boolean>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val androidEvidence: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val baselineProof: RegularFileProperty
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:Internal abstract val candidateDirectory: DirectoryProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun prepare() = prepareProtectedCandidateDirectory(ProtectedCandidatePreflight(
        version.get(), releaseTag.get(), candidateCommit.get(), git("rev-parse", "HEAD^{commit}"),
        git(*protectedCandidateStatusArguments.toTypedArray()), parallelExecution.get(),
        repositoryDirectory.get().asFile, candidateDirectory.get().asFile,
        androidEvidence.get().asFile, baselineProof.get().asFile,
    ))

    private fun git(vararg arguments: String): String {
        val output = ByteArrayOutputStream()
        processes.exec {
            workingDir(repositoryDirectory)
            commandLine("git", *arguments)
            standardOutput = output
        }.assertNormalExitValue()
        return output.toString(Charsets.UTF_8).trim()
    }
}

@CacheableTask
abstract class StageAndroidEvidenceTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val evidenceFile: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val evidenceDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun stage() {
        val source = evidenceDirectory.get().asFile
        val output = outputDirectory.get().asFile.apply { deleteRecursively(); mkdirs() }
        listOf(ANDROID_EVIDENCE_FILE, ANDROID_EVIDENCE_APK, ANDROID_EVIDENCE_REPORT).forEach { name ->
            val file = safePayloadFile(source, name)
            check(file.isFile) { "Android evidence payload is missing: $name" }
            Files.copy(file.toPath(), output.resolve(name).toPath(), REPLACE_EXISTING)
        }
        check(evidenceFile.get().asFile.canonicalFile == source.resolve(ANDROID_EVIDENCE_FILE).canonicalFile) {
            "Android evidence file must be the canonical schema-v2 payload"
        }
    }
}

@CacheableTask
abstract class CopyCandidateFileTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val sourceFile: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun copy() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        Files.copy(sourceFile.get().asFile.toPath(), output.toPath(), REPLACE_EXISTING)
    }
}

@DisableCachingByDefault(because = "Final verification must recompute every candidate binding")
abstract class VerifyProtectedCandidateManifestTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifestFile: RegularFileProperty
    @get:Input abstract val candidateVersion: Property<String>
    @get:Input abstract val releaseTag: Property<String>
    @get:Input abstract val candidateCommit: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftZip: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftChecksum: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftPmAbProof: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val centralBundle: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val centralInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mavenInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val kmpConsumer: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val androidEvidence: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyAudit: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val artifactMetrics: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val resourceReports: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val approvalsFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyDataFlowReview: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyReviews: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val packageSwift: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun verify() = verifyProtectedCandidateManifest(manifestFile.get().asFile, candidateInputs())

    internal fun candidateInputs() = CandidateInputFiles(
        version = candidateVersion.get(), releaseTag = releaseTag.get(), commit = candidateCommit.get(),
        swiftZip = swiftZip.get().asFile, swiftChecksum = swiftChecksum.get().asFile,
        swiftPmAbProof = swiftPmAbProof.get().asFile, centralBundle = centralBundle.get().asFile,
        centralInventory = centralInventory.get().asFile, mavenInventory = mavenInventory.get().asFile,
        kmpConsumer = kmpConsumer.get().asFile, androidEvidence = androidEvidence.get().asFile,
        privacyAudit = privacyAudit.get().asFile, artifactMetrics = artifactMetrics.get().asFile,
        resourceReports = resourceReports.files.sortedBy(File::getName),
        approvals = approvalsFile.get().asFile, privacyManifest = privacyManifest.get().asFile,
        privacyDataFlowReview = privacyDataFlowReview.get().asFile,
        privacyRequiredReasonReviews = privacyReviews.orNull?.asFile, packageSwift = packageSwift.get().asFile,
    )
}

internal fun verifyProtectedCandidateManifest(manifest: File, inputs: CandidateInputFiles) {
    check(manifest.readReleaseObject() == buildCandidateManifest(inputs)) {
        "Candidate manifest does not match the exact current artifacts, evidence, and policies"
    }
}

data class ProtectedCandidatePhases(val privacy: TaskProvider<Task>)

fun Project.registerProtectedCandidatePhases(
    prepare: TaskProvider<PrepareProtectedCandidateTask>,
): ProtectedCandidatePhases {
    val clean = tasks.register("protectedCandidateClean")
    val native = tasks.register("protectedCandidateNative")
    val iosTests = tasks.register("protectedCandidateIosTests")
    val swiftPackage = tasks.register("protectedCandidateSwiftPackage")
    val privacy = tasks.register("protectedCandidatePrivacy")
    val maven = tasks.register("protectedCandidateMaven")
    val consumer = tasks.register("protectedCandidateConsumer")
    val bundle = tasks.register("protectedCandidateBundle")
    val manifest = tasks.register("protectedCandidateManifest")
    val generatedManifest = tasks.named<GenerateCandidateManifestTask>("generateCandidateManifest")
    val verifiedManifest = tasks.named("verifyCandidateManifest")
    val payload = tasks.register<StageProtectedCandidatePayloadTask>("stageProtectedCandidatePayload") {
        dependsOn(verifiedManifest)
        manifestFile.set(generatedManifest.flatMap { it.outputFile })
        sourceFiles.from(
            generatedManifest.flatMap { it.swiftZip }, generatedManifest.flatMap { it.swiftPmAbProof },
            generatedManifest.flatMap { it.centralBundle }, generatedManifest.flatMap { it.centralInventory },
            generatedManifest.flatMap { it.mavenInventory }, generatedManifest.flatMap { it.kmpConsumer },
            generatedManifest.flatMap { it.androidEvidence }, generatedManifest.flatMap { it.privacyAudit },
            generatedManifest.flatMap { it.artifactMetrics }, generatedManifest.map { it.resourceReports },
            generatedManifest.flatMap { it.approvalsFile },
            generatedManifest.flatMap { it.privacyManifest }, generatedManifest.flatMap { it.privacyDataFlowReview },
            generatedManifest.map { it.privacyReviews.orNull?.asFile }, generatedManifest.flatMap { it.packageSwift },
        )
        expectedVersion.set(generatedManifest.flatMap { it.candidateVersion })
        expectedTag.set(generatedManifest.flatMap { it.releaseTag })
        expectedCommit.set(generatedManifest.flatMap { it.candidateCommit })
        candidateDirectory.set(prepare.flatMap { it.candidateDirectory })
        outputDirectory.set(prepare.flatMap { it.candidateDirectory.dir("payload") })
        verificationFile.set(prepare.flatMap { it.candidateDirectory.file("reports/payload-verification.json") })
    }
    tasks.register("assembleProtectedCandidate") {
        group = "publishing"
        description = "Builds the exact commit-isolated technical candidate without external publication approvals."
        dependsOn(payload)
    }

    gradle.projectsEvaluated {
        fun task(path: String) = project(path.substringBeforeLast(':').ifBlank { ":" })
            .tasks.named(path.substringAfterLast(':'))
        val phases = listOf(
            Triple(clean, prepare, listOf(
                ":clean", ":codex-agent-client:clean", ":codex-agent-runtime-android:clean",
                ":codex-agent-runtime-ios:clean",
            )),
            Triple(native, clean, listOf(
                ":codex-agent-runtime-ios:preparePinnedCodexIosArchive", ":codex-agent-runtime-ios:preparePinnedSqliteArchive",
                ":codex-agent-runtime-ios:verifyCodexIosProvenance", ":codex-agent-runtime-ios:prepareCodexIosSource",
                ":codex-agent-runtime-ios:testCodexIosBridge", ":codex-agent-runtime-ios:testCodexIosDirectToolMode",
                ":codex-agent-runtime-ios:buildCodexIosArm64Rust", ":codex-agent-runtime-ios:buildCodexIosSimulatorArm64Rust",
            )),
            Triple(iosTests, native, listOf(
                ":codex-agent-runtime-ios:verifyAppleToolchain", ":codex-agent-runtime-ios:compileKotlinIosArm64",
                ":codex-agent-runtime-ios:iosSimulatorArm64Test", ":codex-agent-runtime-ios:verifyCodexAgentSwiftPackage",
                ":codex-agent-runtime-ios:verifyCodexAgentSwiftAuthenticationTests",
            )),
            Triple(swiftPackage, iosTests, listOf(
                ":codex-agent-runtime-ios:packageCodexAgentSwiftPackageBinary",
                ":codex-agent-runtime-ios:generateCodexAgentSwiftPackageChecksum",
                ":codex-agent-runtime-ios:verifyCodexAgentRemoteSwiftPackage",
                ":codex-agent-runtime-ios:verifyIosDeploymentTargets", ":codex-agent-runtime-ios:verifyIosLicensePackaging",
                ":codex-agent-runtime-ios:verifyIosReleaseBudgets", ":codex-agent-runtime-ios:verifyCodexAgentSwiftPackageAB",
                ":stageProtectedSwiftPackage", ":stageProtectedSwiftChecksum",
            )),
            Triple(privacy, swiftPackage, listOf(
                ":codex-agent-runtime-ios:verifyIosPrivacyManifest", ":stageProtectedPrivacyAudit",
            )),
            Triple(maven, privacy, listOf(
                ":stageCentralRepository", ":verifyCentralStaging", ":stageAndroidRuntimeEvidence",
                ":verifyAndroidRuntimeEvidence",
            )),
            Triple(consumer, maven, listOf(":verifyStagedKmpConsumer")),
            Triple(bundle, consumer, listOf(":packageCentralBundle", ":measureProtectedCandidateResources")),
            Triple(manifest, bundle, listOf(":generateCandidateManifest", ":verifyCandidateManifest")),
        )
        phases.forEach { (marker, previous, paths) ->
            wireProtectedCandidatePhase(marker, previous, paths.map(::task))
        }
        wireProtectedCandidatePhase(payload, manifest, emptyList())
    }
    return ProtectedCandidatePhases(privacy)
}

fun wireProtectedCandidatePhase(
    marker: TaskProvider<out Task>,
    previous: TaskProvider<out Task>,
    gates: List<TaskProvider<out Task>>,
) {
    gates.forEach { it.configure { mustRunAfter(previous) } }
    marker.configure {
        dependsOn(previous)
        dependsOn(gates)
    }
}
