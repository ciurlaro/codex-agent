package io.github.codex_agent_labs.codexmobile.appserver.runtime

import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL

internal actual fun openDesktopAuthorizationUrl(url: String) {
    val nativeUrl = NSURL.URLWithString(url) ?: error("Authorization URL is invalid")
    check(NSWorkspace.sharedWorkspace.openURL(nativeUrl)) { "Unable to open the authorization URL" }
}
