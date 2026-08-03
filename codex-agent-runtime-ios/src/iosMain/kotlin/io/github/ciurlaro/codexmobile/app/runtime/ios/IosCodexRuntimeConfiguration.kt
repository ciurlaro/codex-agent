package io.github.ciurlaro.codexmobile.app.runtime.ios

import kotlinx.serialization.Serializable

@Serializable
data class IosCodexRuntimeConfiguration(
    val sandboxRootPath: String,
    val workspacePath: String,
    val codexHomePath: String = "$sandboxRootPath/Library/Application Support/CodexAgent",
    val temporaryPath: String = "$sandboxRootPath/tmp/CodexAgent",
) {
    init {
        listOf(sandboxRootPath, workspacePath, codexHomePath, temporaryPath).forEach { path ->
            require(path.startsWith('/')) { "iOS runtime paths must be absolute" }
        }
    }
}
