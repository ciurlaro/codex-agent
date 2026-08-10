package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentEvent
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSLock

enum class IosCodexAuthenticationStatus {
    SIGNED_OUT,
    AUTHENTICATING,
    AUTHENTICATED,
    CLOSED,
}

data class IosCodexAuthenticationState(
    val status: IosCodexAuthenticationStatus,
    val generation: Long = 0,
    val pendingSignInUrl: String? = null,
    val terminalReason: String? = null,
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
    private val authenticationMutex = Mutex()
    private val authenticationLock = NSLock()
    private var authenticationGeneration = 0L
    private var closingAuthenticationGeneration = 0L

    @Volatile
    private var closed = false

    private val closeController = IosFacadeCloseController(
        rejectNewOperations = {
            closed = true
            authenticationLock.locked {
                closingAuthenticationGeneration = ++authenticationGeneration
            }
        },
        publishClosed = {
            events.markClosed(
                generation = closingAuthenticationGeneration,
                reason = "Codex Agent facade is closed.",
            )
        },
        cancelHierarchy = rootJob::cancel,
        closeClient = client::close,
        joinHierarchy = {
            rootJob.join()
            events.joinObservers()
        },
    )

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

    fun cancelAuthentication(completion: (String?) -> Unit): IosCodexOperation {
        val generation = nextAuthenticationGeneration()
        return launchOperation(completion, generation = generation) {
            authenticationMutex.withLock {
                client.cancelAuthentication()
                events.markSignedOut(generation, "ChatGPT authentication was canceled.")
            }
        }
    }

    fun signOut(completion: (String?) -> Unit): IosCodexOperation {
        val generation = nextAuthenticationGeneration()
        return launchOperation(completion, generation = generation) {
            authenticationMutex.withLock {
                client.signOut()
                events.markSignedOut(generation, "ChatGPT authentication was canceled by sign-out.")
            }
        }
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
        val generation = nextAuthenticationGeneration()
        return launchOperation(
            completion = completion,
            generation = generation,
            onCancel = {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    authenticationMutex.withLock {
                        if (
                            isCurrentAuthentication(generation) &&
                            events.authenticationState.status == IosCodexAuthenticationStatus.AUTHENTICATING
                        ) {
                            runCatching { client.cancelAuthentication() }
                            if (isCurrentAuthentication(generation)) {
                                events.markSignedOut(
                                    generation,
                                    "ChatGPT authentication was canceled.",
                                )
                            }
                        }
                    }
                }
            },
        ) {
            authenticationMutex.withLock {
                if (!isCurrentAuthentication(generation)) return@withLock
                events.markAuthenticating(generation)
                try {
                    operation()
                } catch (error: Throwable) {
                    if (
                        events.authenticationState.generation == generation &&
                        events.authenticationState.status == IosCodexAuthenticationStatus.AUTHENTICATING
                    ) {
                        events.markSignedOut(
                            generation,
                            error.message ?: "iOS Codex authentication failed",
                        )
                    }
                    throw error
                }
            }
        }
    }

    private fun nextAuthenticationGeneration(): Long = authenticationLock.locked {
        check(!closed) { "iOS Codex facade is closed" }
        ++authenticationGeneration
    }

    private fun isCurrentAuthentication(generation: Long): Boolean =
        authenticationLock.locked { !closed && authenticationGeneration == generation }

    private fun launchOperation(
        completion: (String?) -> Unit,
        generation: Long = 0,
        onCancel: () -> Unit = {},
        operation: suspend () -> Unit,
    ): IosCodexOperation {
        check(!closed) { "iOS Codex facade is closed" }
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
        return IosCodexOperation(job, generation, onCancel)
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

    override fun close() = closeController.close()

    internal suspend fun closeAndJoin() = closeController.closeAndJoin()

    private companion object {
        const val ACCEPTANCE_INPUT = "acceptance-input.txt"
        const val ACCEPTANCE_OUTPUT = "acceptance-output.txt"
        const val ACCEPTANCE_CONTENT = "ChatGPT browser-login local workspace acceptance\n"
        const val ACCEPTANCE_SENTINEL = "Waiting for Codex\n"
        const val ACCEPTANCE_TIMEOUT_MILLIS = 180_000L
        const val ACCEPTANCE_POLL_MILLIS = 500L
    }
}

private fun io.github.ciurlaro.codexmobile.agent.BuiltInToolResult.requireSuccess() =
    apply { check(success) { text() } }

private fun io.github.ciurlaro.codexmobile.agent.BuiltInToolResult.text(): String =
    (content.singleOrNull() as? BuiltInToolContent.Text)?.value
        ?: error("Workspace tool returned unexpected content")
