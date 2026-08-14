import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.AtomicMoveNotSupportedException
import java.util.zip.ZipFile
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.Optional
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
    val nodeEvidence: List<File>,
    val iosNativeEvidenceDirectory: File,
    val windowsSupervisorPackage: File,
    val windowsSupervisorIdentity: File,
    val windowsSupervisorSource: File,
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
    val desktopErrors = validateDesktopRuntimeEvidence(input.desktopEvidence, input.commit)
    check(desktopErrors.isEmpty()) { "Desktop runtime evidence is invalid: ${desktopErrors.joinToString()}" }
    val expectedNodeEvidence = desktopRuntimeEvidenceTargets.keys.map(::nodeRuntimeEvidenceFileName).toSet()
    check(input.nodeEvidence.size == expectedNodeEvidence.size &&
        input.nodeEvidence.map(File::getName).toSet() == expectedNodeEvidence && input.nodeEvidence.all(File::isFile)) {
        "Exactly five target-specific Node runtime evidence files are required"
    }
    check(input.iosNativeEvidenceDirectory.isDirectory) { "iOS native evidence directory is required" }
    verifyWindowsSupervisorPackage(
        input.windowsSupervisorPackage,
        input.windowsSupervisorIdentity,
        input.windowsSupervisorSource,
    )

    val repository = input.repository.canonicalFile
    val candidate = input.candidateDirectory.canonicalFile
    check(candidate == repository.resolve("build/protected-candidate/${input.commit}").canonicalFile) {
        "Protected candidate output must use the commit-isolated build directory"
    }
    check(!candidate.exists()) {
        "Protected candidate output already exists; refusing to clean or rebuild it: $candidate"
    }
    check(input.desktopEvidence.none { it.canonicalFile.toPath().startsWith(candidate.toPath()) }) {
        "Desktop evidence must be external to protected candidate output"
    }
    check(input.nodeEvidence.none { it.canonicalFile.toPath().startsWith(candidate.toPath()) }) {
        "Node evidence must be external to protected candidate output"
    }
    check(!input.iosNativeEvidenceDirectory.canonicalFile.toPath().startsWith(candidate.toPath())) {
        "iOS native evidence must be external to protected candidate output"
    }
    check(listOf(input.windowsSupervisorPackage, input.windowsSupervisorIdentity, input.windowsSupervisorSource)
        .none { it.canonicalFile.toPath().startsWith(candidate.toPath()) }) {
        "Windows supervisor inputs must be external to protected candidate output"
    }
    listOf("artifacts", "evidence", "maven-repository", "clean-consumer", "reports")
        .forEach { candidate.resolve(it).mkdirs() }
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
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val nodeEvidence: ConfigurableFileCollection
    @get:org.gradle.api.tasks.InputDirectory @get:PathSensitive(PathSensitivity.NONE)
    abstract val iosNativeEvidenceDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val windowsSupervisorPackage: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val windowsSupervisorIdentity: RegularFileProperty
    @get:org.gradle.api.tasks.InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val windowsSupervisorSource: DirectoryProperty
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:Internal abstract val candidateDirectory: DirectoryProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun prepare() = prepareProtectedCandidateDirectory(ProtectedCandidatePreflight(
        version.get(), releaseTag.get(), candidateCommit.get(), git("rev-parse", "HEAD^{commit}"),
        git(*protectedCandidateStatusArguments.toTypedArray()), parallelExecution.get(),
        repositoryDirectory.get().asFile, candidateDirectory.get().asFile,
        desktopEvidence.files.sortedBy(File::getName), nodeEvidence.files.sortedBy(File::getName),
        iosNativeEvidenceDirectory.get().asFile, windowsSupervisorPackage.get().asFile,
        windowsSupervisorIdentity.get().asFile, windowsSupervisorSource.get().asFile,
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

internal fun extractCandidateWindowsSupervisor(
    packageFile: File,
    identityFile: File,
    sourceDirectory: File,
    candidateDirectory: File,
    outputFile: File,
) {
    val identity = verifyWindowsSupervisorPackage(packageFile, identityFile, sourceDirectory)
    val candidate = candidateDirectory.canonicalFile
    val output = outputFile.canonicalFile
    check(output == candidate.resolve("reports/$WINDOWS_SUPERVISOR_FILE_NAME").canonicalFile) {
        "Extracted Windows supervisor must remain inside candidate reports"
    }
    output.parentFile.mkdirs()
    val temporary = Files.createTempFile(output.parentFile.toPath(), ".windows-supervisor-", ".tmp")
    try {
        ZipFile(packageFile).use { zip ->
            zip.getInputStream(zip.getEntry(WINDOWS_SUPERVISOR_FILE_NAME)).use { input ->
                Files.copy(input, temporary, REPLACE_EXISTING)
            }
        }
        try {
            Files.move(temporary, output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, output.toPath(), REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
    verifyWindowsSupervisorIdentity(identity, output, sourceDirectory)
}

@CacheableTask
abstract class ExtractCandidateWindowsSupervisorTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val packageFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val identityFile: RegularFileProperty
    @get:org.gradle.api.tasks.InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty
    @get:Internal abstract val candidateDirectory: DirectoryProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun extract() = extractCandidateWindowsSupervisor(
        packageFile.get().asFile,
        identityFile.get().asFile,
        sourceDirectory.get().asFile,
        candidateDirectory.get().asFile,
        outputFile.get().asFile,
    )
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
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftPmProof: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val centralBundle: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val centralInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mavenInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val kmpConsumer: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val desktopEvidence: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val nodeEvidence: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val nodeClassifierArchives: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val nodeRuntimeRunner: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val windowsSupervisorPackage: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val windowsSupervisorIdentity: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val windowsSupervisorExecutable: RegularFileProperty
    @get:org.gradle.api.tasks.InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val windowsSupervisorSource: DirectoryProperty
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
    fun verify() = verifyProtectedCandidateManifest(manifestFile.get().asFile, candidateInputs())

    internal fun candidateInputs() = CandidateInputFiles(
        version = candidateVersion.get(), releaseTag = releaseTag.get(), commit = candidateCommit.get(),
        swiftZip = swiftZip.get().asFile, swiftChecksum = swiftChecksum.get().asFile,
        swiftPmProof = swiftPmProof.get().asFile, centralBundle = centralBundle.get().asFile,
        centralInventory = centralInventory.get().asFile, mavenInventory = mavenInventory.get().asFile,
        kmpConsumer = kmpConsumer.get().asFile,
        desktopEvidence = desktopEvidence.files.sortedBy(File::getName),
        nodeEvidence = nodeEvidence.files.sortedBy(File::getName),
        nodeClassifierArchives = nodeClassifierArchives.files.sortedBy(File::getName),
        nodeRuntimeRunner = nodeRuntimeRunner.get().asFile,
        windowsSupervisorPackage = windowsSupervisorPackage.get().asFile,
        windowsSupervisorIdentity = windowsSupervisorIdentity.get().asFile,
        windowsSupervisorExecutable = windowsSupervisorExecutable.get().asFile,
        windowsSupervisorSource = windowsSupervisorSource.get().asFile,
        iosNativeEvidence = iosNativeEvidence.get().asFile,
        privacyAudit = privacyAudit.get().asFile, artifactMetrics = artifactMetrics.get().asFile,
        resourceReports = resourceReports.files.sortedBy(File::getName),
        approvals = approvalsFile.get().asFile, privacyManifest = privacyManifest.get().asFile,
        privacyDataFlowReview = privacyDataFlowReview.get().asFile,
        privacyRequiredReasonReviews = privacyReviews.orNull?.asFile, packageSwift = packageSwift.get().asFile,
        desktopDistributionManifest = desktopDistributionManifest.get().asFile,
        desktopBundledLicense = desktopBundledLicense.get().asFile,
        desktopBundledNotice = desktopBundledNotice.get().asFile,
    )
}

internal fun verifyProtectedCandidateManifest(manifest: File, inputs: CandidateInputFiles) {
    check(manifest.readReleaseObject() == buildCandidateManifest(inputs)) {
        "Candidate manifest does not match the exact current artifacts, evidence, and policies"
    }
}
