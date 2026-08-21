package io.github.codex_agent_labs.codexmobile.app.runtime.ios

import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntime
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntimeFactory

internal class IosCodexRuntimeFactory(
    val configuration: IosCodexRuntimeConfiguration,
) : CodexRuntimeFactory {
    val workspaceTools = IosCodexWorkspaceTools(configuration)

    override fun create(): CodexRuntime = IosCodexRuntime(configuration)
}
