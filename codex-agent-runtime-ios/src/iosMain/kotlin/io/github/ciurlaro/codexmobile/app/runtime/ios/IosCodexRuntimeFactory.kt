package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory

internal class IosCodexRuntimeFactory(
    val configuration: IosCodexRuntimeConfiguration,
) : CodexRuntimeFactory {
    val workspaceTools = IosCodexWorkspaceTools(configuration)

    override fun create(): CodexRuntime = IosCodexRuntime(configuration)
}
