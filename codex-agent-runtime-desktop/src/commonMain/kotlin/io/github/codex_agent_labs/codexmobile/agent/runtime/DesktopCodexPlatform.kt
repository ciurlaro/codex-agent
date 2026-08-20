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
import io.github.codex_agent_labs.codexmobile.appserver.runtime.DesktopCodexRuntimeConfiguration
import io.github.codex_agent_labs.codexmobile.appserver.runtime.DesktopCodexRuntimeFactory
import io.github.codex_agent_labs.codexmobile.appserver.runtime.currentDesktopTarget
import io.github.codex_agent_labs.codexmobile.appserver.runtime.desktopCodexDistribution
import io.github.codex_agent_labs.codexmobile.appserver.runtime.makeDesktopExecutable
import io.github.codex_agent_labs.codexmobile.appserver.runtime.openDesktopAuthorizationUrl
import io.github.codex_agent_labs.codexmobile.appserver.runtime.host.PathWorkspaceStore
import io.github.codex_agent_labs.codexmobile.appserver.runtime.host.RuntimeBundleDescriptor
import io.github.codex_agent_labs.codexmobile.appserver.runtime.host.RuntimeBundleInstaller
import okio.Path
import okio.Path.Companion.toPath

/**
 * Desktop support for [io.github.codex_agent_labs.codexmobile.agent.CodexHost].
 *
 * A `null` `storageRoots` uses `dataDirectory/cache` and
 * `dataDirectory/state`. Pass [CodexStorageRoots] explicitly to override those
 * roots; an empty value disables client cache and state persistence.
 */
public class DesktopCodexPlatform public constructor(
    bundleDirectory: Path,
    dataDirectory: Path,
    storageRoots: CodexStorageRoots? = null,
) : CodexPlatform {
    private val pathWorkspaceStore = PathWorkspaceStore(dataDirectory)
    private val effectiveStorageRoots = resolveDesktopStorageRoots(dataDirectory, storageRoots)
    public override val workspaceStore: CodexWorkspaceStore = pathWorkspaceStore
    public override val authorizationBrowser: CodexAuthorizationBrowser = DesktopCodexAuthorizationBrowser
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

    public override suspend fun prepare(workspace: CodexWorkspace): PreparedCodexRuntime {
        val resolved = pathWorkspaceStore.resolve(workspace.path)
        require(resolved is CodexWorkspaceResolution.Available) { "Workspace is unavailable" }
        val runtime = installer.install()
        return PreparedCodexRuntime(
            runtimeFactory = DesktopCodexRuntimeFactory(
                DesktopCodexRuntimeConfiguration(
                    appServerExecutable = runtime.appServer,
                    processSupervisorExecutable = runtime.supervisor,
                    processSupervisorSha256 = runtime.supervisorSha256,
                    workingDirectory = resolved.workspace.path.toPath(),
                ),
            ),
            workspacePath = resolved.workspace.path,
            features = desktopCodexRuntimeFeatures,
            storageRoots = effectiveStorageRoots,
        )
    }
}

internal val desktopCodexRuntimeFeatures = setOf(
    CodexRuntimeFeature.SHELL_COMMANDS,
    CodexRuntimeFeature.SKILLS,
    CodexRuntimeFeature.HOOKS,
    CodexRuntimeFeature.PLUGINS,
    CodexRuntimeFeature.CONNECTORS,
    CodexRuntimeFeature.MCP_SERVERS,
)

internal fun resolveDesktopStorageRoots(
    dataDirectory: Path,
    configured: CodexStorageRoots?,
): CodexStorageRoots = configured ?: CodexStorageRoots(
    cacheRoot = dataDirectory / "cache",
    stateRoot = dataDirectory / "state",
)

private object DesktopCodexAuthorizationBrowser : CodexAuthorizationBrowser {
    override fun open(url: CodexAuthorizationUrl): CodexAuthorizationPresentation {
        openDesktopAuthorizationUrl(url.value)
        return CodexAuthorizationPresentation.None
    }
}
