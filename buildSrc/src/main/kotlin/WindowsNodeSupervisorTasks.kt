import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Two clean native builds prove exact Windows supervisor reproducibility")
abstract class BuildWindowsNodeSupervisorTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:Input abstract val cmakeExecutable: Property<String>
    @get:OutputFile abstract val outputExecutable: RegularFileProperty
    @get:OutputFile abstract val generatedIdentityFile: RegularFileProperty

    init {
        cmakeExecutable.convention("cmake")
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun build() {
        check(System.getProperty("os.name").startsWith("Windows")) {
            "The Windows supervisor must be materialized on Windows"
        }
        check(System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")) {
            "The Windows supervisor must be materialized on an x64 runner"
        }
        val source = sourceDirectory.get().asFile
        val output = outputExecutable.get().asFile
        check(output.name == WINDOWS_SUPERVISOR_FILE_NAME) { "Windows supervisor output basename mismatch" }
        val cmake = cmakeExecutable.get()
        val cmakeVersion = parseCmakeVersion(runCommand(listOf(cmake, "--version"), source))
        val builds = (1..2).map { index -> buildOnce(cmake, source, temporaryDir.resolve("build-$index")) }
        check(builds[0].bytes.contentEquals(builds[1].bytes)) {
            "Two clean Windows supervisor builds produced different bytes"
        }
        check(builds[0].compiler == builds[1].compiler) {
            "Two clean Windows supervisor builds used different compilers"
        }
        val compiler = builds[0].compiler.copy(cmakeVersion = cmakeVersion)
        val identity = WindowsSupervisorIdentity(
            fileName = WINDOWS_SUPERVISOR_FILE_NAME,
            sha256 = builds[0].executable.windowsSupervisorSha256(),
            bytes = builds[0].bytes.size.toLong(),
            sourceSha256 = windowsSupervisorSourceSha256(source),
            compiler = compiler,
        )
        installAtomically(builds[0].executable, output)
        writeWindowsSupervisorIdentity(generatedIdentityFile.get().asFile, identity)
    }

    private fun buildOnce(cmake: String, source: File, root: File): SupervisorBuild {
        root.deleteRecursively()
        val binary = root.resolve("cmake")
        val output = root.resolve("output").also(File::mkdirs)
        runCommand(windowsSupervisorConfigureCommand(cmake, source, binary, output), source)
        runCommand(windowsSupervisorBuildCommand(cmake, binary), source)
        val executable = output.resolve(WINDOWS_SUPERVISOR_FILE_NAME)
        check(executable.isFile && executable.length() > 0) { "CMake did not produce the Windows supervisor" }
        return SupervisorBuild(executable, executable.readBytes(), readCompiler(binary))
    }

    private fun readCompiler(build: File): WindowsSupervisorCompiler {
        val records = build.walkTopDown().filter { it.isFile && it.name == "CMakeCCompiler.cmake" }.toList()
        check(records.size == 1) { "CMake compiler evidence is missing or ambiguous" }
        val text = records.single().readText()
        fun value(name: String) = Regex("set\\($name \\\"([^\\\"]+)\\\"\\)").find(text)?.groupValues?.get(1)
            ?: error("CMake compiler evidence is missing $name")
        val id = value("CMAKE_C_COMPILER_ID")
        check(id == "MSVC") { "Windows supervisor compiler must be MSVC, not $id" }
        return WindowsSupervisorCompiler(id, value("CMAKE_C_COMPILER_VERSION"), cmakeVersion = "pending")
    }

    private fun runCommand(command: List<String>, workingDirectory: File): String {
        val output = ByteArrayOutputStream()
        val result = processes.exec {
            workingDir(workingDirectory); commandLine(command)
            standardOutput = output; errorOutput = output; isIgnoreExitValue = true
        }
        check(result.exitValue == 0) { "Windows supervisor command failed (${command.first()}):\n${output}" }
        return output.toString(Charsets.UTF_8)
    }
}

@CacheableTask
abstract class PackageWindowsNodeSupervisorTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val executableFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val generatedIdentityFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val canonicalIdentityFile: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceDirectory: DirectoryProperty
    @get:OutputFile abstract val packageFile: RegularFileProperty

    @TaskAction
    fun packageSupervisor() {
        val executable = executableFile.get().asFile
        val canonical = readWindowsSupervisorIdentity(canonicalIdentityFile.get().asFile)
        val generated = readWindowsSupervisorIdentity(generatedIdentityFile.get().asFile)
        check(canonical == generated) { "Tracked Windows supervisor identity does not match the two-build proof" }
        verifyWindowsSupervisorIdentity(canonical, executable, sourceDirectory.get().asFile)
        val first = temporaryDir.resolve("first.zip")
        val second = temporaryDir.resolve("second.zip")
        writeWindowsSupervisorPackage(first, executable, canonicalIdentityFile.get().asFile)
        writeWindowsSupervisorPackage(second, executable, canonicalIdentityFile.get().asFile)
        check(first.readBytes().contentEquals(second.readBytes())) { "Windows supervisor package is not deterministic" }
        installAtomically(first, packageFile.get().asFile)
    }
}

@CacheableTask
abstract class VerifyWindowsNodeSupervisorPackageTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val packageFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val canonicalIdentityFile: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourceDirectory: DirectoryProperty
    @get:OutputFile abstract val proofFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val identity = verifyWindowsSupervisorPackage(
            packageFile.get().asFile, canonicalIdentityFile.get().asFile, sourceDirectory.get().asFile,
        )
        verifyWindowsSupervisorIdentity(identity, extractExecutable(packageFile.get().asFile), sourceDirectory.get().asFile)
        proofFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(1)); put("fileName", JsonPrimitive(identity.fileName))
            put("sha256", JsonPrimitive(identity.sha256)); put("bytes", JsonPrimitive(identity.bytes))
            put("sourceSha256", JsonPrimitive(identity.sourceSha256))
            put("packageSha256", JsonPrimitive(packageFile.get().asFile.windowsSupervisorSha256()))
            put("result", JsonPrimitive("passed"))
        })
    }

    private fun extractExecutable(packageFile: File): File {
        val output = temporaryDir.resolve(WINDOWS_SUPERVISOR_FILE_NAME)
        java.util.zip.ZipFile(packageFile).use { zip ->
            zip.getInputStream(zip.getEntry(WINDOWS_SUPERVISOR_FILE_NAME)).use { input ->
                Files.copy(input, output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return output
    }
}

private data class SupervisorBuild(
    val executable: File,
    val bytes: ByteArray,
    val compiler: WindowsSupervisorCompiler,
)

private fun parseCmakeVersion(output: String): String =
    Regex("cmake version ([0-9]+(?:\\.[0-9]+)+)").find(output)?.groupValues?.get(1)
        ?: error("Could not identify CMake version")

private fun installAtomically(source: File, target: File) {
    target.parentFile.mkdirs()
    val staged = target.toPath().resolveSibling(".${target.name}.${System.nanoTime()}.tmp")
    try {
        Files.copy(source.toPath(), staged, StandardCopyOption.REPLACE_EXISTING)
        try {
            Files.move(staged, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(staged, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(staged)
    }
}
