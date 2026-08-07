package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.BuiltInToolContent
import io.github.ciurlaro.codexmobile.agent.CodexAgentClient
import io.github.ciurlaro.codexmobile.agent.CodexAuthenticationMethod
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class IosCodexAuthenticationStatus {
    SIGNED_OUT,
    AUTHENTICATING,
    AUTHENTICATED,
    CLOSED,
}

data class IosCodexAuthenticationState(
    val status: IosCodexAuthenticationStatus,
    val pendingSignInUrl: String? = null,
)

class IosCodexAgentFacade(
    private val configuration: IosCodexRuntimeConfiguration,
    clientVersion: String,
) : AutoCloseable {
    private val rootJob = SupervisorJob()
    private val scope = CoroutineScope(rootJob + Dispatchers.Default)
    private val runtimeFactory = IosCodexRuntimeFactory(configuration)
    private val client = CodexAgentClient(
        runtimeFactory = runtimeFactory,
        clientVersion = clientVersion,
        builtInToolDispatcher = runtimeFactory.workspaceTools,
    )
    private val events = IosCodexEventBroadcast(client.events, scope)

    @Volatile
    private var closed = false

    val authenticationState: IosCodexAuthenticationState
        get() = events.authenticationState

    fun observeEvents(observer: (AgentEvent) -> Unit): IosCodexObservation =
        events.observeEvents(observer)

    fun observeAuthenticationState(
        observer: (IosCodexAuthenticationState) -> Unit,
    ): IosCodexObservation = events.observeAuthenticationState(observer)

    fun authenticateWithApiKey(
        apiKey: String,
        completion: (String?) -> Unit,
    ): IosCodexOperation = authenticate(completion) {
        client.authenticate(CodexAuthenticationMethod.ApiKey(apiKey))
    }

    fun authenticateWithChatGpt(completion: (String?) -> Unit): IosCodexOperation =
        authenticate(completion) {
            client.authenticate(CodexAuthenticationMethod.ChatGptBrowser)
        }

    fun cancelAuthentication(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) {
            client.cancelAuthentication()
            events.markSignedOut()
        }

    fun signOut(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) {
            client.signOut()
            events.markSignedOut()
        }

    fun runWorkspaceAcceptance(completion: (String?) -> Unit): IosCodexOperation =
        launchOperation(completion) {
            workspaceTool(
                "write_file",
                "path" to ACCEPTANCE_INPUT,
                "content" to ACCEPTANCE_CONTENT,
            ).requireSuccess()
            workspaceTool(
                "write_file",
                "path" to ACCEPTANCE_OUTPUT,
                "content" to ACCEPTANCE_SENTINEL,
            ).requireSuccess()
            val session = client.openSession(
                settings = AgentRuntimeSettings(
                    approvalPreset = AgentApprovalPreset.NEVER,
                    workingDirectory = configuration.workspacePath,
                ),
            )
            client.sendTurn(
                session,
                AgentTurnRequest(
                    prompt = "Use read_file to read $ACCEPTANCE_INPUT, then use apply_patch to replace the complete contents of $ACCEPTANCE_OUTPUT with exactly what you read. Do not include extra text in the file.",
                    approvalPreset = AgentApprovalPreset.NEVER,
                    workingDirectory = configuration.workspacePath,
                ),
            )
            withTimeout(ACCEPTANCE_TIMEOUT_MILLIS) {
                while (true) {
                    val output = workspaceTool("read_file", "path" to ACCEPTANCE_OUTPUT)
                        .requireSuccess()
                        .text()
                    when (output) {
                        ACCEPTANCE_CONTENT -> return@withTimeout
                        ACCEPTANCE_SENTINEL -> delay(ACCEPTANCE_POLL_MILLIS)
                        else -> error("Model wrote unexpected acceptance output")
                    }
                }
            }
        }

    private fun authenticate(
        completion: (String?) -> Unit,
        operation: suspend () -> Unit,
    ): IosCodexOperation {
        check(!closed) { "iOS Codex facade is closed" }
        events.markAuthenticating()
        return launchOperation(completion) {
            try {
                operation()
            } catch (error: Throwable) {
                events.markSignedOut()
                throw error
            }
        }
    }

    private fun launchOperation(
        completion: (String?) -> Unit,
        operation: suspend () -> Unit,
    ): IosCodexOperation {
        check(!closed) { "iOS Codex facade is closed" }
        return IosCodexOperation(
            scope.launch {
                try {
                    operation()
                    completion(null)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    completion(error.message ?: "iOS Codex operation failed")
                }
            },
        )
    }

    private suspend fun workspaceTool(
        tool: String,
        vararg arguments: Pair<String, String>,
    ) = runtimeFactory.workspaceTools.execute(
        BuiltInToolCall(
            threadId = "manual-chatgpt-acceptance",
            turnId = "manual-chatgpt-acceptance",
            callId = "manual-$tool",
            pluginId = "ios-local-workspace",
            tool = tool,
            arguments = buildJsonObject {
                arguments.forEach { (name, value) -> put(name, value) }
            },
            workspace = configuration.workspacePath,
            argumentsHash = "manual-chatgpt-acceptance",
        ),
    )

    override fun close() {
        if (closed) return
        closed = true
        events.markClosed()
        rootJob.cancel()
        runBlocking { rootJob.join() }
        client.close()
    }

    private companion object {
        const val ACCEPTANCE_INPUT = "acceptance-input.txt"
        const val ACCEPTANCE_OUTPUT = "acceptance-output.txt"
        const val ACCEPTANCE_CONTENT = "ChatGPT browser-login local workspace acceptance\n"
        const val ACCEPTANCE_SENTINEL = "Waiting for Codex\n"
        const val ACCEPTANCE_TIMEOUT_MILLIS = 180_000L
        const val ACCEPTANCE_POLL_MILLIS = 500L
    }
}

