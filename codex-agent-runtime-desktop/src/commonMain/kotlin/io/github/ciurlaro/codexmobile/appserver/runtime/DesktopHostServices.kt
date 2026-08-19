package io.github.ciurlaro.codexmobile.appserver.runtime

import okio.Path

internal expect fun openDesktopAuthorizationUrl(url: String)

internal expect fun makeDesktopExecutable(path: Path)
