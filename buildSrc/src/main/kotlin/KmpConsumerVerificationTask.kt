import java.io.File
import javax.inject.Inject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.LocalState
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

internal val stagedConsumerBuildTasks = listOf(
    "compileKotlinJvm",
    "compileAndroidMain",
    "linkDebugFrameworkIosArm64",
    "linkDebugFrameworkIosSimulatorArm64",
    "compileKotlinMacosArm64",
    "compileKotlinMacosX64",
    "compileKotlinLinuxArm64",
    "compileKotlinLinuxX64",
    "compileKotlinMingwX64",
    "compileKotlinJs",
    "compileKotlinWasmJs",
)

internal fun stagedConsumerArguments(
    consumer: File,
    repository: File,
    version: String,
): List<String> = listOf(
    "-p", consumer.absolutePath,
    "--no-daemon",
    "--no-configuration-cache",
    "-PCENTRAL_STAGING=${repository.absolutePath}",
    "-PcodexAgent.version=$version",
) + stagedConsumerBuildTasks

internal fun prepareStagedConsumer(template: File, consumer: File, androidSdk: String) {
    check(template.isDirectory) { "KMP consumer template is missing" }
    check('\n' !in androidSdk && '\r' !in androidSdk) { "Android SDK path is invalid" }
    consumer.deleteRecursively()
    check(template.copyRecursively(consumer, overwrite = true)) { "Failed to copy KMP consumer template" }
    val escaped = androidSdk.replace("\\", "\\\\").replace(":", "\\:")
    consumer.resolve("local.properties").writeText("sdk.dir=$escaped\n")
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
    @get:LocalState abstract val consumerDirectory: DirectoryProperty
    @get:OutputFile abstract val resultFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val consumer = consumerDirectory.get().asFile
        val repository = repositoryDirectory.get().asFile
        prepareStagedConsumer(templateDirectory.get().asFile, consumer, androidSdkDirectory.get())
        val arguments = stagedConsumerArguments(consumer, repository, projectVersion.get())
        exec.exec {
            workingDir(consumer)
            executable(gradleWrapper.get().asFile.absolutePath)
            args(arguments)
        }.assertNormalExitValue()
        resultFile.get().asFile.atomicWriteJson(buildJsonObject {
            put("schemaVersion", JsonPrimitive(2))
            put("result", JsonPrimitive("passed"))
            put("version", JsonPrimitive(projectVersion.get()))
            put("repository", JsonPrimitive("CENTRAL_STAGING-only"))
            put("mavenInventorySha256", JsonPrimitive(mavenInventory.get().asFile.releaseDigest()))
            put("jvm", JsonPrimitive("passed"))
            put("android", JsonPrimitive("passed"))
            put("iosArm64", JsonPrimitive("passed"))
            put("iosSimulatorArm64", JsonPrimitive("passed"))
            put("macosArm64", JsonPrimitive("passed"))
            put("macosX64", JsonPrimitive("passed"))
            put("linuxArm64", JsonPrimitive("passed"))
            put("linuxX64", JsonPrimitive("passed"))
            put("mingwX64", JsonPrimitive("passed"))
            put("js", JsonPrimitive("passed"))
            put("wasmJs", JsonPrimitive("passed"))
        })
    }
}
