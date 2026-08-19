import io.github.ciurlaro.codexmobile.agent.runtime.DesktopCodexPlatform
import okio.Path

fun desktopPlatform(bundleDirectory: Path, dataDirectory: Path) =
    DesktopCodexPlatform(bundleDirectory, dataDirectory)
