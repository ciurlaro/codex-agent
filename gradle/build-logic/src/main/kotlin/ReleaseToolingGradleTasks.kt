import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

internal fun ExecOperations.captureReleaseProcess(
    command: List<String>,
    workingDirectory: File? = null,
    environmentVariables: Map<String, String> = mapOf("LC_ALL" to "C", "LANG" to "C"),
): String {
    val output = ByteArrayOutputStream()
    val errors = ByteArrayOutputStream()
    val result = exec {
        commandLine(command)
        workingDirectory?.let(::workingDir)
        environment(environmentVariables)
        standardOutput = output
        errorOutput = errors
        isIgnoreExitValue = true
    }
    return requireSuccessfulReleaseProcess(
        command,
        result.exitValue,
        output.toString(Charsets.UTF_8.name()),
        errors.toString(Charsets.UTF_8.name()),
    )
}

@DisableCachingByDefault(because = "Verifies the installed Apple toolchain")
abstract class VerifyAppleToolchainTask @Inject constructor(private val processes: ExecOperations) : DefaultTask() {
    @get:Input abstract val expectedXcodeVersion: Property<String>
    @get:Input abstract val expectedXcodeBuild: Property<String>
    @get:Input abstract val expectedSwiftVersion: Property<String>
    @get:OutputDirectory abstract val reportDirectory: DirectoryProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun verify() {
        val xcode = processes.captureReleaseProcess(listOf("xcodebuild", "-version"))
        val swift = processes.captureReleaseProcess(listOf("swift", "--version"))
        verifyAppleToolchainOutput(
            xcode, swift, expectedXcodeVersion.get(), expectedXcodeBuild.get(), expectedSwiftVersion.get(),
        )
        reportDirectory.file("xcode.txt").get().asFile.apply { parentFile.mkdirs(); writeText(xcode) }
        reportDirectory.file("swift.txt").get().asFile.writeText(swift)
    }
}

@DisableCachingByDefault(because = "Available disk space is live host state")
abstract class VerifyIosFreeDiskSpaceTask : DefaultTask() {
    @get:Input abstract val minimumFreeGiB: Property<Long>
    @get:Internal abstract val workspaceDirectory: DirectoryProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun verify() {
        val availableBytes = Files.getFileStore(workspaceDirectory.get().asFile.toPath()).usableSpace
        val minimum = minimumFreeGiB.get()
        val requiredBytes = Math.multiplyExact(minimum, IOS_GIBIBYTE_BYTES)
        reportFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("availableBytes", JsonPrimitive(availableBytes))
            put("requiredBytes", JsonPrimitive(requiredBytes))
        })
        requireIosFreeDiskSpace(availableBytes, minimum)
        logger.lifecycle(
            "iOS preflight passed: ${availableBytes / IOS_GIBIBYTE_BYTES} GiB free; $minimum GiB required",
        )
    }
}

@DisableCachingByDefault(because = "Inspects binary metadata with the selected Xcode toolchain")
abstract class VerifyIosDeploymentTargetsTask @Inject constructor(private val processes: ExecOperations) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val xcframeworkDirectory: DirectoryProperty
    @get:Input abstract val minimumIosVersion: Property<String>
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun verify() {
        val root = xcframeworkDirectory.get().asFile
        val binaries = Files.walk(root.toPath()).use { paths ->
            paths.filter(Files::isRegularFile)
                .filter { it.fileName.toString() == "CodexAgent" && it.parent.fileName.toString() == "CodexAgent.framework" }
                .map { it.toFile() }.sorted().toList()
        }
        check(binaries.size == 2) { "Expected exactly two XCFramework binaries, found ${binaries.size}" }
        val report = buildString {
            binaries.forEach { binary ->
                val path = binary.invariantSeparatorsPath
                val expectedPlatform = when {
                    "/ios-arm64-simulator/" in path -> 7
                    "/ios-arm64/" in path -> 2
                    else -> error("Unexpected XCFramework slice: $path")
                }
                val records = parseDeploymentTargets(
                    processes.captureReleaseProcess(listOf("/usr/bin/xcrun", "otool", "-l", binary.absolutePath)),
                )
                verifyDeploymentTargets(records, expectedPlatform, minimumIosVersion.get())
                val plist = binary.parentFile.resolve("Info.plist")
                val plistMinimum = processes.captureReleaseProcess(
                    listOf("/usr/bin/xcrun", "plutil", "-extract", "MinimumOSVersion", "raw", "-o", "-", plist.absolutePath),
                ).trim()
                check(plistMinimum == minimumIosVersion.get()) { "Framework Info.plist deployment target mismatch" }
                append("== ${binary.absolutePath} ==\n")
                records.map { "${it.platform}:${it.minimum}" }.toSortedSet().forEach { append(it).append('\n') }
            }
        }
        check("2:${minimumIosVersion.get()}" in report && "7:${minimumIosVersion.get()}" in report) {
            "Device and simulator deployment targets were not both verified"
        }
        reportFile.get().asFile.apply { parentFile.mkdirs(); writeText(report) }
    }
}

