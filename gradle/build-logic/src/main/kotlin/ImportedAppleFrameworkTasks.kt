import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

internal fun importedFrameworkPlatformCommand(infoPlist: File) = listOf(
    "/usr/bin/plutil", "-extract", "CFBundleSupportedPlatforms.0", "raw", "-o", "-", infoPlist.absolutePath,
)

internal fun verifyImportedFrameworkPlatform(expected: String, actual: String) {
    val normalized = actual.trim()
    check(normalized.equals(expected, ignoreCase = true)) {
        "Imported framework platform mismatch: expected=$expected actual=$normalized"
    }
}

@CacheableTask
abstract class ImportCodexAgentFrameworkTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val frameworkDirectory: DirectoryProperty
    @get:Input abstract val platformName: Property<String>
    @get:OutputDirectory abstract val importedFrameworkDirectory: DirectoryProperty

    @TaskAction
    fun importFramework() {
        val source = frameworkDirectory.get().asFile
        val required = listOf("CodexAgent", "Headers/CodexAgent.h", "Modules/module.modulemap", "Info.plist")
        required.forEach { relative ->
            val file = source.resolve(relative)
            check(file.isFile && file.length() > 0L && !Files.isSymbolicLink(file.toPath())) {
                "Imported ${platformName.get()} framework member is missing or unsafe: $relative"
            }
        }
        Files.walk(source.toPath()).use { paths ->
            check(paths.noneMatch(Files::isSymbolicLink)) { "Imported framework contains a symbolic link" }
        }
        val actualPlatform = capture(*importedFrameworkPlatformCommand(source.resolve("Info.plist")).toTypedArray())
        verifyImportedFrameworkPlatform(platformName.get(), actualPlatform)
        check("arm64" in capture("/usr/bin/xcrun", "lipo", "-info", source.resolve("CodexAgent").absolutePath)) {
            "Imported framework does not contain arm64"
        }
        val output = importedFrameworkDirectory.get().asFile
        deleteReleaseTree(output)
        copyReleaseTree(source, output)
    }

    private fun capture(vararg command: String): String {
        val output = ByteArrayOutputStream()
        processes.exec { commandLine(command.toList()); standardOutput = output }.assertNormalExitValue()
        return output.toString()
    }
}

@DisableCachingByDefault(because = "xcodebuild assembles exact previously validated framework inputs")
abstract class AssembleImportedCodexAgentXCFrameworkTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val deviceFrameworkDirectory: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val simulatorFrameworkDirectory: DirectoryProperty
    @get:OutputDirectory abstract val xcframeworkDirectory: DirectoryProperty

    @TaskAction
    fun assemble() {
        val output = xcframeworkDirectory.get().asFile
        deleteReleaseTree(output)
        output.parentFile.mkdirs()
        processes.exec {
            commandLine(
                "/usr/bin/xcodebuild", "-create-xcframework",
                "-framework", deviceFrameworkDirectory.get().asFile.absolutePath,
                "-framework", simulatorFrameworkDirectory.get().asFile.absolutePath,
                "-output", output.absolutePath,
            )
        }.assertNormalExitValue()
    }
}
