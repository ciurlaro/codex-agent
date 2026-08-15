package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory

interface CodexWorkspaceSelection

data class CodexPathWorkspaceSelection(val path: String) : CodexWorkspaceSelection {
    init {
        require(path.isNotBlank() && '\u0000' !in path) { "Workspace path must not be blank" }
    }
}

data class CodexWorkspace(
    val path: String,
    val displayName: String = path,
) {
    init {
        require(path.isNotBlank() && '\u0000' !in path) { "Workspace path must not be blank" }
        require(displayName.isNotBlank()) { "Workspace display name must not be blank" }
    }
}

enum class CodexWorkspaceSelectionReason {
    NOT_SELECTED,
    NOT_FOUND,
    ACCESS_REVOKED,
    INVALID_SELECTION,
}

sealed interface CodexWorkspaceResolution {
    data class Available(val workspace: CodexWorkspace) : CodexWorkspaceResolution

    data class SelectionRequired(
        val reason: CodexWorkspaceSelectionReason,
        val message: String,
    ) : CodexWorkspaceResolution
}

interface CodexWorkspaceStore {
    suspend fun select(selection: CodexWorkspaceSelection): CodexWorkspaceResolution

    suspend fun restore(): CodexWorkspaceResolution

    suspend fun clear()
}

data class CodexPreparedRuntime(
    val runtimeFactory: CodexRuntimeFactory,
    val workspacePath: String,
    val builtInToolDispatcher: BuiltInToolDispatcher? = null,
) {
    fun createClient(
        clientVersion: String,
        requestTimeoutMillis: Long = 20_000,
    ): CodexAgentClient = CodexAgentClient(
        runtimeFactory = runtimeFactory,
        requestTimeoutMillis = requestTimeoutMillis,
        clientVersion = clientVersion,
        builtInToolDispatcher = builtInToolDispatcher,
    )
}

interface CodexPlatformSupport {
    val workspaces: CodexWorkspaceStore
    val browser: CodexAuthorizationBrowser

    suspend fun prepare(workspace: CodexWorkspace): CodexPreparedRuntime
}

internal fun String.isAbsoluteHostPath(): Boolean {
    if (isEmpty() || '\u0000' in this) return false
    if (startsWith('/')) return true
    if (length >= 3 && this[0].isLetter() && this[1] == ':' && this[2] in setOf('/', '\\')) return true
    if (!startsWith("\\\\")) return false
    val separator = indexOf('\\', startIndex = 2)
    return separator > 2 && separator < lastIndex
}
