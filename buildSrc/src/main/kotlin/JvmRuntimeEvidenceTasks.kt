import java.io.File
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

@DisableCachingByDefault(because = "Exact-commit JVM evidence must execute on every target runner")
abstract class RecordJvmRuntimeEvidenceTask : DefaultTask() {
    @get:Input abstract val candidateCommit: Property<String>
    @get:Input abstract val target: Property<String>
    @get:Input abstract val runnerOs: Property<String>
    @get:Input abstract val runnerArch: Property<String>
    @get:Input abstract val javaExecutable: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val distributionManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val classifierArchive: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiledJvmTestRuntime: RegularFileProperty
    @get:OutputFile abstract val evidenceFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun record() = executeJvmRuntimeEvidence(
        candidateCommit.get(),
        target.get(),
        runnerOs.get(),
        runnerArch.get(),
        javaExecutable.get(),
        distributionManifest.get().asFile,
        classifierArchive.get().asFile,
        compiledJvmTestRuntime.get().asFile,
        evidenceFile.get().asFile,
    )
}

@CacheableTask
abstract class VerifyJvmRuntimeEvidenceTask : DefaultTask() {
    @get:Input abstract val expectedCommit: Property<String>
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val evidenceFiles: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val distributionManifest: RegularFileProperty
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE)
    abstract val classifierArchives: ConfigurableFileCollection
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiledJvmTestRuntime: RegularFileProperty
    @get:OutputFile abstract val verificationFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val records = evidenceFiles.files.toList()
        val errors = validateJvmRuntimeEvidence(
            records,
            expectedCommit.get(),
            distributionManifest.get().asFile,
            classifierArchives.files.toList(),
            compiledJvmTestRuntime.get().asFile,
        )
        check(errors.isEmpty()) { "JVM runtime evidence is invalid: ${errors.joinToString()}" }
        verificationFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1))
            put("result", JsonPrimitive("passed"))
            put("candidateCommit", JsonPrimitive(expectedCommit.get()))
            put("targets", buildJsonArray {
                desktopRuntimeEvidenceTargets.keys.forEach { add(JsonPrimitive(it)) }
            })
            put("evidence", buildJsonArray {
                records.sortedBy(File::getName).forEach { add(it.releaseRecord()) }
            })
            put("compiledJvmTestRuntimeSha256", JsonPrimitive(
                compiledJvmTestRuntime.get().asFile.releaseDigest(),
            ))
        })
    }
}
