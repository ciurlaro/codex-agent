import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateCandidateManifestTask : DefaultTask() {
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
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val windowsSupervisorSource: org.gradle.api.file.DirectoryProperty
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
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        outputFile.get().asFile.atomicWriteJson(buildCandidateManifest(CandidateInputFiles(
            version = candidateVersion.get(), releaseTag = releaseTag.get(), commit = candidateCommit.get(),
            swiftZip = swiftZip.get().asFile, swiftChecksum = swiftChecksum.get().asFile,
            swiftPmProof = swiftPmProof.get().asFile, centralBundle = centralBundle.get().asFile,
            centralInventory = centralInventory.get().asFile, mavenInventory = mavenInventory.get().asFile,
            kmpConsumer = kmpConsumer.get().asFile, desktopEvidence = desktopEvidence.files.sortedBy { it.name },
            nodeEvidence = nodeEvidence.files.sortedBy { it.name },
            nodeClassifierArchives = nodeClassifierArchives.files.sortedBy { it.name },
            nodeRuntimeRunner = nodeRuntimeRunner.get().asFile,
            windowsSupervisorPackage = windowsSupervisorPackage.get().asFile,
            windowsSupervisorIdentity = windowsSupervisorIdentity.get().asFile,
            windowsSupervisorExecutable = windowsSupervisorExecutable.get().asFile,
            windowsSupervisorSource = windowsSupervisorSource.get().asFile,
            iosNativeEvidence = iosNativeEvidence.get().asFile, privacyAudit = privacyAudit.get().asFile,
            artifactMetrics = artifactMetrics.get().asFile,
            resourceReports = resourceReports.files.sortedBy { it.name }, approvals = approvalsFile.get().asFile,
            privacyManifest = privacyManifest.get().asFile, privacyDataFlowReview = privacyDataFlowReview.get().asFile,
            privacyRequiredReasonReviews = privacyReviews.orNull?.asFile, packageSwift = packageSwift.get().asFile,
            desktopDistributionManifest = desktopDistributionManifest.get().asFile,
            desktopBundledLicense = desktopBundledLicense.get().asFile,
            desktopBundledNotice = desktopBundledNotice.get().asFile,
        )))
    }
}
