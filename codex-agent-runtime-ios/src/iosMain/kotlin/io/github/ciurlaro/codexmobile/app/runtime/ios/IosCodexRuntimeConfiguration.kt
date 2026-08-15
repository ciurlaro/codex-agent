package io.github.ciurlaro.codexmobile.app.runtime.ios

import kotlinx.serialization.Serializable

@Serializable
enum class IosCodexCredentialProtection {
    WHEN_UNLOCKED,
    AFTER_FIRST_UNLOCK,
    WHILE_OPEN,
}

@Serializable
data class IosCodexRuntimeConfiguration(
    val sandboxRootPath: String,
    val workspacePath: String,
    val credentialProtection: IosCodexCredentialProtection,
    val codexHomePath: String = "$sandboxRootPath/Library/Application Support/CodexAgent",
    @Deprecated("Unused compatibility property; temporary files are created beside their destination for atomic replacement")
    val temporaryPath: String = "$sandboxRootPath/tmp/CodexAgent",
    val securityScopedWorkspace: Boolean = false,
) {
    init {
        listOf(sandboxRootPath, workspacePath, codexHomePath).forEach { path ->
            require(path.startsWith('/')) { "iOS runtime paths must be absolute" }
        }
    }
}
