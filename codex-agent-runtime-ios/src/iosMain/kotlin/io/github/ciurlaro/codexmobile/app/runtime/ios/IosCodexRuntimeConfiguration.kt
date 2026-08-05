package io.github.ciurlaro.codexmobile.app.runtime.ios

import kotlinx.serialization.Serializable

@Serializable
data class IosCodexRuntimeConfiguration(
    val sandboxRootPath: String,
    val workspacePath: String,
    val codexHomePath: String = "$sandboxRootPath/Library/Application Support/CodexAgent",
    @Deprecated("Unused compatibility property; temporary files are created beside their destination for atomic replacement")
    val temporaryPath: String = "$sandboxRootPath/tmp/CodexAgent",
) {
    init {
        listOf(sandboxRootPath, workspacePath, codexHomePath).forEach { path ->
            require(path.startsWith('/')) { "iOS runtime paths must be absolute" }
        }
    }
}
