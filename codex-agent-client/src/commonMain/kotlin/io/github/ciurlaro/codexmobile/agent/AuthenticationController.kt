package io.github.ciurlaro.codexmobile.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public enum class AgentAuthenticationStatus {
    SIGNED_OUT,
    AUTHENTICATING,
    AUTHENTICATED,
}

public data class AgentAuthenticationState(
    public val status: AgentAuthenticationStatus = AgentAuthenticationStatus.SIGNED_OUT,
    public val pendingSignInUrl: CodexAuthorizationUrl? = null,
    public val deviceVerificationUrl: CodexAuthorizationUrl? = null,
    public val deviceUserCode: String? = null,
    public val failure: CodexFailure? = null,
)

internal class AuthenticationController(
    private val client: CodexAgentClient,
    scope: CoroutineScope,
    private val authorizationBrowser: CodexAuthorizationBrowser? = null,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(AgentAuthenticationState())
    private var presentation: CodexAuthorizationPresentation? = null
    private var closed = false

    internal val state: StateFlow<AgentAuthenticationState> = mutableState.asStateFlow()

    private val observation: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        client.events.collect(::process)
    }

    internal suspend fun authenticate(
        method: CodexAuthenticationMethod = CodexAuthenticationMethod.ChatGptBrowser,
    ) {
        val previous = lock.withLock {
            check(!closed) { "Authentication is closed" }
            if (mutableState.value.status == AgentAuthenticationStatus.AUTHENTICATED) return
            presentation.also {
                presentation = null
                mutableState.value = AgentAuthenticationState(status = AgentAuthenticationStatus.AUTHENTICATING)
            }
        }
        try {
            previous?.close()
            client.authenticate(method)
        } catch (error: Throwable) {
            if (error is CancellationException) {
                withContext(NonCancellable) {
                    lock.withLock {
                        if (!closed) mutableState.value = AgentAuthenticationState()
                    }
                }
                throw error
            }
            val exception = error.asCodexOperationException(
                "authentication_failed",
                "Authentication failed",
            )
            withContext(NonCancellable) {
                lock.withLock {
                    if (!closed) markSignedOut(exception.failure)
                }
            }
            throw exception
        }
    }

    internal suspend fun cancel() {
        lock.withLock { check(!closed) { "Authentication is closed" } }
        try {
            client.cancelAuthentication()
        } catch (error: Throwable) {
            throw error.asCodexOperationException("authentication_failed", "Could not cancel authentication")
        }
        withContext(NonCancellable) {
            val owned = lock.withLock {
                if (closed) return@withContext
                presentation.also {
                    presentation = null
                    markSignedOut(
                        codexFailure(
                            "authentication_failed",
                            "Authentication was canceled.",
                            "Authentication was canceled.",
                            true,
                        ),
                    )
                }
            }
            closePublicPresentation(owned, "Could not cancel authentication")
        }
        currentCoroutineContext().ensureActive()
    }

    internal suspend fun signOut() {
        lock.withLock { check(!closed) { "Authentication is closed" } }
        try {
            client.signOut()
        } catch (error: Throwable) {
            throw error.asCodexOperationException("authentication_failed", "Could not sign out")
        }
        withContext(NonCancellable) {
            val owned = lock.withLock {
                if (closed) return@withContext
                presentation.also {
                    presentation = null
                    mutableState.value = AgentAuthenticationState()
                }
            }
            closePublicPresentation(owned, "Could not sign out")
        }
        currentCoroutineContext().ensureActive()
    }

    internal suspend fun close() {
        val owned = lock.withLock {
            if (closed) return
            closed = true
            observation.cancel()
            presentation.also { presentation = null }
        }
        observation.join()
        owned?.close()
    }

    private suspend fun process(event: AgentEvent) {
        when (event) {
            is AgentEvent.AuthenticationRequired -> openBrowser(CodexAuthorizationUrl.chatGpt(event.signInUrl))
            is AgentEvent.DeviceCodeAuthenticationRequired -> lock.withLock {
                if (!closed) {
                    mutableState.value = mutableState.value.copy(
                        status = AgentAuthenticationStatus.AUTHENTICATING,
                        deviceVerificationUrl = CodexAuthorizationUrl.external(event.verificationUrl),
                        deviceUserCode = event.userCode,
                        failure = null,
                    )
                }
            }
            AgentEvent.Authenticated -> closePresentation {
                AgentAuthenticationState(status = AgentAuthenticationStatus.AUTHENTICATED)
            }
            is AgentEvent.AuthenticationFailed -> closePresentation {
                signedOutFailure("authentication_failed", event.message)
            }
            is AgentEvent.Failure -> if (
                event.conversationId == null &&
                mutableState.value.status == AgentAuthenticationStatus.AUTHENTICATING
            ) {
                closePresentation {
                    AgentAuthenticationState(
                        status = AgentAuthenticationStatus.SIGNED_OUT,
                        failure = codexFailure(event.code, event.message, "Authentication failed", event.isRecoverable),
                    )
                }
            }
            else -> Unit
        }
    }

    private suspend fun openBrowser(url: CodexAuthorizationUrl) {
        val previous = lock.withLock {
            if (closed) return
            presentation.also { presentation = null }
        }
        runCatching { previous?.close() }
        val opened = try {
            authorizationBrowser?.open(url)
        } catch (error: Throwable) {
            val failure = error.asCodexOperationException(
                "authorization_browser_failed",
                "Could not open the authorization URL",
            ).failure
            lock.withLock { if (!closed) markSignedOut(failure) }
            return
        }
        withContext(NonCancellable) {
            val closeOpened = lock.withLock {
                if (closed) {
                    true
                } else {
                    presentation = opened
                    mutableState.value = mutableState.value.copy(
                        status = AgentAuthenticationStatus.AUTHENTICATING,
                        pendingSignInUrl = url,
                        failure = null,
                    )
                    false
                }
            }
            if (closeOpened) runCatching { opened?.close() }
        }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun closePresentation(state: () -> AgentAuthenticationState) {
        val owned = lock.withLock {
            if (closed) return
            presentation.also {
                presentation = null
                mutableState.value = state()
            }
        }
        runCatching { owned?.close() }
    }

    private suspend fun closePublicPresentation(
        owned: CodexAuthorizationPresentation?,
        fallback: String,
    ) {
        try {
            owned?.close()
        } catch (error: Throwable) {
            val exception = error.asCodexOperationException("authentication_failed", fallback)
            withContext(NonCancellable) {
                lock.withLock { if (!closed) markSignedOut(exception.failure) }
            }
            throw exception
        }
    }

    private fun signedOutFailure(code: String, message: String): AgentAuthenticationState =
        AgentAuthenticationState(
            status = AgentAuthenticationStatus.SIGNED_OUT,
            failure = codexFailure(code, message, "Authentication failed", true),
        )

    private fun markSignedOut(failure: CodexFailure) {
        mutableState.value = AgentAuthenticationState(
            status = AgentAuthenticationStatus.SIGNED_OUT,
            failure = failure,
        )
    }
}
