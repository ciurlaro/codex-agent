import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val RUNTIME_BUNDLE_DIRECTORY_ENV = "CODEX_AGENT_RUNTIME_BUNDLE_DIRECTORY"
internal const val RUNTIME_DATA_DIRECTORY_ENV = "CODEX_AGENT_RUNTIME_DATA_DIRECTORY"
internal const val RUNTIME_WORKSPACE_ENV = "CODEX_AGENT_WORKSPACE"

internal data class RuntimeEvidenceDirectories(
    val bundle: File,
    val data: File,
    val workspace: File,
) {
    fun environment(target: String) = mapOf(
        RUNTIME_BUNDLE_DIRECTORY_ENV to bundle.absolutePath,
        RUNTIME_DATA_DIRECTORY_ENV to data.absolutePath,
        RUNTIME_WORKSPACE_ENV to workspace.absolutePath,
        "CODEX_HOME" to data.absolutePath,
        "CODEX_SQLITE_HOME" to data.absolutePath,
        "CODEX_AGENT_DESKTOP_TARGET" to target,
    )
}

internal fun stageRuntimeBundleForEvidence(
    archive: File,
    expectedTarget: String,
    expectedClassifier: String,
    root: File,
): RuntimeEvidenceDirectories {
    val identity = ZipFile(archive).use { zip ->
        val entry = zip.getEntry("codex-runtime-manifest.json")
            ?: error("Classifier runtime manifest is missing")
        val manifest = Json.parseToJsonElement(
            zip.getInputStream(entry).use { it.readBytes().decodeToString() },
        ).jsonObject
        check(manifest.getValue("schemaVersion").jsonPrimitive.content == "1" &&
            manifest.getValue("target").jsonPrimitive.content == expectedTarget &&
            manifest.getValue("classifier").jsonPrimitive.content == expectedClassifier) {
            "Classifier runtime manifest identity is invalid"
        }
        manifest.getValue("libraryVersion").jsonPrimitive.content.also {
            check(it.matches(Regex("[A-Za-z0-9._-]+"))) { "Classifier library version is invalid" }
        }
    }
    val directories = RuntimeEvidenceDirectories(
        root.resolve("bundle"),
        root.resolve("data"),
        root.resolve("workspace"),
    )
    listOf(directories.bundle, directories.data, directories.workspace).forEach { directory ->
        check(directory.mkdirs() || directory.isDirectory) { "Could not create runtime evidence directory" }
    }
    val expectedName = "codex-agent-runtime-desktop-$identity-$expectedClassifier.zip"
    Files.copy(archive.toPath(), directories.bundle.resolve(expectedName).toPath(), REPLACE_EXISTING)
    return RuntimeEvidenceDirectories(
        directories.bundle.canonicalFile,
        directories.data.canonicalFile,
        directories.workspace.canonicalFile,
    )
}
