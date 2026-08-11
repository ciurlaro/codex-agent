import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Preflight validates live Commit B state before native work")
abstract class PreflightSwiftPackageBTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:Input abstract val expectedCommit: Property<String>
    @get:Input abstract val version: Property<String>
    @get:Input abstract val expectedUrl: Property<String>
    @get:Input abstract val gitExecutable: Property<String>
    @get:Internal abstract val repositoryDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val baselineProof: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifestFile: RegularFileProperty

    init {
        group = "verification"
        description = "Rejects an invalid Commit B before native SwiftPM work starts."
        gitExecutable.convention("git")
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun preflight() {
        validateSwiftPackageB(
            exec = exec,
            repository = repositoryDirectory.get().asFile.canonicalFile,
            git = gitExecutable.get(),
            expectedCommit = expectedCommit.get(),
            version = version.get(),
            expectedUrl = expectedUrl.get(),
            baselineFile = baselineProof.get().asFile,
            manifestFile = manifestFile.get().asFile,
        )
    }
}
