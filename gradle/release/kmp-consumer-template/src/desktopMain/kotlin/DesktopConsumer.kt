import io.github.ciurlaro.codexmobile.appserver.runtime.DesktopCodexRuntimeConfiguration
import io.github.ciurlaro.codexmobile.appserver.runtime.DesktopCodexRuntimeFactory
import okio.Path

fun desktopRuntimeFactory(
    appServerExecutable: Path,
    processSupervisorExecutable: Path,
    processSupervisorSha256: String,
    workingDirectory: Path,
) = DesktopCodexRuntimeFactory(
    DesktopCodexRuntimeConfiguration(
        appServerExecutable = appServerExecutable,
        processSupervisorExecutable = processSupervisorExecutable,
        processSupervisorSha256 = processSupervisorSha256,
        workingDirectory = workingDirectory,
    ),
)
