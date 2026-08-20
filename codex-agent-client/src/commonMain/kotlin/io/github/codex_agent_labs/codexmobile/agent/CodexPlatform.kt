package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntimeFactory
import okio.Path

public interface CodexWorkspaceSelection

public data class CodexClientInfo(
    public val name: String,
    public val title: String,
    public val version: String,
) {
    init {
        require(name.isValidClientInfoValue()) { "Client name must not be blank or contain control characters" }
        require(title.isValidClientInfoValue()) { "Client title must not be blank or contain control characters" }
        require(version.isValidClientInfoValue()) { "Client version must not be blank or contain control characters" }
    }
}

public enum class CodexRuntimeFeature {
    SHELL_COMMANDS,
    SKILLS,
    HOOKS,
    PLUGINS,
    CONNECTORS,
    MCP_SERVERS,
}

public data class CodexPathWorkspaceSelection(public val path: String) : CodexWorkspaceSelection {
    init {
        require(path.isNotBlank() && '\u0000' !in path) { "Workspace path must not be blank" }
    }
}

public data class CodexWorkspace(
    public val path: String,
    public val displayName: String = path,
) {
    init {
        require(path.isNotBlank() && '\u0000' !in path) { "Workspace path must not be blank" }
        require(displayName.isNotBlank()) { "Workspace display name must not be blank" }
    }
}

public enum class CodexWorkspaceSelectionReason {
    NOT_SELECTED,
    NOT_FOUND,
    ACCESS_REVOKED,
    INVALID_SELECTION,
}

public sealed interface CodexWorkspaceResolution {
    public data class Available(public val workspace: CodexWorkspace) : CodexWorkspaceResolution

    public data class SelectionRequired(
        public val reason: CodexWorkspaceSelectionReason,
        public val message: String,
    ) : CodexWorkspaceResolution
}

public interface CodexWorkspaceStore {
    @Throws(Exception::class)
    public suspend fun select(selection: CodexWorkspaceSelection): CodexWorkspaceResolution

    @Throws(Exception::class)
    public suspend fun restore(): CodexWorkspaceResolution

    @Throws(Exception::class)
    public suspend fun clear()
}

public enum class CodexStorageDurability {
    CACHE,
    STATE,
}

public enum class CodexStorageArea(
    public val durability: CodexStorageDurability,
    public val directoryName: String,
) {
    PLUGIN_CACHE(CodexStorageDurability.CACHE, "plugins"),
    SHELL_TRANSCRIPTS(CodexStorageDurability.STATE, "shell-transcripts"),
    TURN_INPUT_METADATA(CodexStorageDurability.STATE, "turn-input-metadata"),
}

public data class CodexStorageRoots(
    public val cacheRoot: Path? = null,
    public val stateRoot: Path? = null,
) {
    public fun directory(area: CodexStorageArea): Path? = when (area.durability) {
        CodexStorageDurability.CACHE -> cacheRoot
        CodexStorageDurability.STATE -> stateRoot
    }?.let { it / area.directoryName }
}

public data class PreparedCodexRuntime(
    public val runtimeFactory: CodexRuntimeFactory,
    public val workspacePath: String,
    public val features: Set<CodexRuntimeFeature>,
    public val storageRoots: CodexStorageRoots = CodexStorageRoots(),
    public val toolProvider: CodexToolProvider? = null,
) {
    internal fun createClient(
        clientInfo: CodexClientInfo,
        requestTimeoutMillis: Long = 20_000,
    ): CodexAgentClient = CodexAgentClient(
        runtimeFactory = runtimeFactory,
        requestTimeoutMillis = requestTimeoutMillis,
        clientInfo = clientInfo,
        pluginCacheDirectory = storageRoots.directory(CodexStorageArea.PLUGIN_CACHE),
        shellTranscriptDirectory = storageRoots.directory(CodexStorageArea.SHELL_TRANSCRIPTS),
        turnInputMetadataDirectory = storageRoots.directory(CodexStorageArea.TURN_INPUT_METADATA),
        toolProvider = toolProvider,
        fileSystem = systemAgentFileStore,
    )
}

public interface CodexPlatform {
    public val workspaceStore: CodexWorkspaceStore
    public val authorizationBrowser: CodexAuthorizationBrowser

    @Throws(Exception::class)
    public suspend fun prepare(workspace: CodexWorkspace): PreparedCodexRuntime
}

internal fun String.isAbsoluteHostPath(): Boolean {
    if (isEmpty() || '\u0000' in this) return false
    if (startsWith('/')) return true
    if (length >= 3 && this[0].isLetter() && this[1] == ':' && this[2] in setOf('/', '\\')) return true
    if (!startsWith("\\\\")) return false
    val separator = indexOf('\\', startIndex = 2)
    return separator > 2 && separator < lastIndex
}

private fun String.isValidClientInfoValue(): Boolean = isNotBlank() && none(Char::isISOControl)
