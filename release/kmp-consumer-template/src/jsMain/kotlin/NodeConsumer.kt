import io.github.ciurlaro.codexmobile.appserver.runtime.NodeCodexRuntimeConfiguration
import io.github.ciurlaro.codexmobile.appserver.runtime.NodeCodexRuntimeFactory
import okio.Path

fun nodeRuntimeFactory(
    appServerExecutable: Path,
    processSupervisorExecutable: Path,
    processSupervisorSha256: String,
    workingDirectory: Path,
) = NodeCodexRuntimeFactory(
    NodeCodexRuntimeConfiguration(
        appServerExecutable = appServerExecutable,
        processSupervisorExecutable = processSupervisorExecutable,
        processSupervisorSha256 = processSupervisorSha256,
        workingDirectory = workingDirectory,
    ),
)
