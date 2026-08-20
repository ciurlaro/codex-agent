package io.github.codex_agent_labs.codexmobile.agent.runtime

import android.content.Context
import io.github.codex_agent_labs.codexmobile.agent.CodexAuthorizationBrowser
import io.github.codex_agent_labs.codexmobile.agent.CodexPlatform
import io.github.codex_agent_labs.codexmobile.agent.CodexRuntimeFeature
import io.github.codex_agent_labs.codexmobile.agent.CodexStorageRoots
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspace
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceResolution
import io.github.codex_agent_labs.codexmobile.agent.CodexWorkspaceStore
import io.github.codex_agent_labs.codexmobile.agent.PreparedCodexRuntime
import io.github.codex_agent_labs.codexmobile.app.runtime.bootstrap.AndroidCodexAuthorizationBrowser
import io.github.codex_agent_labs.codexmobile.app.runtime.bootstrap.AndroidCodexRuntimeFactory
import io.github.codex_agent_labs.codexmobile.app.runtime.bootstrap.AndroidCodexWorkspaceStore
import okio.Path
import okio.Path.Companion.toPath

/**
 * Android support for [io.github.codex_agent_labs.codexmobile.agent.CodexHost].
 *
 * A `null` `storageRoots` uses `cacheDir/codex-agent` for cache data and
 * `noBackupFilesDir/codex-agent` for durable state. Pass [CodexStorageRoots]
 * explicitly to override those roots; an empty value disables client cache
 * and state persistence.
 */
public class AndroidCodexPlatform public constructor(
    context: Context,
    storageRoots: CodexStorageRoots? = null,
) : CodexPlatform {
    private val appContext = context.applicationContext
    private val effectiveStorageRoots = resolveAndroidStorageRoots(
        cacheDirectory = appContext.cacheDir.absolutePath.toPath(),
        stateDirectory = appContext.noBackupFilesDir.absolutePath.toPath(),
        configured = storageRoots,
    )

    private val androidWorkspaceStore = AndroidCodexWorkspaceStore(appContext)
    public override val workspaceStore: CodexWorkspaceStore = androidWorkspaceStore
    public override val authorizationBrowser: CodexAuthorizationBrowser =
        AndroidCodexAuthorizationBrowser(appContext)

    public override suspend fun prepare(workspace: CodexWorkspace): PreparedCodexRuntime {
        val available = androidWorkspaceStore.resolve(workspace.path) as? CodexWorkspaceResolution.Available
            ?: error("Android workspace is unavailable; restore or select it again")
        return PreparedCodexRuntime(
            runtimeFactory = AndroidCodexRuntimeFactory(appContext),
            workspacePath = available.workspace.path,
            features = androidCodexRuntimeFeatures,
            storageRoots = effectiveStorageRoots,
        )
    }
}

internal val androidCodexRuntimeFeatures = setOf(
    CodexRuntimeFeature.SHELL_COMMANDS,
    CodexRuntimeFeature.SKILLS,
    CodexRuntimeFeature.HOOKS,
    CodexRuntimeFeature.PLUGINS,
    CodexRuntimeFeature.CONNECTORS,
    CodexRuntimeFeature.MCP_SERVERS,
)

internal fun resolveAndroidStorageRoots(
    cacheDirectory: Path,
    stateDirectory: Path,
    configured: CodexStorageRoots?,
): CodexStorageRoots = configured ?: CodexStorageRoots(
    cacheRoot = cacheDirectory / STORAGE_DIRECTORY,
    stateRoot = stateDirectory / STORAGE_DIRECTORY,
)

private const val STORAGE_DIRECTORY = "codex-agent"
