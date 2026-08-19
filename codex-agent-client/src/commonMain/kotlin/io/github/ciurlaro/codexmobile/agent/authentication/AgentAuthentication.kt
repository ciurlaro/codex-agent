package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentConversation
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentElicitationAction
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentFormValue
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentHookActivity
import io.github.ciurlaro.codexmobile.agent.AgentHookCatalog
import io.github.ciurlaro.codexmobile.agent.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.agent.AgentHookTrustStatus
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentMessage
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.agent.AgentPluginDetail
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentPlanProgress
import io.github.ciurlaro.codexmobile.agent.AgentPlanStep
import io.github.ciurlaro.codexmobile.agent.AgentPlanStepStatus
import io.github.ciurlaro.codexmobile.agent.AgentConversationSettings
import io.github.ciurlaro.codexmobile.agent.AgentServiceTier
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkillChunk
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.ConversationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.KSerializer


internal suspend fun CodexAgentClient.authenticateAction(
    method: CodexAuthenticationMethod,
) = authMutex.withLock {
    connection.ensureStarted()
    if (stateLock.withLock { authenticated }) {
        emitAuthenticated()
        return@withLock
    }
    if (loginId != null) return@withLock

    val account = connection.request(
        AppServerClientMethods.AccountRead,
        GetAccountParams(refreshToken = false),
    ).account
    val accountMatches = when (method) {
        CodexAuthenticationMethod.ChatGptBrowser,
        CodexAuthenticationMethod.ChatGptDeviceCode,
        -> account is AccountChatgptAccount
        is CodexAuthenticationMethod.ApiKey -> account is AccountApiKeyAccount
    }
    if (accountMatches) {
        emitAuthenticated()
        return@withLock
    }

    if (method is CodexAuthenticationMethod.ApiKey) {
        check(
            connection.request(
                AppServerClientMethods.AccountLoginStart,
                LoginAccountParamsApiKey(method.value),
            ) is LoginAccountResponseApiKey,
        ) { "App-server returned an unexpected login method" }
        emitAuthenticated()
        return@withLock
    }

    loginStateLock.withLock {
        loginStarting = true
        loginCompletedDuringStart = null
    }
    var startedLoginId: String? = null
    try {
        val result = connection.request(
            AppServerClientMethods.AccountLoginStart,
            when (method) {
                CodexAuthenticationMethod.ChatGptBrowser -> LoginAccountParamsChatgpt(
                    appBrand = LoginAppBrand.CODEX,
                    useHostedLoginSuccessPage = true,
                )
                CodexAuthenticationMethod.ChatGptDeviceCode -> LoginAccountParamsChatgptDeviceCode()
                is CodexAuthenticationMethod.ApiKey -> error("API-key login is handled synchronously")
            },
        )
        startedLoginId = when (result) {
            is LoginAccountResponseChatgpt -> result.loginId
            is LoginAccountResponseChatgptDeviceCode -> result.loginId
            else -> error("App-server returned an unexpected login method")
        }
        withContext(NonCancellable) {
            val earlyCompletion = loginStateLock.withLock {
                loginStarting = false
                loginCompletedDuringStart
                    ?.takeIf { it.loginId == startedLoginId }
                    .also { loginCompletedDuringStart = null }
                    .also {
                        loginId = if (it == null && !stateLock.withLock { authenticated }) startedLoginId else null
                    }
            }
            when {
                earlyCompletion != null -> applyLoginCompletion(earlyCompletion)
                stateLock.withLock { authenticated } -> Unit
                result is LoginAccountResponseChatgpt -> eventsChannel.send(
                    AgentEvent.AuthenticationRequired(result.authUrl),
                )
                result is LoginAccountResponseChatgptDeviceCode -> eventsChannel.send(
                    AgentEvent.DeviceCodeAuthenticationRequired(
                        verificationUrl = result.verificationUrl,
                        userCode = result.userCode,
                    ),
                )
            }
        }
        currentCoroutineContext().ensureActive()
    } catch (error: Exception) {
        withContext(NonCancellable) {
            val acquiredLoginId = loginStateLock.withLock {
                loginStarting = false
                loginCompletedDuringStart = null
                startedLoginId?.takeIf { loginId == it }?.also {
                    loginId = null
                    cancelledLoginIds += it
                }
            }
            if (acquiredLoginId != null) {
                runCatching {
                    val status = connection.request(
                        AppServerClientMethods.AccountLoginCancel,
                        CancelLoginAccountParams(acquiredLoginId),
                    ).status
                    if (status == CancelLoginAccountStatus.NOT_FOUND) {
                        loginStateLock.withLock { cancelledLoginIds -= acquiredLoginId }
                    }
                }
            }
        }
        throw error
    }
}