@DisableCachingByDefault(because = "Measures release artifacts produced by Xcode and Gradle")
abstract class VerifyIosReleaseBudgetsTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val policyFile: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val importedReport: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val archiveFile: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val deviceBinary: RegularFileProperty
    @get:Optional @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val applicationDirectory: DirectoryProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun verify() {
        val metrics = if (importedReport.isPresent) {
            val report = importedReport.get().asFile.readReleaseObject()
            check(report.keys == setOf(
                "compressedXcframeworkBytes", "deviceFrameworkBytes", "sampleAppInstallBytes",
            )) { "Imported Apple artifact metrics schema mismatch" }
            AppleArtifactMetrics(
                report.releaseLong("compressedXcframeworkBytes"),
                report.releaseLong("deviceFrameworkBytes"),
                report.releaseLong("sampleAppInstallBytes"),
            )
        } else {
            measureAppleArtifacts(
                archiveFile.get().asFile, deviceBinary.get().asFile, applicationDirectory.get().asFile,
            )
        }
        verifyAppleArtifactBudgets(metrics, policyFile.get().asFile)
        reportFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("compressedXcframeworkBytes", JsonPrimitive(metrics.compressedXcframeworkBytes))
            put("deviceFrameworkBytes", JsonPrimitive(metrics.deviceFrameworkBytes))
            put("sampleAppInstallBytes", JsonPrimitive(metrics.sampleAppInstallBytes))
        })
    }
}

@DisableCachingByDefault(because = "This task uploads one exact remote deployment")
abstract class PrepareCentralDeploymentTask : CentralPortalTask() {
    @get:Input abstract val allowNewUpload: Property<Boolean>
    @get:OutputFile abstract override val deploymentRecord: RegularFileProperty
    @TaskAction fun prepare() = prepareCentralDeployment(
        bundleFile.get().asFile, candidateManifest.get().asFile, deploymentRecord.get().asFile,
        apiBaseUrl.get(), username.orNull.orEmpty(), password.orNull.orEmpty(), allowNewUpload.get(),
    )
}

@DisableCachingByDefault(because = "This task polls a remote deployment")
abstract class AwaitCentralValidationTask : CentralPortalTask() {
    @get:Internal abstract override val deploymentRecord: RegularFileProperty
    @TaskAction fun await() = awaitCentralValidation(
        bundleFile.get().asFile, candidateManifest.get().asFile, deploymentRecord.get().asFile,
        apiBaseUrl.get(), username.orNull.orEmpty(), password.orNull.orEmpty(),
    )
}

@DisableCachingByDefault(because = "This task releases and polls a remote deployment")
abstract class ReleaseCentralDeploymentTask : CentralPortalTask() {
    @get:Internal abstract override val deploymentRecord: RegularFileProperty
    @TaskAction fun release() = releaseCentralDeployment(
        bundleFile.get().asFile, candidateManifest.get().asFile, deploymentRecord.get().asFile,
        apiBaseUrl.get(), username.orNull.orEmpty(), password.orNull.orEmpty(),
    )
}

