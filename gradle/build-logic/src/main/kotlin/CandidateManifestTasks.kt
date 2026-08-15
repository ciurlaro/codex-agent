import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class CandidateManifestInputsTask : DefaultTask() {
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
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val iosRuntimeMetrics: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val approvalsFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyDataFlowReview: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyReviews: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val packageSwift: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopDistributionManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopBundledLicense: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopBundledNotice: RegularFileProperty
    internal fun candidateInputs() = CandidateInputFiles(
        candidateVersion.get(), releaseTag.get(), candidateCommit.get(),
        swiftZip.asFile.get(), swiftChecksum.asFile.get(), swiftPmProof.asFile.get(),
        centralBundle.asFile.get(), centralInventory.asFile.get(), mavenInventory.asFile.get(),
        kmpConsumer.asFile.get(), ciProvenance.asFile.get(), desktopEvidence.sorted(), desktopClassifierArchives.sorted(),
        jvmEvidence.sorted(), jvmRuntimeRunner.asFile.get(), nodeEvidence.sorted(),
        nodeRuntimeRunner.asFile.get(), nodeWasmEvidence.sorted(), nodeWasmRuntimeRunner.asFile.get(),
        androidEvidence.sorted(), iosNativeEvidence.asFile.get(), privacyAudit.asFile.get(),
        artifactMetrics.asFile.get(), iosRuntimeMetrics.asFile.get(), approvalsFile.asFile.get(),
        privacyManifest.asFile.get(), privacyDataFlowReview.asFile.get(), privacyReviews.orNull?.asFile,
        packageSwift.asFile.get(), desktopDistributionManifest.asFile.get(),
        desktopBundledLicense.asFile.get(), desktopBundledNotice.asFile.get(),
    )
}

@CacheableTask
abstract class GenerateCandidateManifestTask : CandidateManifestInputsTask() {
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        outputFile.get().asFile.atomicWriteJson(buildCandidateManifest(candidateInputs()))
    }
}

private fun ConfigurableFileCollection.sorted() = files.sortedBy { it.name }
