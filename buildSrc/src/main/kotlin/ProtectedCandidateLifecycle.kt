import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import org.gradle.api.DefaultTask
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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

internal data class ProtectedCandidatePreflight(
    val version: String,
    val releaseTag: String,
    val commit: String,
    val head: String,
    val trackedStatus: String,
    val parallel: Boolean,
    val repository: File,
    val candidateDirectory: File,
    val desktopEvidence: List<File>,
    val jvmEvidence: List<File>,
    val jvmRuntimeRunner: File,
    val nodeEvidence: List<File>,
    val nodeRuntimeRunner: File,
    val nodeWasmEvidence: List<File>,
    val nodeWasmRuntimeRunner: File,
    val androidEvidence: List<File>,
    val iosNativeEvidenceDirectory: File,
)

internal val protectedCandidateStatusArguments =
    listOf("status", "--porcelain=v1", "--untracked-files=normal")

internal fun prepareProtectedCandidateDirectory(input: ProtectedCandidatePreflight) {
    check(input.commit.matches(Regex("[0-9a-f]{40}"))) {
        "Candidate commit must be 40 lowercase hexadecimal characters"
    }
    check(input.releaseTag == "v${input.version}") { "Candidate release tag must equal v${input.version}" }
    check(input.head == input.commit) { "Checked-out HEAD ${input.head} does not match candidate ${input.commit}" }
    check(input.trackedStatus.isBlank()) {
        "Protected candidate requires a clean checkout, including non-ignored untracked files"
    }
    check(!input.parallel) { "assembleProtectedCandidate must be invoked with --no-parallel" }
    requireEvidenceSet(
        "desktop", input.desktopEvidence,
        desktopRuntimeEvidenceTargets.keys.map(::desktopRuntimeEvidenceFileName).toSet(),
    )
    val desktopErrors = validateDesktopRuntimeEvidence(input.desktopEvidence, input.commit)
    check(desktopErrors.isEmpty()) { "Desktop runtime evidence is invalid: ${desktopErrors.joinToString()}" }
    requireEvidenceSet(
        "JVM", input.jvmEvidence,
        desktopRuntimeEvidenceTargets.keys.map(::jvmRuntimeEvidenceFileName).toSet(),
    )
    requireRunner("JVM", input.jvmRuntimeRunner, JVM_RUNTIME_RUNNER_ARCHIVE)
    requireEvidenceSet(
        "Node JS", input.nodeEvidence,
        desktopRuntimeEvidenceTargets.keys.map {
            nodeRuntimeEvidenceFileName(it, NODE_RUNTIME_JS_BACKEND)
        }.toSet(),
    )
    requireRunner("Node JS", input.nodeRuntimeRunner, NODE_RUNTIME_RUNNER_ARCHIVE)
    requireEvidenceSet(
        "Node Wasm", input.nodeWasmEvidence,
        desktopRuntimeEvidenceTargets.keys.map {
            nodeRuntimeEvidenceFileName(it, NODE_RUNTIME_WASM_BACKEND)
        }.toSet(),
    )
    requireRunner("Node Wasm", input.nodeWasmRuntimeRunner, NODE_WASM_RUNTIME_RUNNER_ARCHIVE)
    requireEvidenceSet(
        "Firebase Android", input.androidEvidence,
        protectedFirebaseAndroidRuntimeRawFiles.map(Pair<String, String>::second).toSet(),
    )
    check(input.iosNativeEvidenceDirectory.isDirectory) { "iOS native evidence directory is required" }

    val repository = input.repository.canonicalFile
    val candidate = input.candidateDirectory.canonicalFile
    check(candidate == repository.resolve("build/protected-candidate/${input.commit}").canonicalFile) {
        "Protected candidate output must use the commit-isolated build directory"
    }
    check(!candidate.exists()) {
        "Protected candidate output already exists; refusing to clean or rebuild it: $candidate"
    }
    val externalFiles = input.desktopEvidence + input.jvmEvidence + input.jvmRuntimeRunner +
        input.nodeEvidence + input.nodeRuntimeRunner + input.nodeWasmEvidence +
        input.nodeWasmRuntimeRunner + input.androidEvidence
    check(externalFiles.none { it.canonicalFile.toPath().startsWith(candidate.toPath()) } &&
        !input.iosNativeEvidenceDirectory.canonicalFile.toPath().startsWith(candidate.toPath())) {
        "Runtime evidence must be external to protected candidate output"
    }
    listOf("artifacts", "evidence", "maven-repository", "clean-consumer", "reports")
        .forEach { candidate.resolve(it).mkdirs() }
}

