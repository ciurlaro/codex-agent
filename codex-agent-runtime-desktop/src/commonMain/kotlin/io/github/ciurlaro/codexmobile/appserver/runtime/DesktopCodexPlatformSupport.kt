package io.github.ciurlaro.codexmobile.appserver.runtime

import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationBrowser
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationPresentation
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationUrl
import io.github.ciurlaro.codexmobile.agent.CodexPlatformSupport
import io.github.ciurlaro.codexmobile.agent.CodexPreparedRuntime
import io.github.ciurlaro.codexmobile.agent.CodexWorkspace
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceStore
import io.github.ciurlaro.codexmobile.appserver.runtime.host.PathWorkspaceStore
import io.github.ciurlaro.codexmobile.appserver.runtime.host.RuntimeBundleDescriptor
import io.github.ciurlaro.codexmobile.appserver.runtime.host.RuntimeBundleInstaller
import okio.Path
import okio.Path.Companion.toPath

class DesktopCodexPlatformSupport(
    bundleDirectory: Path,
    dataDirectory: Path,
) : CodexPlatformSupport {
    private val workspaceStore = PathWorkspaceStore(dataDirectory)
    override val workspaces: CodexWorkspaceStore = workspaceStore
    override val browser: CodexAuthorizationBrowser = DesktopCodexAuthorizationBrowser
    private val distribution = desktopCodexDistribution(currentDesktopTarget())
    private val installer = RuntimeBundleInstaller(
        bundleDirectory = bundleDirectory,
        dataDirectory = dataDirectory,
        descriptor = RuntimeBundleDescriptor(
            libraryVersion = distribution.libraryVersion,
            appServerVersion = distribution.appServerVersion,
            target = distribution.target,
            classifier = distribution.classifier,
            appServerName = distribution.executableName,
            appServerSha256 = distribution.binarySha256,
            supervisorName = distribution.supervisorExecutableName,
        ),
        makeExecutable = ::makeDesktopExecutable,
    )

    override suspend fun prepare(workspace: CodexWorkspace): CodexPreparedRuntime {
        val resolved = workspaceStore.resolve(workspace.path)
        require(resolved is CodexWorkspaceResolution.Available) { "Workspace is unavailable" }
        val runtime = installer.install()
        return CodexPreparedRuntime(
            runtimeFactory = DesktopCodexRuntimeFactory(
                DesktopCodexRuntimeConfiguration(
                    appServerExecutable = runtime.appServer,
                    processSupervisorExecutable = runtime.supervisor,
                    processSupervisorSha256 = runtime.supervisorSha256,
                    workingDirectory = resolved.workspace.path.toPath(),
                ),
            ),
            workspacePath = resolved.workspace.path,
        )
    }
}

private object DesktopCodexAuthorizationBrowser : CodexAuthorizationBrowser {
    override fun open(url: CodexAuthorizationUrl): CodexAuthorizationPresentation {
        openDesktopAuthorizationUrl(url.value)
        return CodexAuthorizationPresentation.None
    }
}

internal expect fun openDesktopAuthorizationUrl(url: String)

internal expect fun makeDesktopExecutable(path: Path)