abstract class CentralPortalTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val bundleFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val candidateManifest: RegularFileProperty
    @get:Internal abstract val deploymentRecord: RegularFileProperty
    @get:Input abstract val apiBaseUrl: Property<String>
    @get:Internal abstract val username: Property<String>
    @get:Internal abstract val password: Property<String>

    init {
        apiBaseUrl.convention(project.providers.environmentVariable("CENTRAL_PORTAL_API").orElse(DEFAULT_CENTRAL_PORTAL_API))
        username.convention(project.providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
        password.convention(project.providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
    }
}

fun Project.registerCentralPortalTasks() {
    val bundle = layout.file(providers.gradleProperty("codexAgent.centralBundle").map(::file))
    val candidate = layout.file(providers.gradleProperty("codexAgent.candidateManifest").map(::file))
    val record = layout.file(providers.gradleProperty("codexAgent.centralDeploymentRecord").map(::file))
    tasks.register("prepareCentralDeployment", PrepareCentralDeploymentTask::class.java) {
        group = "publishing"
        description = "Uploads the exact candidate bundle once as USER_MANAGED and records its deployment ID."
        bundleFile.set(bundle); candidateManifest.set(candidate); deploymentRecord.set(record)
        allowNewUpload.set(providers.gradleProperty("codexAgent.allowCentralUpload").map(String::toBoolean).orElse(false))
    }
    tasks.register("awaitCentralValidation", AwaitCentralValidationTask::class.java) {
        group = "publishing"
        description = "Waits for the recorded exact Central deployment to validate."
        bundleFile.set(bundle); candidateManifest.set(candidate); deploymentRecord.set(record)
    }
    tasks.register("releaseCentralDeployment", ReleaseCentralDeploymentTask::class.java) {
        group = "publishing"
        description = "Releases the recorded validated Central deployment and waits for PUBLISHED."
        bundleFile.set(bundle); candidateManifest.set(candidate); deploymentRecord.set(record)
    }
}

@DisableCachingByDefault(because = "Platform smoke evidence must execute for every immutable candidate")
abstract class RecordDesktopRuntimeEvidenceTask : DefaultTask() {
    @get:Input abstract val target: Property<String>
    @get:Input abstract val classifier: Property<String>
    @get:Input abstract val binarySha256: Property<String>
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val runnerOs: Property<String>
    @get:Input abstract val runnerArch: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val classifierArchive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val distributionManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val testReport: RegularFileProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun record() {
        val targetName = target.get()
        val commit = candidateCommit.get()
        check(commit.matches(Regex("[0-9a-f]{40}"))) { "Desktop evidence commit is not immutable" }
        verifyDesktopRuntimeTestReport(testReport.get().asFile, targetName)
        val expected = desktopRuntimeEvidenceTargets.getValue(targetName)
        check(classifier.get() == expected.classifier) { "Desktop evidence classifier mismatch" }
        check(runnerOs.get() == expected.runnerOs && runnerArch.get() == expected.runnerArch) {
            "Desktop evidence runner does not match $targetName"
        }
        val proof = inspectDesktopClassifier(
            targetName,
            readDesktopCodexManifest(distributionManifest.get().asFile),
            classifierArchive.get().asFile,
        )
        check(binarySha256.get() == proof.binarySha256) { "Desktop evidence App Server hash mismatch" }
        evidenceFile.get().asFile.atomicWriteJson(buildDesktopRuntimeEvidence(DesktopRuntimeEvidenceValues(
            commit,
            targetName,
            proof.binarySha256,
            proof.supervisorSha256,
            proof.archiveSha256,
        )))
    }
}

@DisableCachingByDefault(because = "Relocation POMs are tiny deterministic publication primaries")
abstract class GenerateMavenRelocationPomsTask : DefaultTask() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @get:Input abstract val newGroup: Property<String>
    @get:Input abstract val version: Property<String>

    @TaskAction
    fun generate() = generateMavenRelocationPoms(outputDirectory.get().asFile, newGroup.get(), version.get())
}

