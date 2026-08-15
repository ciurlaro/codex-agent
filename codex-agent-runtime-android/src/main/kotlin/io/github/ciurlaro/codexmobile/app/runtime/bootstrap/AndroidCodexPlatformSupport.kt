package io.github.ciurlaro.codexmobile.app.runtime.bootstrap

import android.content.Context
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationBrowser
import io.github.ciurlaro.codexmobile.agent.CodexPlatformSupport
import io.github.ciurlaro.codexmobile.agent.CodexPreparedRuntime
import io.github.ciurlaro.codexmobile.agent.CodexWorkspace
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution

class AndroidCodexPlatformSupport(context: Context) : CodexPlatformSupport {
    private val appContext = context.applicationContext

    override val workspaces = AndroidCodexWorkspaceStore(appContext)
    override val browser: CodexAuthorizationBrowser = AndroidCodexAuthorizationBrowser(appContext)

    override suspend fun prepare(workspace: CodexWorkspace): CodexPreparedRuntime {
        val available = workspaces.resolve(workspace.path) as? CodexWorkspaceResolution.Available
            ?: error("Android workspace is unavailable; restore or select it again")
        return CodexPreparedRuntime(
            runtimeFactory = AndroidCodexRuntimeFactory(appContext),
            workspacePath = available.workspace.path,
        )
    }
}
