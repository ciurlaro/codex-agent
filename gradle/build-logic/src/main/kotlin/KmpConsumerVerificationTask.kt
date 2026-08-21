import java.io.File

internal val stagedConsumerBuildTasks = linkedMapOf(
    "common" to listOf("compileKotlinJvm"),
    "android" to listOf("compileAndroidMain"),
    "desktop" to listOf(
        "compileKotlinJvm", "compileKotlinMacosArm64", "compileKotlinMacosX64",
        "compileKotlinLinuxArm64", "compileKotlinLinuxX64", "compileKotlinMingwX64",
    ),
    "ios-device" to listOf("linkDebugFrameworkIosArm64"),
    "ios-simulator" to listOf("linkDebugFrameworkIosSimulatorArm64"),
    "node-js" to listOf("compileKotlinJs"),
    "node-wasm" to listOf("compileKotlinWasmJs"),
)

internal const val stagedConsumerOutcomeTask = "verifyCodexStagedConsumerTaskOutcomes"

internal fun stagedConsumerOutcomeInitScript(buildTasks: List<String>): String {
    check(buildTasks.isNotEmpty()) { "Staged consumer build task set is empty" }
    check(buildTasks.distinct().size == buildTasks.size) { "Staged consumer build tasks contain duplicates" }
    check(buildTasks.all { it.matches(Regex("[A-Za-z0-9_-]+")) }) { "Staged consumer build task name is invalid" }
    val required = buildTasks.joinToString(", ") { "\"$it\"" }
    return """
        val requiredCodexConsumerTasks = listOf($required)
        gradle.projectsEvaluated {
            rootProject.tasks.register("$stagedConsumerOutcomeTask") {
                mustRunAfter(*requiredCodexConsumerTasks.toTypedArray())
                doLast {
                    val unproved = requiredCodexConsumerTasks.filter { taskName ->
                        val state = rootProject.tasks.getByName(taskName).state
                        !state.didWork && !state.upToDate
                    }
                    check(unproved.isEmpty()) {
                        "Staged consumer tasks did not execute or prove up-to-date: " +
                            unproved.joinToString()
                    }
                }
            }
        }
    """.trimIndent() + "\n"
}

internal fun stagedConsumerArguments(
    consumer: File,
    repository: File,
    version: String,
    target: String,
    buildTasks: List<String>,
    outcomeInitScript: File? = null,
): List<String> = listOf(
    "-p", consumer.absolutePath,
    "--no-daemon",
    "--no-configuration-cache",
    "-PCENTRAL_STAGING=${repository.absolutePath}",
    "-PcodexAgent.version=$version",
    "-PcodexAgent.consumerTarget=$target",
) + outcomeInitScript?.let { listOf("--init-script", it.absolutePath) }.orEmpty() +
    buildTasks + outcomeInitScript?.let { listOf(stagedConsumerOutcomeTask) }.orEmpty()

internal fun prepareStagedConsumer(template: File, consumer: File, androidSdk: String) {
    check(template.isDirectory) { "KMP consumer template is missing" }
    check('\n' !in androidSdk && '\r' !in androidSdk) { "Android SDK path is invalid" }
    consumer.deleteRecursively()
    check(template.copyRecursively(consumer, overwrite = true)) { "Failed to copy KMP consumer template" }
    val escaped = androidSdk.replace("\\", "\\\\").replace(":", "\\:")
    consumer.resolve("local.properties").writeText("sdk.dir=$escaped\n")
}
