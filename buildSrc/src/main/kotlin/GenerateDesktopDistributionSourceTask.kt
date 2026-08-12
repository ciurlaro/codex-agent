import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateDesktopDistributionSourceTask : DefaultTask() {
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
        val expectedTargets = linkedMapOf(
            "macosArm64" to "OsFamily.MACOSX to CpuArchitecture.ARM64",
            "macosX64" to "OsFamily.MACOSX to CpuArchitecture.X64",
            "linuxArm64" to "OsFamily.LINUX to CpuArchitecture.ARM64",
            "linuxX64" to "OsFamily.LINUX to CpuArchitecture.X64",
            "mingwX64" to "OsFamily.WINDOWS to CpuArchitecture.X64",
        )
        check(distributions.keys == expectedTargets.keys) {
            "Desktop distribution targets must be exactly ${expectedTargets.keys}"
        }
        fun value(target: String, key: String): String =
            distributions.getValue(target).getValue(key).jsonPrimitive.content.also {
                check(key !in setOf("archiveSha256", "binarySha256") || it.matches(Regex("[0-9a-f]{64}"))) {
                    "$target $key must be a lowercase SHA-256"
                }
            }

        val source = buildString {
            appendLine("@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)")
            appendLine()
            appendLine("package io.github.ciurlaro.codexmobile.appserver.runtime")
            appendLine()
            appendLine("import kotlin.native.CpuArchitecture")
            appendLine("import kotlin.native.OsFamily")
            appendLine("import kotlin.native.Platform")
            appendLine()
            appendLine("internal data class DesktopCodexDistribution(")
            appendLine("    val target: String,")
            appendLine("    val binarySha256: String,")
            appendLine("    val executableName: String,")
            appendLine(")")
            appendLine()
            appendLine("internal fun currentDesktopCodexDistribution(): DesktopCodexDistribution = when (")
            appendLine("    Platform.osFamily to Platform.cpuArchitecture")
            appendLine(") {")
            expectedTargets.forEach { (target, condition) ->
                appendLine("    $condition -> DesktopCodexDistribution(")
                appendLine("        target = \"$target\",")
                appendLine("        binarySha256 = \"${value(target, "binarySha256")}\",")
                appendLine("        executableName = \"${value(target, "executableName")}\",")
                appendLine("    )")
            }
            appendLine("    else -> error(\"Unsupported desktop target: ${'$'}{Platform.osFamily}/${'$'}{Platform.cpuArchitecture}\")")
            appendLine("}")
        }
        val output = outputDirectory.file(
            "io/github/ciurlaro/codexmobile/appserver/runtime/DesktopCodexDistribution.generated.kt",
        ).get().asFile
        output.parentFile.mkdirs()
        output.writeText(source)
    }
}
