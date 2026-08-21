package io.github.codex_agent_labs.codexmobile.app.runtime.bootstrap

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import io.github.codex_agent_labs.codexmobile.agent.CodexAuthorizationBrowser
import io.github.codex_agent_labs.codexmobile.agent.CodexAuthorizationPresentation
import io.github.codex_agent_labs.codexmobile.agent.CodexAuthorizationUrl

internal class AndroidCodexAuthorizationBrowser(context: Context) : CodexAuthorizationBrowser {
    private val appContext = context.applicationContext

    override fun open(url: CodexAuthorizationUrl): CodexAuthorizationPresentation {
        val customTab = CustomTabsIntent.Builder().setShowTitle(true).build()
        customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTab.launchUrl(appContext, Uri.parse(url.value))
        return CodexAuthorizationPresentation.None
    }
}
