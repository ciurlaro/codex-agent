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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
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
    private val authenticationMutex = Mutex()
    private val authenticationLock = NSLock()
    private var authenticationGeneration = 0L

    @Volatile
    private var closed = false

    private val closeController = IosFacadeCloseController(
        rejectNewOperations = {
            closed = true
            authenticationLock.locked { authenticationGeneration++ }
        },
        publishClosed = events::markClosed,
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
        return launchOperation(completion) {
            authenticationMutex.withLock {
                client.cancelAuthentication()
                if (isCurrentAuthentication(generation)) events.markSignedOut()
            }
        }
    }

    fun signOut(completion: (String?) -> Unit): IosCodexOperation {
        val generation = nextAuthenticationGeneration()
        return launchOperation(completion) {
            authenticationMutex.withLock {
                client.signOut()
                if (isCurrentAuthentication(generation)) events.markSignedOut()
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
        events.markAuthenticating()
        return launchOperation(
            completion = completion,
            onCancel = {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    authenticationMutex.withLock {
                        if (
                            isCurrentAuthentication(generation) &&
                            events.authenticationState.status == IosCodexAuthenticationStatus.AUTHENTICATING
                        ) {
                            runCatching { client.cancelAuthentication() }
                            if (isCurrentAuthentication(generation)) events.markSignedOut()
                        }
                    }
                }
            },
        ) {
            authenticationMutex.withLock {
                if (!isCurrentAuthentication(generation)) return@withLock
                try {
                    operation()
                } catch (error: Throwable) {
                    if (isCurrentAuthentication(generation)) events.markSignedOut()
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
        return IosCodexOperation(job, onCancel)
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

internal class IosFacadeCloseController(
    private val rejectNewOperations: () -> Unit,
    private val publishClosed: () -> Unit,
    private val cancelHierarchy: () -> Unit,
    private val closeClient: () -> Unit,
    private val joinHierarchy: suspend () -> Unit,
    private val timeoutMillis: Long = CLOSE_TIMEOUT_MILLIS,
) {
    private val closeStarted = CompletableDeferred<Unit>()
    private val closeCompleted = CompletableDeferred<Result<Unit>>()
    private val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun close() {
        if (!closeStarted.complete(Unit)) return
        rejectNewOperations()
        publishClosed()
        cancelHierarchy()
        val clientClose = shutdownScope.async { closeClient() }
        shutdownScope.launch {
            closeCompleted.complete(
                runCatching {
                    withTimeout(timeoutMillis) {
                        val clientFailure = runCatching { clientClose.await() }.exceptionOrNull()
                        val joinFailure = runCatching { joinHierarchy() }.exceptionOrNull()
                        (clientFailure ?: joinFailure)?.let { throw it }
                        Unit
                    }
                },
            )
        }
    }

    suspend fun closeAndJoin() {
        close()
        closeCompleted.await().getOrThrow()
    }

    private companion object {
        const val CLOSE_TIMEOUT_MILLIS = 5_000L
    }
}

internal class IosCodexEventBroadcast(
    upstream: Flow<AgentEvent>,
    upstreamScope: CoroutineScope,
) {
    private val lock = NSLock()
    private val observerRoot = SupervisorJob()
    private val observerScope = CoroutineScope(observerRoot + Dispatchers.Default)
    private val eventMailboxes = mutableMapOf<Long, Channel<AgentEvent>>()
    private val stateMailboxes = mutableMapOf<Long, Channel<IosCodexAuthenticationState>>()
    private val backlog = ArrayDeque<AgentEvent>(EVENT_CAPACITY)
    private var backlogOverflowed = false
    private var nextObserverId = 0L
    private var closed = false

    @Volatile
    private var state = IosCodexAuthenticationState(IosCodexAuthenticationStatus.SIGNED_OUT)

    val authenticationState: IosCodexAuthenticationState
        get() = state

    private val upstreamCollection = upstreamScope.launch(start = CoroutineStart.UNDISPATCHED) {
        upstream.collect { event ->
            distribute(event)
            when (event) {
                is AgentEvent.AuthenticationRequired -> updateAuthenticationState(
                    IosCodexAuthenticationState(
                        IosCodexAuthenticationStatus.AUTHENTICATING,
                        event.signInUrl,
                    ),
                )
                is AgentEvent.DeviceCodeAuthenticationRequired -> markAuthenticating()
                AgentEvent.Authenticated -> updateAuthenticationState(
                    IosCodexAuthenticationState(IosCodexAuthenticationStatus.AUTHENTICATED),
                )
                is AgentEvent.Failure -> if (event.sessionId == null) markSignedOut()
                else -> Unit
            }
        }
    }

    fun observeEvents(observer: (AgentEvent) -> Unit): IosCodexObservation {
        val mailbox = Channel<AgentEvent>(EVENT_CAPACITY)
        var observerId = -1L
        val initial = lock.locked {
            if (closed) {
                emptyList()
            } else {
                observerId = nextObserverId++
                eventMailboxes[observerId] = mailbox
                backlog.toList().also {
                    backlog.clear()
                    backlogOverflowed = false
                }
            }
        }
        if (observerId < 0) {
            mailbox.close()
            return IosCodexObservation {}
        }
        initial.forEach { check(mailbox.trySend(it).isSuccess) }
        val job = observerScope.launch {
            try {
                for (event in mailbox) observer(event)
            } catch (_: IosCodexObserverOverflowException) {
                observer(observerOverflowEvent())
            } finally {
                unregisterEventObserver(observerId, mailbox)
            }
        }
        return IosCodexObservation {
            unregisterEventObserver(observerId, mailbox)
            job.cancel()
        }
    }

    fun observeAuthenticationState(
        observer: (IosCodexAuthenticationState) -> Unit,
    ): IosCodexObservation {
        val mailbox = Channel<IosCodexAuthenticationState>(
            capacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        var observerId = -1L
        val initial = lock.locked {
            if (!closed) {
                observerId = nextObserverId++
                stateMailboxes[observerId] = mailbox
            }
            state
        }
        check(mailbox.trySend(initial).isSuccess)
        if (observerId < 0) mailbox.close()
        val job = observerScope.launch {
            try {
                for (value in mailbox) observer(value)
            } finally {
                unregisterStateObserver(observerId, mailbox)
            }
        }
        return IosCodexObservation {
            unregisterStateObserver(observerId, mailbox)
            job.cancel()
        }
    }

    fun markAuthenticating() = updateAuthenticationState(
        IosCodexAuthenticationState(IosCodexAuthenticationStatus.AUTHENTICATING),
    )

    fun markSignedOut() = updateAuthenticationState(
        IosCodexAuthenticationState(IosCodexAuthenticationStatus.SIGNED_OUT),
    )

    fun markClosed() {
        val channels = lock.locked {
            if (closed) return
            state = IosCodexAuthenticationState(IosCodexAuthenticationStatus.CLOSED)
            stateMailboxes.values.forEach { it.trySend(state) }
            closed = true
            backlog.clear()
            (eventMailboxes.values + stateMailboxes.values).also {
                eventMailboxes.clear()
                stateMailboxes.clear()
            }
        }
        channels.forEach { it.close() }
        upstreamCollection.cancel()
        observerRoot.complete()
    }

    suspend fun joinObservers() = observerRoot.join()

    private fun distribute(event: AgentEvent) {
        val overflowed = mutableListOf<Channel<AgentEvent>>()
        lock.locked {
            if (closed) return
            if (eventMailboxes.isEmpty()) {
                if (!backlogOverflowed && backlog.size < EVENT_CAPACITY) {
                    backlog.addLast(event)
                } else if (!backlogOverflowed) {
                    backlog.clear()
                    backlog.addLast(backlogOverflowEvent())
                    backlogOverflowed = true
                }
                return
            }
            val subscriptions = eventMailboxes.iterator()
            while (subscriptions.hasNext()) {
                val (_, mailbox) = subscriptions.next()
                if (mailbox.trySend(event).isFailure) {
                    subscriptions.remove()
                    overflowed += mailbox
                }
            }
        }
        overflowed.forEach { it.close(IosCodexObserverOverflowException()) }
    }

    private fun updateAuthenticationState(value: IosCodexAuthenticationState) {
        lock.locked {
            if (closed) return
            state = value
            stateMailboxes.values.forEach { it.trySend(value) }
        }
    }

    private fun unregisterEventObserver(observerId: Long, mailbox: Channel<AgentEvent>) {
        lock.locked {
            if (eventMailboxes[observerId] === mailbox) eventMailboxes.remove(observerId)
        }
        mailbox.close()
    }

    private fun unregisterStateObserver(
        observerId: Long,
        mailbox: Channel<IosCodexAuthenticationState>,
    ) {
        lock.locked {
            if (stateMailboxes[observerId] === mailbox) stateMailboxes.remove(observerId)
        }
        mailbox.close()
    }

    private fun observerOverflowEvent() = AgentEvent.Failure(
        sessionId = null,
        code = "ios_observer_overflow",
        message = "The iOS event observer was closed because its 64-event mailbox overflowed.",
        recoverable = true,
    )

    private fun backlogOverflowEvent() = AgentEvent.Failure(
        sessionId = null,
        code = "ios_event_backlog_overflow",
        message = "The iOS event backlog overflowed while no observers were registered.",
        recoverable = true,
    )

    private companion object {
        const val EVENT_CAPACITY = 64
    }
}

private class IosCodexObserverOverflowException : IllegalStateException("iOS observer overflow")

private fun io.github.ciurlaro.codexmobile.agent.BuiltInToolResult.requireSuccess() =
    apply { check(success) { text() } }

private fun io.github.ciurlaro.codexmobile.agent.BuiltInToolResult.text(): String =
    (content.singleOrNull() as? BuiltInToolContent.Text)?.value
        ?: error("Workspace tool returned unexpected content")

class IosCodexObservation internal constructor(
    private val closeHandler: () -> Unit,
) : AutoCloseable {
    private val lock = NSLock()
    private var closed = false

    internal constructor(job: Job) : this(job::cancel)

    override fun close() {
        val shouldClose = lock.locked {
            if (closed) false else true.also { closed = true }
        }
        if (shouldClose) closeHandler()
    }
}

class IosCodexOperation internal constructor(
    private val job: Job,
    private val cancellationHandler: () -> Unit = {},
) : AutoCloseable {
    private val lock = NSLock()
    private var closed = false

    override fun close() {
        val shouldClose = lock.locked {
            if (closed) false else true.also { closed = true }
        }
        if (!shouldClose) return
        job.cancel()
        cancellationHandler()
    }
}

private inline fun <T> NSLock.locked(block: () -> T): T {
    lock()
    return try {
        block()
    } finally {
        unlock()
    }
}
