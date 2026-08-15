package io.github.ciurlaro.codexmobile.app.runtime.bootstrap

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationBrowser
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationPresentation
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationUrl

class AndroidCodexAuthorizationBrowser(context: Context) : CodexAuthorizationBrowser {
    private val appContext = context.applicationContext

    override fun open(url: CodexAuthorizationUrl): CodexAuthorizationPresentation {
        val customTab = CustomTabsIntent.Builder().setShowTitle(true).build()
        customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        customTab.launchUrl(appContext, Uri.parse(url.value))
        return CodexAuthorizationPresentation.None
    }
}
