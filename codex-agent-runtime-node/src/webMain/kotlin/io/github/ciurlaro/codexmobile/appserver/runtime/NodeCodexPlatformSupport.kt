package io.github.ciurlaro.codexmobile.appserver.runtime

import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationBrowser
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationPresentation
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationUrl
import io.github.ciurlaro.codexmobile.agent.CodexPlatformSupport
import io.github.ciurlaro.codexmobile.agent.CodexPreparedRuntime
import io.github.ciurlaro.codexmobile.agent.CodexWorkspace
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceStore
import io.github.ciurlaro.codexmobile.appserver.runtime.host.NodePathWorkspaceStore
import io.github.ciurlaro.codexmobile.appserver.runtime.host.NodeRuntimeBundleInstaller
import io.github.ciurlaro.codexmobile.appserver.runtime.host.RuntimeBundleDescriptor
import okio.Path
import okio.Path.Companion.toPath

class NodeCodexPlatformSupport(
    bundleDirectory: Path,
    dataDirectory: Path,
) : CodexPlatformSupport {
    private val workspaceStore = NodePathWorkspaceStore(dataDirectory)
    override val workspaces: CodexWorkspaceStore = workspaceStore
    override val browser: CodexAuthorizationBrowser = NodeCodexAuthorizationBrowser
    private val distribution = nodeCodexDistribution(currentNodeTarget())
    private val installer = NodeRuntimeBundleInstaller(
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
    )

    override suspend fun prepare(workspace: CodexWorkspace): CodexPreparedRuntime {
        val resolved = workspaceStore.resolve(workspace.path)
        require(resolved is CodexWorkspaceResolution.Available) { "Workspace is unavailable" }
        val runtime = installer.install()
        return CodexPreparedRuntime(
            runtimeFactory = NodeCodexRuntimeFactory(
                NodeCodexRuntimeConfiguration(
                    appServerExecutable = runtime.appServer,
                    workingDirectory = resolved.workspace.path.toPath(),
                    processSupervisorExecutable = runtime.supervisor,
                    processSupervisorSha256 = runtime.supervisorSha256,
                ),
            ),
            workspacePath = resolved.workspace.path,
        )
    }
}

private object NodeCodexAuthorizationBrowser : CodexAuthorizationBrowser {
    override fun open(url: CodexAuthorizationUrl): CodexAuthorizationPresentation {
        nodeHost.openUrl(url.value)
        return CodexAuthorizationPresentation.None
    }
}