internal class IosCodexEventBroadcast(
    upstream: Flow<AgentEvent>,
    private val scope: CoroutineScope,
) {
    private val broadcast = MutableSharedFlow<AgentEvent>(extraBufferCapacity = EVENT_CAPACITY)
    private val state = MutableStateFlow(
        IosCodexAuthenticationState(IosCodexAuthenticationStatus.SIGNED_OUT),
    )

    val authenticationState: IosCodexAuthenticationState
        get() = state.value

    @Suppress("unused")
    private val upstreamCollection = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        upstream.collect { event ->
            when (event) {
                is AgentEvent.AuthenticationRequired -> state.value = IosCodexAuthenticationState(
                    IosCodexAuthenticationStatus.AUTHENTICATING,
                    event.signInUrl,
                )
                is AgentEvent.DeviceCodeAuthenticationRequired -> markAuthenticating()
                AgentEvent.Authenticated -> state.value = IosCodexAuthenticationState(
                    IosCodexAuthenticationStatus.AUTHENTICATED,
                )
                is AgentEvent.Failure -> if (event.sessionId == null) markSignedOut()
                else -> Unit
            }
            broadcast.emit(event)
        }
    }

    fun observeEvents(observer: (AgentEvent) -> Unit) = IosCodexObservation(
        scope.launch(start = CoroutineStart.UNDISPATCHED) { broadcast.collect(observer) },
    )

    fun observeAuthenticationState(observer: (IosCodexAuthenticationState) -> Unit) =
        IosCodexObservation(
            scope.launch(start = CoroutineStart.UNDISPATCHED) { state.collect(observer) },
        )

    fun markAuthenticating() {
        state.value = IosCodexAuthenticationState(IosCodexAuthenticationStatus.AUTHENTICATING)
    }

    fun markSignedOut() {
        state.value = IosCodexAuthenticationState(IosCodexAuthenticationStatus.SIGNED_OUT)
    }

    fun markClosed() {
        state.value = IosCodexAuthenticationState(IosCodexAuthenticationStatus.CLOSED)
    }

    private companion object {
        const val EVENT_CAPACITY = 64
    }
}

private fun io.github.ciurlaro.codexmobile.agent.BuiltInToolResult.requireSuccess() =
    apply { check(success) { text() } }

private fun io.github.ciurlaro.codexmobile.agent.BuiltInToolResult.text(): String =
    (content.singleOrNull() as? BuiltInToolContent.Text)?.value
        ?: error("Workspace tool returned unexpected content")

class IosCodexObservation internal constructor(
    private val job: Job,
) : AutoCloseable {
    override fun close() = job.cancel()
}

class IosCodexOperation internal constructor(
    private val job: Job,
) : AutoCloseable {
    override fun close() = job.cancel()
}
