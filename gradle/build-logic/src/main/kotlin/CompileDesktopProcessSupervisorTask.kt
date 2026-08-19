import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "The host compiler identity is not a declared input")
abstract class CompileDesktopProcessSupervisorTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFile: RegularFileProperty
    @get:Input abstract val compiler: Property<String>
    @get:Input abstract val windows: Property<Boolean>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun compile() {
        val source = sourceFile.get().asFile
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        processes.exec {
            if (windows.get()) {
                commandLine(compiler.get(), "/nologo", "/O2", "/W4", "/WX", source, "/Fe:$output")
            } else {
                commandLine(
                    compiler.get(), "-std=c11", "-D_POSIX_C_SOURCE=200809L", "-O2",
                    "-Wall", "-Wextra", "-Werror", source, "-o", output,
                )
            }
        }.assertNormalExitValue()
    }
}
