import io.github.codex_agent_labs.codexmobile.agent.runtime.NodeCodexPlatform
import okio.Path

fun nodePlatform(bundleDirectory: Path, dataDirectory: Path) =
    NodeCodexPlatform(bundleDirectory, dataDirectory)
