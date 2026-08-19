package io.github.ciurlaro.codexmobile.agent.runtime

import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationBrowser
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationPresentation
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationUrl
import io.github.ciurlaro.codexmobile.agent.CodexPlatform
import io.github.ciurlaro.codexmobile.agent.CodexRuntimeFeature
import io.github.ciurlaro.codexmobile.agent.CodexStorageRoots
import io.github.ciurlaro.codexmobile.agent.CodexWorkspace
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceStore
import io.github.ciurlaro.codexmobile.agent.PreparedCodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.NodeCodexRuntimeConfiguration
import io.github.ciurlaro.codexmobile.appserver.runtime.NodeCodexRuntimeFactory
import io.github.ciurlaro.codexmobile.appserver.runtime.currentNodeTarget
import io.github.ciurlaro.codexmobile.appserver.runtime.nodeCodexDistribution
import io.github.ciurlaro.codexmobile.appserver.runtime.nodeHost
import io.github.ciurlaro.codexmobile.appserver.runtime.host.NodePathWorkspaceStore
import io.github.ciurlaro.codexmobile.appserver.runtime.host.NodeRuntimeBundleInstaller
import io.github.ciurlaro.codexmobile.appserver.runtime.host.RuntimeBundleDescriptor
import okio.Path
import okio.Path.Companion.toPath

public class NodeCodexPlatform(
    bundleDirectory: Path,
    dataDirectory: Path,
    storageRoots: CodexStorageRoots? = null,
) : CodexPlatform {
    private val pathWorkspaceStore = NodePathWorkspaceStore(dataDirectory)
    private val resolvedStorageRoots = resolveNodeStorageRoots(dataDirectory, storageRoots)
    public override val workspaceStore: CodexWorkspaceStore = pathWorkspaceStore
    public override val authorizationBrowser: CodexAuthorizationBrowser = NodeCodexAuthorizationBrowser
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

    public override suspend fun prepare(workspace: CodexWorkspace): PreparedCodexRuntime {
        val resolved = pathWorkspaceStore.resolve(workspace.path)
        require(resolved is CodexWorkspaceResolution.Available) { "Workspace is unavailable" }
        val runtime = installer.install()
        return PreparedCodexRuntime(
            runtimeFactory = NodeCodexRuntimeFactory(
                NodeCodexRuntimeConfiguration(
                    appServerExecutable = runtime.appServer,
                    workingDirectory = resolved.workspace.path.toPath(),
                    processSupervisorExecutable = runtime.supervisor,
                    processSupervisorSha256 = runtime.supervisorSha256,
                ),
            ),
            workspacePath = resolved.workspace.path,
            features = nodeCodexRuntimeFeatures,
            storageRoots = resolvedStorageRoots,
        )
    }
}

internal val nodeCodexRuntimeFeatures = setOf(
    CodexRuntimeFeature.SHELL_COMMANDS,
    CodexRuntimeFeature.SKILLS,
    CodexRuntimeFeature.HOOKS,
    CodexRuntimeFeature.PLUGINS,
    CodexRuntimeFeature.CONNECTORS,
    CodexRuntimeFeature.MCP_SERVERS,
)

internal fun resolveNodeStorageRoots(
    dataDirectory: Path,
    configured: CodexStorageRoots?,
): CodexStorageRoots = configured ?: CodexStorageRoots(
    cacheRoot = dataDirectory / "cache",
    stateRoot = dataDirectory / "state",
)

private object NodeCodexAuthorizationBrowser : CodexAuthorizationBrowser {
    override fun open(url: CodexAuthorizationUrl): CodexAuthorizationPresentation {
        nodeHost.openUrl(url.value)
        return CodexAuthorizationPresentation.None
    }
}