@DisableCachingByDefault(because = "Checksums are materialized into the isolated staged repository")
abstract class VerifyMavenStagingTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty
    @get:Input abstract val groupId: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Input abstract val requireSignatures: Property<Boolean>
    @get:OutputFile abstract val inventoryFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun verify() = verifyMavenRepository(
        repositoryDirectory.get().asFile,
        groupId.get(),
        version.get(),
        requireSignatures.get(),
        inventoryFile.get().asFile,
    )
}

@CacheableTask
abstract class VerifyCandidatePayloadTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifestFile: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val payloadDirectory: DirectoryProperty
    @get:Input abstract val expectedVersion: Property<String>
    @get:Input abstract val expectedTag: Property<String>
    @get:Input abstract val expectedCommit: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val approvalsFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyDataFlowReview: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val iosResourcePolicy: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyReviewTemplate: RegularFileProperty
    @get:Optional @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyReviews: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val packageSwift: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopDistributionManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopBundledLicense: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopBundledNotice: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @get:Optional @get:OutputFile abstract val githubOutputFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val manifest = manifestFile.get().asFile.readReleaseObject()
        val payload = payloadDirectory.get().asFile
        val exactReview = resolveCandidatePrivacyReview(
            manifest, payload, privacyReviews.orNull?.asFile, privacyReviewTemplate.orNull?.asFile,
        )
        val result = verifyCandidatePayload(
            manifestFile.get().asFile,
            payload,
            expectedVersion.get(),
            expectedTag.get(),
            expectedCommit.get(),
            buildMap {
                put("approvals", approvalsFile.get().asFile)
                put("privacyManifest", privacyManifest.get().asFile)
                put("privacyDataFlowReview", privacyDataFlowReview.get().asFile)
                if (manifest.releaseInt("schemaVersion") == PROMOTED_CANDIDATE_SCHEMA) {
                    put("iosResourcePolicy", iosResourcePolicy.get().asFile)
                }
                exactReview?.let { put("privacyRequiredReasonReviews", it) }
                put("packageSwift", packageSwift.get().asFile)
                put("desktopDistributionManifest", desktopDistributionManifest.get().asFile)
                put("desktopBundledLicense", desktopBundledLicense.get().asFile)
                put("desktopBundledNotice", desktopBundledNotice.get().asFile)
            },
        )
        outputFile.get().asFile.atomicWriteJson(result)
        githubOutputFile.orNull?.asFile?.let { file ->
            file.parentFile.mkdirs()
            file.writeText(candidateGithubOutputs(result))
        }
    }
}

@DisableCachingByDefault(because = "The task forwards exact promoted Maven primaries")
abstract class StagePromotedMavenPrimariesTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val promotedArtifactsDirectory: DirectoryProperty
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val candidateVersion: Property<String>
    @get:OutputDirectory abstract val mavenRepository: DirectoryProperty

    @TaskAction
    fun stage() = stageCanonicalPromotedMavenPrimaries(
        promotedArtifactsDirectory.get().asFile,
        candidateCommit.get(),
        candidateVersion.get(),
        mavenRepository.get().asFile,
    )
}

