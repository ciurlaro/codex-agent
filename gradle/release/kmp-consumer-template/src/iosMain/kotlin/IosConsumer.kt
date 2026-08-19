import io.github.ciurlaro.codexmobile.agent.runtime.IosCodexCredentialProtection
import io.github.ciurlaro.codexmobile.agent.runtime.IosCodexPlatform

fun iosPlatform(sandbox: String) = IosCodexPlatform(
    sandboxRootPath = sandbox,
    credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
)
