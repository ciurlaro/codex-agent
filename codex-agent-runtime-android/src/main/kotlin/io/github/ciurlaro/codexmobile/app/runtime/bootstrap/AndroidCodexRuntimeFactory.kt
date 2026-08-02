package io.github.ciurlaro.codexmobile.app.runtime.bootstrap

import android.content.Context
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory

class AndroidCodexRuntimeFactory(context: Context) : CodexRuntimeFactory {
    private val bootstrap = AndroidRuntimeBootstrap(context, runtimeOverride = null)

    override fun create(): CodexRuntime = bootstrap.create()
}
