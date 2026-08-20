package io.github.codex_agent_labs.codexmobile.agent.runtime

import io.github.codex_agent_labs.codexmobile.agent.CodexAuthorizationBrowser
import io.github.codex_agent_labs.codexmobile.agent.CodexAuthorizationPresentation
import io.github.codex_agent_labs.codexmobile.agent.CodexAuthorizationUrl
import io.github.codex_agent_labs.codexmobile.agent.CodexPlatform
import io.github.codex_agent_labs.codexmobile.agent.CodexRuntimeFeature
import io.github.codex_agent_labs.codexmobile.agent.CodexStorageRoots
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspace
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceResolution
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceStore
import io.github.codex_agent_labs.codexmobile.agent.PreparedCodexRuntime
import io.github.codex_agent_labs.codexmobile.appserver.runtime.NodeCodexRuntimeConfiguration
import io.github.codex_agent_labs.codexmobile.appserver.runtime.NodeCodexRuntimeFactory
import io.github.codex_agent_labs.codexmobile.appserver.runtime.currentNodeTarget
import io.github.codex_agent_labs.codexmobile.appserver.runtime.nodeCodexDistribution
import io.github.codex_agent_labs.codexmobile.appserver.runtime.nodeHost
import io.github.codex_agent_labs.codexmobile.appserver.runtime.host.NodePathWorkspaceStore
import io.github.codex_agent_labs.codexmobile.appserver.runtime.host.NodeRuntimeBundleInstaller
import io.github.codex_agent_labs.codexmobile.appserver.runtime.host.RuntimeBundleDescriptor
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
