import io.github.ciurlaro.codexmobile.agent.CodexAgentClient
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory

fun publicClient(factory: CodexRuntimeFactory): CodexAgentClient = CodexAgentClient(factory)
