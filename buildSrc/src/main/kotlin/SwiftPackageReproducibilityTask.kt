import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Each invocation must prove two fresh builds")
abstract class VerifySwiftPackageReproducibilityTask @Inject constructor(
    private val exec: ExecOperations,
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val gradleWrapper: RegularFileProperty

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:Internal
    abstract val archiveFile: RegularFileProperty

    @get:Input
    abstract val buildTasks: ListProperty<String>

    @get:Input
    abstract val swiftExecutable: Property<String>

    init {
        group = "verification"
        description = "Proves two clean SwiftPM binary builds are byte-for-byte identical."
        buildTasks.convention(
            listOf(
                ":codex-agent-runtime-ios:clean",
                ":codex-agent-runtime-ios:packageCodexAgentSwiftPackageBinary",
                ":codex-agent-runtime-ios:generateCodexAgentSwiftPackageChecksum",
                "--no-configuration-cache",
            ),
        )
        swiftExecutable.convention("swift")
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val archive = archiveFile.get().asFile
        val firstArchive = Files.createTempFile("codex-agent-swiftpm-", ".zip").toFile()
        try {
            build()
            check(archive.isFile) { "SwiftPM binary archive is missing: $archive" }
            Files.copy(archive.toPath(), firstArchive.toPath(), StandardCopyOption.REPLACE_EXISTING)

            val firstChecksum = checksum(firstArchive)
            val manifestLine = manifest.get().asFile.useLines { lines ->
                lines.firstOrNull { it.contains("checksum: \"$firstChecksum\"") }
            }
            checkNotNull(manifestLine) { "SwiftPM checksum $firstChecksum is not committed in Package.swift" }
            logger.lifecycle(manifestLine)

            build()
            check(archive.isFile) { "Second SwiftPM binary archive is missing: $archive" }
            check(Files.mismatch(firstArchive.toPath(), archive.toPath()) == -1L) {
                "Clean SwiftPM binary builds are not byte-for-byte identical"
            }

            val secondChecksum = checksum(archive)
            check(secondChecksum == firstChecksum) {
                "Clean SwiftPM checksums differ: $firstChecksum != $secondChecksum"
            }
            logger.lifecycle("Deterministic SwiftPM checksum: $secondChecksum")
        } finally {
            firstArchive.delete()
        }
    }

    private fun build() {
        exec.exec {
            workingDir(repositoryDirectory)
            executable(gradleWrapper.get().asFile)
            args(buildTasks.get())
        }.assertNormalExitValue()
    }

    private fun checksum(archive: java.io.File): String {
        val output = ByteArrayOutputStream()
        exec.exec {
            commandLine(swiftExecutable.get(), "package", "compute-checksum", archive.absolutePath)
            standardOutput = output
        }.assertNormalExitValue()
        return output.toString().trim().also { check(it.isNotEmpty()) { "SwiftPM checksum is empty" } }
    }
}
