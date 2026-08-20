import io.github.codex_agent_labs.codexmobile.agent.runtime.IosCodexCredentialProtection
import io.github.codex_agent_labs.codexmobile.agent.runtime.IosCodexPlatform

fun iosPlatform(sandbox: String) = IosCodexPlatform(
    sandboxRootPath = sandbox,
    credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
)
