package io.github.codex_agent_labs.codexmobile.appserver.runtime.host

import io.github.codex_agent_labs.codexmobile.agent.CodexPathWorkspaceSelection
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspace
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceResolution
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceSelection
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceSelectionReason
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceStore
import io.github.codex_agent_labs.codexmobile.appserver.runtime.nodeHost
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okio.Path

internal class NodePathWorkspaceStore(dataDirectory: Path) : CodexWorkspaceStore {
    private val dataDirectory = dataDirectory.toString()
    private val selectionFile get() = nodeHost.joinPath(dataDirectory, "workspace.json")

    override suspend fun select(selection: CodexWorkspaceSelection): CodexWorkspaceResolution {
        if (selection !is CodexPathWorkspaceSelection) return required(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "This host requires a filesystem workspace path.",
        )
        val resolution = resolve(selection.path)
        if (resolution is CodexWorkspaceResolution.Available) persist(resolution.workspace.path)
        return resolution
    }

    override suspend fun restore(): CodexWorkspaceResolution {
        if (!nodeHost.exists(selectionFile)) return required(
            CodexWorkspaceSelectionReason.NOT_SELECTED,
            "Select a workspace.",
        )
        if (!nodeHost.isFile(selectionFile) || nodeHost.fileSize(selectionFile) !in 1..MAX_SELECTION_BYTES) {
            return required(
                CodexWorkspaceSelectionReason.INVALID_SELECTION,
                "The saved workspace selection is corrupt; select it again.",
            )
        }
        val path = runCatching {
            Json.parseToJsonElement(nodeHost.readBytes(selectionFile).decodeToString()).jsonPrimitive.content
        }.getOrElse {
            return required(
                CodexWorkspaceSelectionReason.INVALID_SELECTION,
                "The saved workspace selection is corrupt; select it again.",
            )
        }
        return resolve(path)
    }

    override suspend fun clear() {
        nodeHost.removePath(selectionFile)
    }

    fun resolve(path: String): CodexWorkspaceResolution {
        if (path.isBlank() || '\u0000' in path || !nodeHost.isAbsolute(path)) return required(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "Workspace path must be a valid absolute path.",
        )
        if (!nodeHost.exists(path)) return required(
            CodexWorkspaceSelectionReason.NOT_FOUND,
            "Workspace directory is unavailable.",
        )
        if (!nodeHost.isDirectory(path)) return required(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "Workspace path is not a directory.",
        )
        val canonical = runCatching { nodeHost.realPath(path) }.getOrElse {
            return required(CodexWorkspaceSelectionReason.ACCESS_REVOKED, "Workspace access is unavailable.")
        }
        if (runCatching { nodeHost.list(canonical) }.isFailure) return required(
            CodexWorkspaceSelectionReason.ACCESS_REVOKED,
            "Workspace access is unavailable; select it again.",
        )
        return CodexWorkspaceResolution.Available(
            CodexWorkspace(canonical, nodeHost.baseName(canonical).ifBlank { canonical }),
        )
    }

    private fun persist(path: String) {
        nodeHost.createDirectories(dataDirectory)
        val temporary = nodeHost.joinPath(
            dataDirectory,
            ".workspace-${Random.nextLong().toString().replace('-', '0')}.tmp",
        )
        try {
            nodeHost.writeBytes(temporary, JsonPrimitive(path).toString().encodeToByteArray())
            nodeHost.move(temporary, selectionFile)
        } finally {
            nodeHost.removePath(temporary)
        }
    }

    private fun required(reason: CodexWorkspaceSelectionReason, message: String) =
        CodexWorkspaceResolution.SelectionRequired(reason, message)

    private companion object {
        const val MAX_SELECTION_BYTES = 16 * 1024L
    }
}
