package io.github.ciurlaro.codexmobile.app.runtime.bootstrap

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import io.github.ciurlaro.codexmobile.agent.CodexPathWorkspaceSelection
import io.github.ciurlaro.codexmobile.agent.CodexWorkspace
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceSelection
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceSelectionReason
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceStore
import java.io.File

internal class AndroidCodexWorkspaceStore(context: Context) : CodexWorkspaceStore {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override suspend fun select(selection: CodexWorkspaceSelection): CodexWorkspaceResolution {
        if (selection !is CodexPathWorkspaceSelection) return selectionRequired(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "Android workspaces require a filesystem path.",
        )
        val resolution = resolve(selection.path)
        if (resolution is CodexWorkspaceResolution.Available) {
            check(preferences.edit().putString(PATH_KEY, resolution.workspace.path).commit()) {
                "Unable to save the Android workspace"
            }
        }
        return resolution
    }

    override suspend fun restore(): CodexWorkspaceResolution =
        preferences.getString(PATH_KEY, null)?.let(::resolve)
            ?: selectionRequired(CodexWorkspaceSelectionReason.NOT_SELECTED, "Select an Android workspace.")

    override suspend fun clear() {
        check(preferences.edit().remove(PATH_KEY).commit()) { "Unable to clear the Android workspace" }
    }

    internal fun resolve(path: String): CodexWorkspaceResolution =
        resolveAndroidWorkspace(path, roots())

    private fun roots(): List<File> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appContext.getSystemService(StorageManager::class.java).storageVolumes
                .mapNotNullTo(this) { it.directory }
        }
        @Suppress("DEPRECATION")
        add(Environment.getExternalStorageDirectory())
    }.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .filter(File::isDirectory)
        .distinctBy(File::getPath)
        .sortedBy(File::getPath)

    private companion object {
        const val PREFERENCES = "codex-agent-workspace"
        const val PATH_KEY = "path"
    }
}

internal fun resolveAndroidWorkspace(path: String, roots: List<File>): CodexWorkspaceResolution {
    if (path.isBlank() || '\u0000' in path) return selectionRequired(
        CodexWorkspaceSelectionReason.INVALID_SELECTION,
        "Workspace path is invalid.",
    )
    val candidate = runCatching { File(path).canonicalFile }.getOrElse {
        return selectionRequired(CodexWorkspaceSelectionReason.INVALID_SELECTION, "Workspace path is invalid.")
    }
    if (!candidate.exists() || !candidate.isDirectory) return selectionRequired(
        CodexWorkspaceSelectionReason.NOT_FOUND,
        "Workspace directory is unavailable.",
    )
    if (!candidate.canRead() || !candidate.canWrite()) return selectionRequired(
        CodexWorkspaceSelectionReason.ACCESS_REVOKED,
        "Workspace access is unavailable; grant filesystem access and select it again.",
    )
    val root = roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }
        .firstOrNull(candidate::isInside)
        ?: return selectionRequired(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "Workspace is outside Android shared storage.",
        )
    val relative = candidate.relativeTo(root).invariantSeparatorsPath.lowercase()
    if (relative == "android/data" || relative.startsWith("android/data/") ||
        relative == "android/obb" || relative.startsWith("android/obb/")) {
        return selectionRequired(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "Android does not allow this workspace.",
        )
    }
    return CodexWorkspaceResolution.Available(
        CodexWorkspace(candidate.path, candidate.name.ifBlank { candidate.path }),
    )
}

private fun File.isInside(root: File): Boolean =
    path == root.path || path.startsWith(root.path + File.separator)

private fun selectionRequired(
    reason: CodexWorkspaceSelectionReason,
    message: String,
) = CodexWorkspaceResolution.SelectionRequired(reason, message)
