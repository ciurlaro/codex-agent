package io.github.codex_agent_labs.codexmobile.appserver.runtime

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.net.URI
import okio.Path

internal actual fun openDesktopAuthorizationUrl(url: String) {
    check(!GraphicsEnvironment.isHeadless() && Desktop.isDesktopSupported()) {
        "Desktop browser integration is unavailable"
    }
    val desktop = Desktop.getDesktop()
    check(desktop.isSupported(Desktop.Action.BROWSE)) { "Desktop browser integration is unavailable" }
    desktop.browse(URI(url))
}

internal actual fun makeDesktopExecutable(path: Path) {
    if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
        check(path.toFile().setExecutable(true, false)) { "Unable to make '${path.name}' executable" }
    }
}