@DisableCachingByDefault(because = "The protected candidate signs and inventories one immutable promotion")
abstract class AssemblePromotedCandidateTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val promotedArtifactsDirectory: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val signedMavenRepository: DirectoryProperty
    @get:Input abstract val candidateVersion: Property<String>
    @get:Input abstract val releaseTag: Property<String>
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val candidateTree: Property<String>
    @get:Input abstract val promotionRunId: Property<Long>
    @get:Input abstract val promotionRunAttempt: Property<Int>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val approvalsFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyDataFlowReview: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyReviewTemplate: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val iosResourcePolicy: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val packageSwift: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val remoteConsumerManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopDistributionManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopBundledLicense: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val desktopBundledNotice: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val releaseTooling: RegularFileProperty
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:OutputDirectory abstract val payloadDirectory: DirectoryProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun assemble() = assemblePromotedCandidate(PromotedCandidateInputs(
        promotedArtifactsDirectory.get().asFile,
        signedMavenRepository.get().asFile,
        candidateVersion.get(),
        releaseTag.get(),
        candidateCommit.get(),
        candidateTree.get(),
        promotionRunId.get(),
        promotionRunAttempt.get(),
        approvalsFile.get().asFile,
        privacyManifest.get().asFile,
        privacyDataFlowReview.get().asFile,
        privacyReviewTemplate.get().asFile,
        iosResourcePolicy.get().asFile,
        packageSwift.get().asFile,
        remoteConsumerManifest.get().asFile,
        desktopDistributionManifest.get().asFile,
        desktopBundledLicense.get().asFile,
        desktopBundledNotice.get().asFile,
        releaseTooling.get().asFile,
        repositoryDirectory.get().asFile,
        payloadDirectory.get().asFile,
    ))
}

@DisableCachingByDefault(because = "This task proves an isolated nested KMP build")
abstract class VerifyStagedKmpConsumerTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val templateDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val mavenInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val gradleWrapper: RegularFileProperty
    @get:Input abstract val projectVersion: Property<String>
    @get:Input abstract val androidSdkDirectory: Property<String>
    @get:Input abstract val targetName: Property<String>
    @get:Input abstract val buildTasks: ListProperty<String>
    @get:LocalState abstract val consumerDirectory: DirectoryProperty
    @get:OutputFile abstract val resultFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val consumer = consumerDirectory.get().asFile
        val repository = repositoryDirectory.get().asFile
        prepareStagedConsumer(templateDirectory.get().asFile, consumer, androidSdkDirectory.get())
        val requestedTasks = buildTasks.get()
        val outcomeInitScript = consumer.resolve(".codex-consumer-task-outcomes.init.gradle.kts").apply {
            writeText(stagedConsumerOutcomeInitScript(requestedTasks))
        }
        val arguments = stagedConsumerArguments(
            consumer, repository, projectVersion.get(), targetName.get(), requestedTasks, outcomeInitScript,
        )
        exec.exec {
            workingDir(consumer)
            executable(gradleWrapper.get().asFile.absolutePath)
            args(arguments)
        }.assertNormalExitValue()
        resultFile.get().asFile.atomicWriteJson(buildJsonObject {
            val inventory = mavenInventory.get().asFile.readReleaseObject()
            put("schemaVersion", JsonPrimitive(5))
            put("result", JsonPrimitive("passed"))
            put("version", JsonPrimitive(projectVersion.get()))
            put("repository", JsonPrimitive("CENTRAL_STAGING-only"))
            put("mavenGroup", JsonPrimitive(inventory.releaseString("groupId")))
            put("target", JsonPrimitive(targetName.get()))
            put("tasks", buildJsonArray { requestedTasks.forEach { add(JsonPrimitive(it)) } })
        })
    }
}

@DisableCachingByDefault(because = "This task verifies exact target receipts from isolated nested builds")
abstract class AggregateStagedKmpConsumerTask : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val targetResults: ConfigurableFileCollection
    @get:Input abstract val projectVersion: Property<String>
    @get:OutputFile abstract val resultFile: RegularFileProperty

    @TaskAction
    fun aggregate() {
        val results = targetResults.files.associate { file ->
            val value = file.readReleaseObject()
            check(value.releaseString("result") == "passed") { "Staged consumer target failed: ${file.name}" }
            check(value.releaseString("version") == projectVersion.get()) { "Staged consumer version mismatch" }
            value.releaseString("target") to file
        }
        check(results.keys == stagedConsumerBuildTasks.keys) {
            "Staged consumer target set mismatch: expected=${stagedConsumerBuildTasks.keys} actual=${results.keys}"
        }
        resultFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(5))
            put("result", JsonPrimitive("passed"))
            put("version", JsonPrimitive(projectVersion.get()))
            put("targets", buildJsonArray { results.keys.sorted().forEach { add(JsonPrimitive(it)) } })
        })
    }
}
