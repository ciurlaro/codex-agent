package io.github.ciurlaro.codexmobile.agent.runtime

import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationBrowser
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationPresentation
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationUrl
import io.github.ciurlaro.codexmobile.agent.CodexPlatform
import io.github.ciurlaro.codexmobile.agent.CodexRuntimeFeature
import io.github.ciurlaro.codexmobile.agent.CodexStorageRoots
import io.github.ciurlaro.codexmobile.agent.CodexWorkspace
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceSelection
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceStore
import io.github.ciurlaro.codexmobile.agent.PreparedCodexRuntime
import io.github.ciurlaro.codexmobile.app.runtime.ios.IosCodexRuntimeConfiguration
import io.github.ciurlaro.codexmobile.app.runtime.ios.IosCodexRuntimeFactory
import io.github.ciurlaro.codexmobile.app.runtime.ios.IosCodexWorkspaceStore
import io.github.ciurlaro.codexmobile.app.runtime.ios.IosWorkspaceLease
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import okio.Path.Companion.toPath
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

@Serializable
public enum class IosCodexCredentialProtection {
    WHEN_UNLOCKED,
    AFTER_FIRST_UNLOCK,
    WHILE_OPEN,
}

public class IosCodexWorkspaceSelection(public val url: NSURL) : CodexWorkspaceSelection

public class IosCodexPlatform(
    sandboxRootPath: String,
    credentialProtection: IosCodexCredentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
    public override val authorizationBrowser: CodexAuthorizationBrowser = IosSystemAuthorizationBrowser,
    codexHomePath: String = "$sandboxRootPath/Library/Application Support/CodexAgent",
    storageRoots: CodexStorageRoots? = null,
) : CodexPlatform {
    private val resolvedStorageRoots = resolveIosStorageRoots(sandboxRootPath, codexHomePath, storageRoots)
    private val iosWorkspaceStore = IosCodexWorkspaceStore(
        sandboxRootPath,
        "$codexHomePath/workspace.bookmark",
    )
    public override val workspaceStore: CodexWorkspaceStore = iosWorkspaceStore
    private val baseConfiguration = IosCodexRuntimeConfiguration(
        sandboxRootPath = sandboxRootPath,
        workspacePath = sandboxRootPath,
        credentialProtection = credentialProtection,
        codexHomePath = codexHomePath,
    )

    @Throws(Exception::class)
    public override suspend fun prepare(workspace: CodexWorkspace): PreparedCodexRuntime {
        val validation = iosWorkspaceStore.acquire(workspace.path)
        val securityScoped = validation.securityScoped
        validation.close()
        val configuration = baseConfiguration.copy(
            workspacePath = workspace.path,
            securityScopedWorkspace = securityScoped,
        )
        val factory = BookmarkIosRuntimeFactory(configuration, iosWorkspaceStore)
        return PreparedCodexRuntime(
            runtimeFactory = factory,
            workspacePath = workspace.path,
            features = iosCodexRuntimeFeatures,
            storageRoots = resolvedStorageRoots,
            toolProvider = factory.workspaceTools,
        )
    }
}

internal val iosCodexRuntimeFeatures = setOf(CodexRuntimeFeature.SKILLS)

internal fun resolveIosStorageRoots(
    sandboxRootPath: String,
    codexHomePath: String,
    configured: CodexStorageRoots?,
): CodexStorageRoots = configured ?: CodexStorageRoots(
    cacheRoot = "$sandboxRootPath/Library/Caches/CodexAgent".toPath(),
    stateRoot = codexHomePath.toPath(),
)

private object IosSystemAuthorizationBrowser : CodexAuthorizationBrowser {
    override fun open(url: CodexAuthorizationUrl): CodexAuthorizationPresentation {
        val nativeUrl = NSURL.URLWithString(url.value) ?: error("Authorization URL is invalid")
        NSOperationQueue.mainQueue.addOperationWithBlock {
            UIApplication.sharedApplication.openURL(nativeUrl, emptyMap<Any?, Any?>(), null)
        }
        return CodexAuthorizationPresentation.None
    }
}

private class BookmarkIosRuntimeFactory(
    private val configuration: IosCodexRuntimeConfiguration,
    private val workspaceStore: IosCodexWorkspaceStore,
) : CodexRuntimeFactory {
    private val delegate = IosCodexRuntimeFactory(configuration)
    val workspaceTools = delegate.workspaceTools

    override fun create(): CodexRuntime {
        val lease = workspaceStore.acquire(configuration.workspacePath)
        return try {
            LeasedIosRuntime(delegate.create(), lease)
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }
}

private class LeasedIosRuntime(
    private val delegate: CodexRuntime,
    private val lease: IosWorkspaceLease,
) : CodexRuntime {
    override val events: Flow<CodexRuntimeEvent> = delegate.events

    override suspend fun start() {
        try {
            delegate.start()
        } catch (error: Throwable) {
            lease.close()
            throw error
        }
    }

    override suspend fun send(line: CodexJsonLine) = delegate.send(line)

    override fun close() {
        try {
            delegate.close()
        } finally {
            lease.close()
        }
    }
}
