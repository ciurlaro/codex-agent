import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class GenerateNodeDistributionSourceTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val distributionManifest: RegularFileProperty

    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val windowsSupervisorIdentity: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val manifest = readDesktopCodexManifest(distributionManifest.get().asFile)
        check(manifest.distributions.map { it.target }.toSet() == desktopRuntimeEvidenceTargets.keys) {
            "Node distribution targets do not match the authoritative desktop manifest"
        }
        val supervisor = windowsSupervisorIdentity.orNull?.asFile?.let { file ->
            check(file.name == WINDOWS_SUPERVISOR_IDENTITY_FILE_NAME) {
                "Windows supervisor identity filename mismatch"
            }
            readWindowsSupervisorIdentity(file)
        }
        val source = buildString {
            appendLine("package io.github.ciurlaro.codexmobile.appserver.runtime")
            appendLine()
            appendLine("internal data class NodeCodexDistribution(")
            appendLine("    val target: String,")
            appendLine("    val binarySha256: String,")
            appendLine("    val executableName: String,")
            appendLine(")")
            appendLine()
            appendLine("internal val nodeCodexDistributions = mapOf(")
            manifest.distributions.forEach { entry ->
                appendLine("    \"${entry.target}\" to NodeCodexDistribution(")
                appendLine("        target = \"${entry.target}\",")
                appendLine("        binarySha256 = \"${entry.binarySha256}\",")
                appendLine("        executableName = \"${entry.executableName}\",")
                appendLine("    ),")
            }
            appendLine(")")
            appendLine()
            appendLine("internal const val windowsNodeSupervisorFileName = \"$WINDOWS_SUPERVISOR_FILE_NAME\"")
            if (supervisor == null) {
                appendLine("internal val windowsNodeSupervisorSha256: String? = null")
            } else {
                appendLine("internal const val windowsNodeSupervisorSha256: String = \"${supervisor.sha256}\"")
            }
        }
        val output = outputDirectory.file(
            "io/github/ciurlaro/codexmobile/appserver/runtime/NodeCodexDistribution.generated.kt",
        ).get().asFile
        output.parentFile.mkdirs()
        output.writeText(source)
    }
}
