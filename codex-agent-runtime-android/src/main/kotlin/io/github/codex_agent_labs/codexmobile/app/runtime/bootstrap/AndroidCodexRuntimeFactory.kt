package io.github.codex_agent_labs.codexmobile.app.runtime.bootstrap

import android.content.Context
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntime
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntimeFactory

internal class AndroidCodexRuntimeFactory(context: Context) : CodexRuntimeFactory {
    private val bootstrap = AndroidRuntimeBootstrap(context, runtimeOverride = null)

    override fun create(): CodexRuntime = bootstrap.create()
}
