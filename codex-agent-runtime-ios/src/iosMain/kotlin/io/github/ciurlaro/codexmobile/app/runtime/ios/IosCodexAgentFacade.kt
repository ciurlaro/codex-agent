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
    private val eventSubscriptions = mutableMapOf<Long, IosCodexEventSubscription>()
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
            processUpstreamEvent(event)
        }
    }

    fun observeEvents(observer: (AgentEvent) -> Unit): IosCodexObservation {
        var observerId = -1L
        val subscription = IosCodexEventSubscription(
            retainedBacklog = ArrayDeque(),
            liveMailbox = Channel(EVENT_CAPACITY),
        )
        val job = try {
            observerScope.launch(start = CoroutineStart.LAZY) {
                try {
                    subscription.retainedBacklog.forEach(observer)
                    subscription.retainedBacklog.clear()
                    for (event in subscription.liveMailbox) observer(event)
                } catch (_: IosCodexObserverOverflowException) {
                    observer(observerOverflowEvent())
                } finally {
                    unregisterEventObserver(observerId, subscription)
                }
            }
        } catch (error: Throwable) {
            subscription.liveMailbox.close()
            throw error
        }
        lock.locked {
            if (closed) {
                return@locked
            } else {
                observerId = nextObserverId++
                subscription.retainedBacklog.addAll(backlog)
                backlog.clear()
                backlogOverflowed = false
                eventSubscriptions[observerId] = subscription
            }
        }
        if (observerId < 0) {
            job.cancel()
            return IosCodexObservation {}
        }
        if (!job.start()) {
            unregisterEventObserver(observerId, subscription)
        }
        return IosCodexObservation {
            unregisterEventObserver(observerId, subscription)
            job.cancel()
        }
    }

    fun observeAuthenticationState(
        observer: (IosCodexAuthenticationState) -> Unit,
    ): IosCodexObservation {
        val mailbox = Channel<IosCodexAuthenticationState>(Channel.UNLIMITED)
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

    fun markAuthenticating(generation: Long) = updateAuthenticationState(
        IosCodexAuthenticationState(
            status = IosCodexAuthenticationStatus.AUTHENTICATING,
            generation = generation,
        ),
    )

    fun markSignedOut(generation: Long, reason: String? = null) = updateAuthenticationState(
        IosCodexAuthenticationState(
            status = IosCodexAuthenticationStatus.SIGNED_OUT,
            generation = generation,
            terminalReason = reason,
        ),
    )

    fun markClosed(generation: Long, reason: String) {
        val channels = lock.locked {
            if (closed) return
            state = IosCodexAuthenticationState(
                status = IosCodexAuthenticationStatus.CLOSED,
                generation = generation,
                terminalReason = reason,
            )
            stateMailboxes.values.forEach { it.trySend(state) }
            closed = true
            backlog.clear()
            (eventSubscriptions.values.map { it.liveMailbox } + stateMailboxes.values).also {
                eventSubscriptions.clear()
                stateMailboxes.clear()
            }
        }
        channels.forEach { it.close() }
        upstreamCollection.cancel()
        observerRoot.complete()
    }

    suspend fun joinObservers() = observerRoot.join()

    private fun processUpstreamEvent(event: AgentEvent) {
        val overflowed = mutableListOf<Channel<AgentEvent>>()
        lock.locked {
            if (closed) return
            distributeLocked(event, overflowed)
            authenticationStateFor(event)?.let(::updateAuthenticationStateLocked)
        }
        overflowed.forEach { it.close(IosCodexObserverOverflowException()) }
    }

    private fun distributeLocked(
        event: AgentEvent,
        overflowed: MutableList<Channel<AgentEvent>>,
    ) {
        if (eventSubscriptions.isEmpty()) {
            if (!backlogOverflowed && backlog.size < EVENT_CAPACITY) {
                backlog.addLast(event)
            } else if (!backlogOverflowed) {
                backlog.clear()
                backlog.addLast(backlogOverflowEvent())
                backlogOverflowed = true
            }
            return
        }
        val subscriptions = eventSubscriptions.iterator()
        while (subscriptions.hasNext()) {
            val (_, subscription) = subscriptions.next()
            val mailbox = subscription.liveMailbox
            if (mailbox.trySend(event).isFailure) {
                subscriptions.remove()
                overflowed += mailbox
            }
        }
    }

    private fun updateAuthenticationState(value: IosCodexAuthenticationState) {
        lock.locked {
            if (closed) return
            updateAuthenticationStateLocked(value)
        }
    }

    private fun updateAuthenticationStateLocked(value: IosCodexAuthenticationState) {
        if (value.generation < state.generation) return
        state = value
        stateMailboxes.values.forEach { check(it.trySend(value).isSuccess) }
    }

    private fun authenticationStateFor(event: AgentEvent): IosCodexAuthenticationState? =
        when (event) {
            is AgentEvent.AuthenticationRequired -> state.copy(
                status = IosCodexAuthenticationStatus.AUTHENTICATING,
                pendingSignInUrl = event.signInUrl,
                terminalReason = null,
            )
            is AgentEvent.DeviceCodeAuthenticationRequired -> state.copy(
                status = IosCodexAuthenticationStatus.AUTHENTICATING,
                pendingSignInUrl = null,
                terminalReason = null,
            )
            AgentEvent.Authenticated -> state.copy(
                status = IosCodexAuthenticationStatus.AUTHENTICATED,
                pendingSignInUrl = null,
                terminalReason = null,
            )
            is AgentEvent.Failure -> if (event.sessionId == null) {
                state.copy(
                    status = IosCodexAuthenticationStatus.SIGNED_OUT,
                    pendingSignInUrl = null,
                    terminalReason = event.message,
                )
            } else {
                null
            }
            else -> null
        }

    private fun unregisterEventObserver(
        observerId: Long,
        subscription: IosCodexEventSubscription,
    ) {
        lock.locked {
            if (eventSubscriptions[observerId] === subscription) eventSubscriptions.remove(observerId)
        }
        subscription.liveMailbox.close()
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

private class IosCodexEventSubscription(
    val retainedBacklog: ArrayDeque<AgentEvent>,
    val liveMailbox: Channel<AgentEvent>,
)

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
    val generation: Long = 0,
    private val cancellationHandler: () -> Unit = {},
) : AutoCloseable {
    private val lock = NSLock()
    private var closed = false

    fun cancel() {
        val shouldClose = lock.locked {
            if (closed) false else true.also { closed = true }
        }
        if (!shouldClose) return
        job.cancel()
        cancellationHandler()
    }

    override fun close() {
        lock.locked { closed = true }
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
