import io.github.ciurlaro.codexmobile.agent.runtime.NodeCodexPlatform
import okio.Path

fun nodePlatform(bundleDirectory: Path, dataDirectory: Path) =
    NodeCodexPlatform(bundleDirectory, dataDirectory)
