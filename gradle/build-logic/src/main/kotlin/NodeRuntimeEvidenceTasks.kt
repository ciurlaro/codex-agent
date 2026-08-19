import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Exact-commit Node evidence must execute on every target runner")
abstract class RecordNodeRuntimeEvidenceTask : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val target: Property<String>
    @get:Input abstract val runtimeBackend: Property<String>
    @get:Input abstract val runnerOs: Property<String>
    @get:Input abstract val runnerArch: Property<String>
    @get:Input abstract val nodeExecutable: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val distributionManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val classifierArchive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiledNodeTestRuntime: RegularFileProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty
    @get:OutputFile abstract val testReport: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun record() = executeNodeRuntimeEvidence(
        candidateCommit.get(),
        target.get(),
        runtimeBackend.get(),
        runnerOs.get(),
        runnerArch.get(),
        nodeExecutable.get(),
        distributionManifest.get().asFile,
        classifierArchive.get().asFile,
        compiledNodeTestRuntime.get().asFile,
        evidenceFile.get().asFile,
        testReport.get().asFile,
    )
}

@CacheableTask
abstract class VerifyNodeRuntimeEvidenceTask @Inject constructor() : DefaultTask() {
    @get:Input abstract val expectedCommit: Property<String>
    @get:Input abstract val runtimeBackend: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val evidenceFiles: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val distributionManifest: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val classifierArchives: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiledNodeTestRuntime: RegularFileProperty
    @get:OutputFile abstract val verificationFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val records = evidenceFiles.files.toList()
        val backend = runtimeBackend.get()
        val errors = validateNodeRuntimeEvidence(
            records,
            expectedCommit.get(),
            backend,
            distributionManifest.get().asFile,
            classifierArchives.files.toList(),
            compiledNodeTestRuntime.get().asFile,
        )
        check(errors.isEmpty()) { "Node runtime evidence is invalid: ${errors.joinToString()}" }
        verificationFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(2))
            put("result", JsonPrimitive("passed"))
            put("candidateCommit", JsonPrimitive(expectedCommit.get()))
            put("runtimeBackend", JsonPrimitive(backend))
            put("nodeVersion", JsonPrimitive(PINNED_NODE_VERSION))
            put("targets", buildJsonArray {
                desktopRuntimeEvidenceTargets.keys.forEach { add(JsonPrimitive(it)) }
            })
            put("evidence", buildJsonArray {
                records.sortedBy { it.name }.forEach { add(it.releaseRecord()) }
            })
            put("compiledNodeTestRuntimeSha256", JsonPrimitive(
                compiledNodeTestRuntime.get().asFile.releaseDigest(),
            ))
        })
    }
}
