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
abstract class GenerateNodeDistributionSourceTask : DefaultTask() {
    @get:Input
    abstract val libraryVersion: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val distributionManifest: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val manifest = readDesktopCodexManifest(distributionManifest.get().asFile)
        check(manifest.distributions.map { it.target }.toSet() == desktopRuntimeEvidenceTargets.keys) {
            "Node distribution targets do not match the authoritative desktop manifest"
        }
        val source = buildString {
            appendLine("package io.github.ciurlaro.codexmobile.appserver.runtime")
            appendLine()
            appendLine("internal data class NodeCodexDistribution(")
            appendLine("    val libraryVersion: String,")
            appendLine("    val appServerVersion: String,")
            appendLine("    val target: String,")
            appendLine("    val classifier: String,")
            appendLine("    val binarySha256: String,")
            appendLine("    val executableName: String,")
            appendLine("    val supervisorExecutableName: String,")
            appendLine(")")
            appendLine()
            appendLine("internal val nodeCodexDistributions = mapOf(")
            manifest.distributions.forEach { entry ->
                appendLine("    \"${entry.target}\" to NodeCodexDistribution(")
                appendLine("        libraryVersion = \"${libraryVersion.get()}\",")
                appendLine("        appServerVersion = \"${manifest.version}\",")
                appendLine("        target = \"${entry.target}\",")
                appendLine("        classifier = \"${entry.classifier}\",")
                appendLine("        binarySha256 = \"${entry.binarySha256}\",")
                appendLine("        executableName = \"${entry.executableName}\",")
                appendLine("        supervisorExecutableName = \"${entry.supervisorExecutableName}\",")
                appendLine("    ),")
            }
            appendLine(")")
            appendLine()
            appendLine("internal fun nodeCodexDistribution(target: String): NodeCodexDistribution =")
            appendLine("    nodeCodexDistributions[target] ?: error(\"Unsupported Codex App Server target: ${'$'}target\")")
        }
        val output = outputDirectory.file(
            "io/github/ciurlaro/codexmobile/appserver/runtime/NodeCodexDistribution.generated.kt",
        ).get().asFile
        output.parentFile.mkdirs()
        output.writeText(source)
    }
}
