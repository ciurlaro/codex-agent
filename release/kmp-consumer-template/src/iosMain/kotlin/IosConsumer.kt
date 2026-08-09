import io.github.ciurlaro.codexmobile.app.runtime.ios.IosCodexCredentialProtection
import io.github.ciurlaro.codexmobile.app.runtime.ios.IosCodexRuntimeConfiguration
import io.github.ciurlaro.codexmobile.app.runtime.ios.IosCodexRuntimeFactory

fun iosRuntimeFactory(sandbox: String): IosCodexRuntimeFactory = IosCodexRuntimeFactory(
    IosCodexRuntimeConfiguration(
        sandboxRootPath = sandbox,
        workspacePath = "$sandbox/Documents/CodexWorkspace",
        codexHomePath = "$sandbox/Library/Application Support/CodexAgent",
        credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
    ),
)
