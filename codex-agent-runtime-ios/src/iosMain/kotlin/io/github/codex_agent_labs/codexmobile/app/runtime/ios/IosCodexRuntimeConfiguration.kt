package io.github.codex_agent_labs.codexmobile.app.runtime.ios

import io.github.codex_agent_labs.codexmobile.agent.runtime.IosCodexCredentialProtection
import kotlinx.serialization.Serializable

@Serializable
internal data class IosCodexRuntimeConfiguration(
    val sandboxRootPath: String,
    val workspacePath: String,
    val credentialProtection: IosCodexCredentialProtection,
    val codexHomePath: String = "$sandboxRootPath/Library/Application Support/CodexAgent",
    val securityScopedWorkspace: Boolean = false,
) {
    init {
        listOf(sandboxRootPath, workspacePath, codexHomePath).forEach { path ->
            require(path.startsWith('/')) { "iOS runtime paths must be absolute" }
        }
    }
}
