package io.github.ciurlaro.codexmobile.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AgentAuthenticationStatus {
    SIGNED_OUT,
    AUTHENTICATING,
    AUTHENTICATED,
    CLOSED,
}

data class AgentAuthenticationState(
    val status: AgentAuthenticationStatus,
    val generation: Long = 0,
    val pendingSignInUrl: CodexAuthorizationUrl? = null,
    val deviceVerificationUrl: CodexAuthorizationUrl? = null,
    val deviceUserCode: String? = null,
    val terminalReason: String? = null,
)

class AgentAuthenticationSession(
    private val client: CodexAgentClient,
    private val scope: CoroutineScope,
    private val browser: CodexAuthorizationBrowser? = null,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(AgentAuthenticationState(AgentAuthenticationStatus.SIGNED_OUT))
    private var method: CodexAuthenticationMethod = CodexAuthenticationMethod.ChatGptBrowser
    private var presentation: CodexAuthorizationPresentation? = null
    private var closed = false

    val state: StateFlow<AgentAuthenticationState> = mutableState

    private val observation: Job = scope.launch {
        client.events.collect(::process)
    }

    suspend fun authenticate(method: CodexAuthenticationMethod = CodexAuthenticationMethod.ChatGptBrowser) {
        lock.withLock {
            check(!closed) { "Authentication session is closed" }
            closePresentation()
            this.method = method
            mutableState.value = AgentAuthenticationState(
                status = AgentAuthenticationStatus.AUTHENTICATING,
                generation = mutableState.value.generation + 1,
            )
            try {
                client.authenticate(method)
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(
                    status = AgentAuthenticationStatus.SIGNED_OUT,
                    terminalReason = error.message ?: "Authentication failed",
                )
                throw error
            }
        }
    }

    suspend fun retry() = authenticate(lock.withLock { method })

    suspend fun cancel() = lock.withLock {
        check(!closed) { "Authentication session is closed" }
        client.cancelAuthentication()
        closePresentation()
        markSignedOut("Authentication was canceled.")
    }

    suspend fun signOut() = lock.withLock {
        check(!closed) { "Authentication session is closed" }
        client.signOut()
        closePresentation()
        markSignedOut("Authentication was canceled by sign-out.")
    }

    suspend fun close() {
        lock.withLock {
            if (closed) return@withLock
            closed = true
            observation.cancel()
            closePresentation()
            mutableState.value = AgentAuthenticationState(
                status = AgentAuthenticationStatus.CLOSED,
                generation = mutableState.value.generation + 1,
                terminalReason = "Authentication session is closed.",
            )
        }
    }

    private suspend fun process(event: AgentEvent) {
        lock.withLock {
            if (closed) return@withLock
            when (event) {
                is AgentEvent.AuthenticationRequired -> {
                    val url = CodexAuthorizationUrl.chatGpt(event.signInUrl)
                    closePresentation()
                    val opened = runCatching { browser?.open(url) }.getOrElse {
                        markSignedOut(it.message ?: "Could not open the authorization URL")
                        return@withLock
                    }
                    presentation = opened
                    mutableState.value = mutableState.value.copy(
                        status = AgentAuthenticationStatus.AUTHENTICATING,
                        pendingSignInUrl = url,
                        terminalReason = null,
                    )
                }
                is AgentEvent.DeviceCodeAuthenticationRequired -> mutableState.value = mutableState.value.copy(
                    status = AgentAuthenticationStatus.AUTHENTICATING,
                    deviceVerificationUrl = CodexAuthorizationUrl.external(event.verificationUrl),
                    deviceUserCode = event.userCode,
                    terminalReason = null,
                )
                AgentEvent.Authenticated -> {
                    closePresentation()
                    mutableState.value = mutableState.value.copy(
                        status = AgentAuthenticationStatus.AUTHENTICATED,
                        pendingSignInUrl = null,
                        deviceVerificationUrl = null,
                        deviceUserCode = null,
                        terminalReason = null,
                    )
                }
                is AgentEvent.Failure -> if (event.sessionId == null) {
                    closePresentation()
                    markSignedOut(event.message)
                }
                else -> Unit
            }
        }
    }

    private fun markSignedOut(reason: String) {
        mutableState.value = mutableState.value.copy(
            status = AgentAuthenticationStatus.SIGNED_OUT,
            pendingSignInUrl = null,
            deviceVerificationUrl = null,
            deviceUserCode = null,
            terminalReason = reason,
        )
    }

    private fun closePresentation() {
        presentation?.close()
        presentation = null
    }
}
