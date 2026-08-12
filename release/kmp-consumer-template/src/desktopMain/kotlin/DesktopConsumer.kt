import io.github.ciurlaro.codexmobile.appserver.runtime.DesktopCodexRuntimeConfiguration
import io.github.ciurlaro.codexmobile.appserver.runtime.DesktopCodexRuntimeFactory
import okio.Path

fun desktopRuntimeFactory(executable: Path, workingDirectory: Path) = DesktopCodexRuntimeFactory(
    DesktopCodexRuntimeConfiguration(executable, workingDirectory),
)
