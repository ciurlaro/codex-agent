package io.github.codex_agent_labs.codexmobile.appserver.runtime

import okio.Path

internal expect fun openDesktopAuthorizationUrl(url: String)

internal expect fun makeDesktopExecutable(path: Path)
