package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationBrowser
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationPresentation
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationUrl
import io.github.ciurlaro.codexmobile.agent.CodexPlatformSupport
import io.github.ciurlaro.codexmobile.agent.CodexPreparedRuntime
import io.github.ciurlaro.codexmobile.agent.CodexWorkspace
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceStore
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory
import kotlinx.coroutines.flow.Flow
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication

class IosCodexPlatformSupport(
    sandboxRootPath: String,
    credentialProtection: IosCodexCredentialProtection,
    override val browser: CodexAuthorizationBrowser = IosSystemAuthorizationBrowser,
    codexHomePath: String = "$sandboxRootPath/Library/Application Support/CodexAgent",
    bookmarkPath: String = "$codexHomePath/workspace.bookmark",
) : CodexPlatformSupport {
    private val workspaceStore = IosCodexWorkspaceStore(sandboxRootPath, bookmarkPath)
    private val baseConfiguration = IosCodexRuntimeConfiguration(
        sandboxRootPath = sandboxRootPath,
        workspacePath = sandboxRootPath,
        credentialProtection = credentialProtection,
        codexHomePath = codexHomePath,
    )
    override val workspaces: CodexWorkspaceStore = workspaceStore

    override suspend fun prepare(workspace: CodexWorkspace): CodexPreparedRuntime {
        val validation = workspaceStore.acquire(workspace.path)
        val securityScoped = validation.securityScoped
        validation.close()
        val configuration = baseConfiguration.copy(
            workspacePath = workspace.path,
            securityScopedWorkspace = securityScoped,
        )
        val factory = BookmarkIosRuntimeFactory(configuration, workspaceStore)
        return CodexPreparedRuntime(
            runtimeFactory = factory,
            workspacePath = workspace.path,
            builtInToolDispatcher = factory.workspaceTools,
        )
    }
}

object IosSystemAuthorizationBrowser : CodexAuthorizationBrowser {
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