private fun requireEvidenceSet(name: String, files: List<File>, expectedNames: Set<String>) {
    check(files.size == expectedNames.size && files.map(File::getName).toSet() == expectedNames &&
        files.all(File::isFile)) { "Exact $name runtime evidence files are required" }
}

private fun requireRunner(name: String, file: File, expectedName: String) {
    check(file.name == expectedName && file.isFile && file.length() > 0) {
        "Exact $name runtime runner is required"
    }
}

@DisableCachingByDefault(because = "Preflight validates live Git state and reserves fresh proof output")
abstract class PrepareProtectedCandidateTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val version: Property<String>
    @get:Input abstract val releaseTag: Property<String>
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val parallelExecution: Property<Boolean>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val desktopEvidence: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val jvmEvidence: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jvmRuntimeRunner: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val nodeEvidence: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val nodeRuntimeRunner: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val nodeWasmEvidence: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val nodeWasmRuntimeRunner: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val androidEvidence: ConfigurableFileCollection
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE)
    abstract val iosNativeEvidenceDirectory: DirectoryProperty
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:Internal abstract val candidateDirectory: DirectoryProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun prepare() = prepareProtectedCandidateDirectory(ProtectedCandidatePreflight(
        version.get(), releaseTag.get(), candidateCommit.get(), git("rev-parse", "HEAD^{commit}"),
        git(*protectedCandidateStatusArguments.toTypedArray()), parallelExecution.get(),
        repositoryDirectory.asFile.get(), candidateDirectory.asFile.get(),
        desktopEvidence.sorted(), jvmEvidence.sorted(), jvmRuntimeRunner.asFile.get(),
        nodeEvidence.sorted(), nodeRuntimeRunner.asFile.get(), nodeWasmEvidence.sorted(),
        nodeWasmRuntimeRunner.asFile.get(), androidEvidence.sorted(), iosNativeEvidenceDirectory.asFile.get(),
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
abstract class CopyCandidateFileTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val sourceFile: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun copy() {
        val output = outputFile.asFile.get()
        output.parentFile.mkdirs()
        Files.copy(sourceFile.asFile.get().toPath(), output.toPath(), REPLACE_EXISTING)
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
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftPmProof: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val centralBundle: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val centralInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mavenInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val kmpConsumer: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val ciProvenance: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val desktopEvidence: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val desktopClassifierArchives: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val jvmEvidence: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val jvmRuntimeRunner: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val nodeEvidence: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val nodeRuntimeRunner: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val nodeWasmEvidence: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val nodeWasmRuntimeRunner: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val androidEvidence: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val iosNativeEvidence: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyAudit: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val artifactMetrics: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val resourceReports: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val approvalsFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyDataFlowReview: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyReviews: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val packageSwift: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopDistributionManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopBundledLicense: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopBundledNotice: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun verify() = verifyProtectedCandidateManifest(manifestFile.asFile.get(), candidateInputs())

    internal fun candidateInputs() = CandidateInputFiles(
        candidateVersion.get(), releaseTag.get(), candidateCommit.get(),
        swiftZip.asFile.get(), swiftChecksum.asFile.get(), swiftPmProof.asFile.get(),
        centralBundle.asFile.get(), centralInventory.asFile.get(), mavenInventory.asFile.get(),
        kmpConsumer.asFile.get(), ciProvenance.asFile.get(), desktopEvidence.sorted(), desktopClassifierArchives.sorted(),
        jvmEvidence.sorted(), jvmRuntimeRunner.asFile.get(), nodeEvidence.sorted(),
        nodeRuntimeRunner.asFile.get(), nodeWasmEvidence.sorted(), nodeWasmRuntimeRunner.asFile.get(),
        androidEvidence.sorted(), iosNativeEvidence.asFile.get(), privacyAudit.asFile.get(),
        artifactMetrics.asFile.get(), resourceReports.sorted(), approvalsFile.asFile.get(),
        privacyManifest.asFile.get(), privacyDataFlowReview.asFile.get(), privacyReviews.orNull?.asFile,
        packageSwift.asFile.get(), desktopDistributionManifest.asFile.get(),
        desktopBundledLicense.asFile.get(), desktopBundledNotice.asFile.get(),
    )
}

internal fun verifyProtectedCandidateManifest(manifest: File, inputs: CandidateInputFiles) {
    check(manifest.readReleaseObject() == buildCandidateManifest(inputs)) {
        "Candidate manifest does not match the exact current artifacts, evidence, and policies"
    }
}

private fun ConfigurableFileCollection.sorted() = files.sortedBy(File::getName)