internal suspend fun CodexAgentClient.cancelAuthenticationAction() = authMutex.withLock {
    connection.ensureStarted()
    val activeLoginId = loginStateLock.withLock {
        loginId?.also(cancelledLoginIds::add)
    } ?: return@withLock
    try {
        val status = connection.request(
            AppServerClientMethods.AccountLoginCancel,
            CancelLoginAccountParams(activeLoginId),
        ).status
        check(status == CancelLoginAccountStatus.CANCELED || status == CancelLoginAccountStatus.NOT_FOUND) {
            "Unexpected login cancellation status: $status"
        }
        withContext(NonCancellable) {
            loginStateLock.withLock {
                if (loginId == activeLoginId) loginId = null
                if (status == CancelLoginAccountStatus.NOT_FOUND) cancelledLoginIds -= activeLoginId
            }
        }
    } catch (error: Exception) {
        withContext(NonCancellable) {
            loginStateLock.withLock { cancelledLoginIds -= activeLoginId }
        }
        throw error
    }
}

internal suspend fun CodexAgentClient.signOutAction() {
    authMutex.withLock {
        connection.ensureStarted()
        connection.request(AppServerClientMethods.AccountLogout, Unit)
        stateLock.withLock { authenticated = false }
        loginStateLock.withLock {
            loginId = null
            loginStarting = false
            loginCompletedDuringStart = null
            cancelledLoginIds.clear()
        }
    }
    clearPluginCache()
    turnStateLock.withLock {
        shellStartupCompletions.values.forEach { it.complete(false) }
        shellStartupCompletions.clear()
        activeTurns.clear()
        startingTurns.clear()
        pendingTerminalsDuringStart.clear()
        recentTerminalTurnIds.clear()
        cancellingTurns.clear()
    }
    stateLock.withLock {
        userShellItems.clear()
        knownSkillPaths.clear()
        openedConversations.clear()
        conversationOwners.clear()
        conversationRuntimeSettings.clear()
    }
}

internal suspend fun CodexAgentClient.emitAuthenticatedAction() {
    val firstAuthentication = stateLock.withLock {
        if (authenticated) false else {
            authenticated = true
            true
        }
    }
    if (firstAuthentication) {
        eventsChannel.send(AgentEvent.Authenticated)
    }
}

internal suspend fun CodexAgentClient.applyLoginCompletionAction(completion: LoginCompletion) {
    if (completion.success) {
        emitAuthenticated()
    } else {
        eventsChannel.send(AgentEvent.AuthenticationFailed(completion.error ?: "Authentication failed"))
    }
}

internal suspend fun CodexAgentClient.handleConnectionFailureAction(code: String, message: String) {
    stateLock.withLock { authenticated = false }
    builtInToolGate.withLock { builtInEnablementLoaded = false }
    loginStateLock.withLock {
        loginId = null
        loginStarting = false
        loginCompletedDuringStart = null
        cancelledLoginIds.clear()
    }
    turnStateLock.withLock {
        shellStartupCompletions.values.forEach { it.complete(false) }
        shellStartupCompletions.clear()
        activeTurns.clear()
        startingTurns.clear()
        pendingTerminalsDuringStart.clear()
        recentTerminalTurnIds.clear()
        cancellingTurns.clear()
        cancelledTurns.clear()
    }
    stateLock.withLock {
        pendingApprovalRequests.clear()
        pendingBuiltInApprovals.clear()
        pendingElicitationRequests.clear()
        workItems.clear()
        userShellItems.clear()
        commentaryItems.clear()
        knownSkillPaths.clear()
        openedConversations.clear()
        conversationOwners.clear()
        conversationRuntimeSettings.clear()
    }
    eventsChannel.send(AgentEvent.Failure(null, code, message, isRecoverable = true))
}
