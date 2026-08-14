import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Exports fresh exact-main verification evidence")
abstract class ExportAppleVerifiedDistributionTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Input abstract val freshSemanticVerification: Property<Boolean>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val applePackageArchive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftPackageArchive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftPackageChecksum: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyReviewFile: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val reportFiles: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val xcodeVersionFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftVersionFile: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) abstract val nativeEvidenceDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val nativeEvidenceReceipt: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val nativeProvenance: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val packageSwift: RegularFileProperty
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:Internal abstract val canonicalBuildDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun export() {
        check(freshSemanticVerification.get()) { "Verified Apple distribution cannot be re-exported from imported evidence" }
        val repository = repositoryDirectory.get().asFile.canonicalFile
        val (commit, tree) = verifyAppleEvidenceCheckout(exec, repository, candidateCommit.get())
        val output = outputDirectory.get().asFile
        deleteReleaseTree(output); output.mkdirs()
        val artifacts = linkedMapOf(
            applePackageArchive.get().asFile.name to copyVerified(applePackageArchive.get().asFile, output),
            swiftPackageArchive.get().asFile.name to copyVerified(swiftPackageArchive.get().asFile, output),
            swiftPackageChecksum.get().asFile.name to copyVerified(swiftPackageChecksum.get().asFile, output),
        )
        val build = canonicalBuildDirectory.get().asFile.canonicalFile
        val reports = appleVerifiedReportLayout.mapValues { (destination, source) ->
            val input = if (destination.endsWith("privacy-required-reason-review.json"))
                privacyReviewFile.get().asFile else build.resolve(source)
            copyVerified(input, output.resolve(destination))
        }
        check(reportFiles.files.map(File::getCanonicalFile).toSet() ==
            appleVerifiedReportLayout.map { (destination, source) ->
                if (destination.endsWith("privacy-required-reason-review.json"))
                    privacyReviewFile.get().asFile.canonicalFile else build.resolve(source).canonicalFile
            }.toSet()) { "Verified Apple report inputs do not match the canonical report layout" }
        val toolchain = linkedMapOf(
            "toolchain/xcode.txt" to copyVerified(xcodeVersionFile.get().asFile, output.resolve("toolchain/xcode.txt")),
            "toolchain/swift.txt" to copyVerified(swiftVersionFile.get().asFile, output.resolve("toolchain/swift.txt")),
        )
        val nativeEvidence = verifiedRegularFiles(nativeEvidenceDirectory.get().asFile)
        val identity = AppleVerifiedDistributionIdentity(
            commit, tree, version.get(), nativeProvenance.get().asFile.releaseDigest(),
            packageSwift.get().asFile.releaseDigest(), nativeEvidenceReceipt.get().asFile.releaseDigest(),
        )
        output.resolve(IOS_VERIFIED_DISTRIBUTION_PROOF).atomicWriteJson(
            buildAppleVerifiedDistributionProof(identity, artifacts, reports, toolchain, nativeEvidence),
        )
    }
}

@DisableCachingByDefault(because = "Validates transported evidence and restores canonical candidate inputs")
abstract class ImportAppleVerifiedDistributionTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val version: Property<String>
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) abstract val evidenceDirectory: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.NONE) abstract val nativeEvidenceDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val nativeEvidenceReceipt: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val nativeProvenance: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val packageSwift: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val currentXcodeVersionFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val currentSwiftVersionFile: RegularFileProperty
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:Internal abstract val canonicalBuildDirectory: DirectoryProperty
    @get:OutputFile abstract val verificationReceipt: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun importEvidence() {
        val repository = repositoryDirectory.get().asFile.canonicalFile
        val (commit, tree) = verifyAppleEvidenceCheckout(exec, repository, candidateCommit.get())
        val identity = AppleVerifiedDistributionIdentity(
            commit, tree, version.get(), nativeProvenance.get().asFile.releaseDigest(),
            packageSwift.get().asFile.releaseDigest(), nativeEvidenceReceipt.get().asFile.releaseDigest(),
        )
        val inventory = verifyAppleVerifiedDistribution(
            evidenceDirectory.get().asFile, nativeEvidenceDirectory.get().asFile, identity,
        )
        check(Files.mismatch(
            currentXcodeVersionFile.get().asFile.toPath(), inventory.toolchain.getValue("toolchain/xcode.txt").toPath(),
        ) == -1L) { "Imported Apple distribution Xcode identity mismatch" }
        check(Files.mismatch(
            currentSwiftVersionFile.get().asFile.toPath(), inventory.toolchain.getValue("toolchain/swift.txt").toPath(),
        ) == -1L) { "Imported Apple distribution Swift identity mismatch" }
        val build = canonicalBuildDirectory.get().asFile
        inventory.artifacts.forEach { (path, file) -> copyVerified(file, build.resolve("distributions/$path")) }
        inventory.reports.forEach { (path, file) ->
            copyVerified(file, build.resolve(appleVerifiedReportLayout.getValue(path)))
        }
        inventory.toolchain.forEach { (path, file) ->
            copyVerified(file, build.resolve(appleVerifiedToolchainLayout.getValue(path)))
        }
        verificationReceipt.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("protocol", JsonPrimitive("codex-agent-ios-verified-distribution-import-v1"))
            put("result", JsonPrimitive("passed"))
            put("candidateCommit", JsonPrimitive(commit))
            put("candidateTree", JsonPrimitive(tree))
            put("sourceProofSha256", JsonPrimitive(inventory.proof.releaseDigest()))
            put("nativeEvidenceReceiptSha256", JsonPrimitive(identity.nativeEvidenceReceiptSha256))
        })
    }
}

private fun copyVerified(source: File, destination: File): File {
    check(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Verified Apple input is missing or unsafe: $source" }
    destination.parentFile.mkdirs()
    Files.copy(source.toPath(), destination.toPath(), REPLACE_EXISTING)
    return destination
}
