package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentAuthenticationState
import io.github.ciurlaro.codexmobile.agent.AgentConversationState
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentInteractionState
import io.github.ciurlaro.codexmobile.agent.AgentMcpAuthorizationState
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.CodexAuthenticationMethod
import io.github.ciurlaro.codexmobile.agent.CodexAuthorizationBrowser
import io.github.ciurlaro.codexmobile.agent.CodexHostSession
import io.github.ciurlaro.codexmobile.agent.CodexHostState
import io.github.ciurlaro.codexmobile.agent.SessionId
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import platform.Foundation.NSLock
import platform.Foundation.NSURL

data class IosCodexHostSnapshot(
    val host: CodexHostState,
    val authentication: AgentAuthenticationState? = null,
    val interactions: AgentInteractionState? = null,
    val mcpAuthorization: AgentMcpAuthorizationState? = null,
    val conversation: AgentConversationState? = null,
)

class IosCodexHostFacade(
    sandboxRootPath: String,
    credentialProtection: IosCodexCredentialProtection,
    clientVersion: String,
    browser: CodexAuthorizationBrowser = IosSystemAuthorizationBrowser,
) : AutoCloseable {
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + Dispatchers.Default)
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val closeLock = NSLock()
    private val host = CodexHostSession(
        IosCodexPlatformSupport(
            sandboxRootPath = sandboxRootPath,
            credentialProtection = credentialProtection,
            browser = browser,
        ),
        scope,
        clientVersion,
    )

    @Volatile
    private var closed = false

    val currentState: IosCodexHostSnapshot
        get() = snapshot(host.state.value)

    fun observeState(observer: (IosCodexHostSnapshot) -> Unit): IosCodexObservation =
        IosCodexObservation(
            scope.launch {
                host.state.collectLatest { hostState ->
                    coroutineScope {
                        observer(snapshot(hostState))
                        hostState.authentication?.let { authentication ->
                            launch {
                                authentication.state.drop(1).collect { observer(currentState) }
                            }
                        }
                        hostState.interactions?.let { interactions ->
                            launch {
                                interactions.state.drop(1).collect { observer(currentState) }
                            }
                        }
                        hostState.mcpAuthorization?.let { authorization ->
                            launch {
                                authorization.state.drop(1).collect { observer(currentState) }
                            }
                        }
                        hostState.conversation?.let { conversation ->
                            launch {
                                conversation.state.drop(1).collect { observer(currentState) }
                            }
                        }
                        awaitCancellation()
                    }
                }
            },
        )

    fun start(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { host.start() }

    fun selectWorkspace(url: NSURL, completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { host.selectWorkspace(IosCodexWorkspaceSelection(url)) }

    fun retry(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { host.retry() }

    fun openConversation(
        previousSessionId: String?,
        settings: AgentRuntimeSettings,
        completion: (String?) -> Unit,
    ): IosCodexOperation = launchOperation(completion) {
        host.openConversation(previousSessionId?.let(::SessionId), settings)
    }

    fun closeConversation(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { host.closeConversation() }

    fun authenticateWithChatGpt(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) {
            ready().authentication!!.authenticate(CodexAuthenticationMethod.ChatGptBrowser)
        }

    fun authenticateWithApiKey(apiKey: String, completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) {
            ready().authentication!!.authenticate(CodexAuthenticationMethod.ApiKey(apiKey))
        }

    fun cancelAuthentication(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { ready().authentication!!.cancel() }

    fun signOut(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { ready().authentication!!.signOut() }

    fun resolveApproval(
        requestId: String,
        decision: AgentApprovalDecision,
        completion: (String?) -> Unit,
    ): IosCodexOperation = launchOperation(completion) {
        ready().interactions!!.resolveApproval(requestId, decision)
    }

    fun resolveElicitation(
        requestId: String,
        response: AgentElicitationResponse,
        completion: (String?) -> Unit,
    ): IosCodexOperation = launchOperation(completion) {
        ready().interactions!!.resolveElicitation(requestId, response)
    }

    fun openElicitationUrl(requestId: String, completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { ready().interactions!!.openUrl(requestId) }

    fun startMcpAuthorization(
        serverName: String,
        sessionId: String?,
        completion: (String?) -> Unit,
    ): IosCodexOperation = launchOperation(completion) {
        ready().mcpAuthorization!!.start(serverName, sessionId?.let(::SessionId))
    }

    fun dismissMcpBrowser(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { ready().mcpAuthorization!!.dismissBrowser() }

    fun sendTurn(request: AgentTurnRequest, completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { conversation().send(request) }

    fun runShellCommand(command: String, completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { conversation().runShellCommand(command) }

    fun cancelTurn(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { conversation().cancel() }

    fun refreshConversation(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) { conversation().refresh() }

    override fun close() {
        val shouldClose = closeLock.locked {
            if (closed) false else true.also { closed = true }
        }
        if (!shouldClose) return
        shutdownScope.launch {
            try {
                host.close()
            } finally {
                rootJob.cancel()
            }
        }
    }

    internal suspend fun closeAndJoin() {
        closeLock.locked { closed = true }
        host.close()
        rootJob.cancel()
        rootJob.join()
    }

    private fun launchOperation(
        completion: (String?) -> Unit,
        operation: suspend () -> Unit,
    ): IosCodexOperation {
        check(!closed) { "iOS Codex host facade is closed" }
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                operation()
                completion(null)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                completion(error.message ?: "iOS Codex operation failed")
            }
        }
        return IosCodexOperation(job)
    }

    private fun ready(): CodexHostState = host.state.value.also {
        check(it.client != null) { "Codex host is not ready" }
    }

    private fun conversation() = checkNotNull(host.state.value.conversation) {
        "Codex conversation is not open"
    }

    private fun snapshot(state: CodexHostState) = IosCodexHostSnapshot(
        host = state,
        authentication = state.authentication?.state?.value,
        interactions = state.interactions?.state?.value,
        mcpAuthorization = state.mcpAuthorization?.state?.value,
        conversation = state.conversation?.state?.value,
    )
}
