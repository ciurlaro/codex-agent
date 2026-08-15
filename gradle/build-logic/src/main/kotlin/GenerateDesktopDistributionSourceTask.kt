import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateDesktopDistributionSourceTask : DefaultTask() {
    @get:Input
    abstract val libraryVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val manifest = Json.parseToJsonElement(manifestFile.get().asFile.readText()).jsonObject
        val distributions = manifest.getValue("distributions").jsonArray.associate { value ->
            val entry = value.jsonObject
            entry.getValue("target").jsonPrimitive.content to entry
        }
        val expectedTargets = linkedSetOf("macosArm64", "macosX64", "linuxArm64", "linuxX64", "mingwX64")
        check(distributions.keys == expectedTargets) {
            "Desktop distribution targets must be exactly $expectedTargets"
        }
        fun value(target: String, key: String): String =
            distributions.getValue(target).getValue(key).jsonPrimitive.content.also {
                check(key !in setOf("archiveSha256", "binarySha256") || it.matches(Regex("[0-9a-f]{64}"))) {
                    "$target $key must be a lowercase SHA-256"
                }
            }

        val source = buildString {
            appendLine("package io.github.ciurlaro.codexmobile.appserver.runtime")
            appendLine()
            appendLine("internal data class DesktopCodexDistribution(")
            appendLine("    val libraryVersion: String,")
            appendLine("    val appServerVersion: String,")
            appendLine("    val target: String,")
            appendLine("    val classifier: String,")
            appendLine("    val binarySha256: String,")
            appendLine("    val executableName: String,")
            appendLine("    val supervisorExecutableName: String,")
            appendLine(")")
            appendLine()
            appendLine("internal fun desktopCodexDistribution(target: String): DesktopCodexDistribution = when (target) {")
            expectedTargets.forEach { target ->
                appendLine("    \"$target\" -> DesktopCodexDistribution(")
                appendLine("        libraryVersion = \"${libraryVersion.get()}\",")
                appendLine("        appServerVersion = \"${manifest.getValue("version").jsonPrimitive.content}\",")
                appendLine("        target = \"$target\",")
                appendLine("        classifier = \"${value(target, "classifier")}\",")
                appendLine("        binarySha256 = \"${value(target, "binarySha256")}\",")
                appendLine("        executableName = \"${value(target, "executableName")}\",")
                appendLine("        supervisorExecutableName = \"${value(target, "supervisorExecutableName")}\",")
                appendLine("    )")
            }
            appendLine("    else -> error(\"Unsupported desktop target: ${'$'}target\")")
            appendLine("}")
        }
        val output = outputDirectory.file(
            "io/github/ciurlaro/codexmobile/appserver/runtime/DesktopCodexDistribution.generated.kt",
        ).get().asFile
        output.parentFile.mkdirs()
        output.writeText(source)
    }
}
