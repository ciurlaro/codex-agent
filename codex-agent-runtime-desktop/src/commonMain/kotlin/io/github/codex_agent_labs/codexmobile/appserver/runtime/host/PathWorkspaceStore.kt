package io.github.codex_agent_labs.codexmobile.appserver.runtime.host

import io.github.codex_agent_labs.codexmobile.agent.CodexPathWorkspaceSelection
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspace
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceResolution
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceSelection
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceSelectionReason
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceStore
import io.github.codex_agent_labs.codexmobile.appserver.runtime.desktopFileSystem
import kotlin.random.Random
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer

internal class PathWorkspaceStore(
    private val dataDirectory: Path,
    private val fileSystem: FileSystem = desktopFileSystem,
) : CodexWorkspaceStore {
    private val selectionFile get() = dataDirectory / "workspace.json"

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
        val metadata = fileSystem.metadataOrNull(selectionFile)
            ?: return required(CodexWorkspaceSelectionReason.NOT_SELECTED, "Select a workspace.")
        if (!metadata.isRegularFile || metadata.size !in 1..MAX_SELECTION_BYTES) return required(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "The saved workspace selection is corrupt; select it again.",
        )
        val path = runCatching {
            val source = fileSystem.source(selectionFile).buffer()
            val value = try {
                source.readUtf8()
            } finally {
                source.close()
            }
            Json.parseToJsonElement(value).jsonPrimitive.content
        }.getOrElse {
            return required(
                CodexWorkspaceSelectionReason.INVALID_SELECTION,
                "The saved workspace selection is corrupt; select it again.",
            )
        }
        return resolve(path)
    }

    override suspend fun clear() {
        fileSystem.delete(selectionFile, mustExist = false)
    }

    fun resolve(path: String): CodexWorkspaceResolution {
        if (path.isBlank() || '\u0000' in path) return required(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "Workspace path is invalid.",
        )
        val raw = runCatching { path.toPath() }.getOrElse {
            return required(CodexWorkspaceSelectionReason.INVALID_SELECTION, "Workspace path is invalid.")
        }
        if (!raw.isAbsolute) return required(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "Workspace path must be absolute.",
        )
        val metadata = fileSystem.metadataOrNull(raw)
            ?: return required(CodexWorkspaceSelectionReason.NOT_FOUND, "Workspace directory is unavailable.")
        if (!metadata.isDirectory) return required(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "Workspace path is not a directory.",
        )
        val canonical = runCatching { fileSystem.canonicalize(raw) }.getOrElse {
            return required(CodexWorkspaceSelectionReason.ACCESS_REVOKED, "Workspace access is unavailable.")
        }
        if (runCatching { fileSystem.list(canonical) }.isFailure) return required(
            CodexWorkspaceSelectionReason.ACCESS_REVOKED,
            "Workspace access is unavailable; select it again.",
        )
        return CodexWorkspaceResolution.Available(
            CodexWorkspace(canonical.toString(), canonical.name.ifBlank { canonical.toString() }),
        )
    }

    private fun persist(path: String) {
        fileSystem.createDirectories(dataDirectory)
        val temporary = dataDirectory / ".workspace-${Random.nextLong().toString().replace('-', '0')}.tmp"
        try {
            val sink = fileSystem.sink(temporary).buffer()
            try {
                sink.writeUtf8(JsonPrimitive(path).toString())
            } finally {
                sink.close()
            }
            fileSystem.atomicMove(temporary, selectionFile)
        } finally {
            fileSystem.delete(temporary, mustExist = false)
        }
    }

    private fun required(reason: CodexWorkspaceSelectionReason, message: String) =
        CodexWorkspaceResolution.SelectionRequired(reason, message)

    private companion object {
        const val MAX_SELECTION_BYTES = 16 * 1024L
    }
}
