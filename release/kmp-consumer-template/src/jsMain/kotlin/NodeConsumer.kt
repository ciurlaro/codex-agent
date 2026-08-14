import io.github.ciurlaro.codexmobile.appserver.runtime.NodeCodexRuntimeConfiguration
import io.github.ciurlaro.codexmobile.appserver.runtime.NodeCodexRuntimeFactory
import okio.Path

fun nodeRuntimeFactory(
    executable: Path,
    workingDirectory: Path,
    windowsSupervisorExecutable: Path? = null,
) = NodeCodexRuntimeFactory(
    NodeCodexRuntimeConfiguration(executable, workingDirectory, windowsSupervisorExecutable),
)
