@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.appserver.runtime

import codex_desktop.codex_open_url

internal actual fun openDesktopAuthorizationUrl(url: String) {
    check(codex_open_url(url) == 0) { "Unable to open the authorization URL" }
}
