package io.github.codex_agent_labs.codexmobile.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

public enum class AgentConversationStatus {
    NEW,
    OPENING,
    READY,
    STARTING_TURN,
    RUNNING_TURN,
    CANCELLING_TURN,
    RELOADING,
    FAILED,
    CLOSED,
}

public data class AgentTurnProgress(
    public val text: String = "",
    public val commentary: String = "",
    public val reasoning: String = "",
    public val plan: String = "",
    public val planProgress: AgentPlanProgress? = null,
    public val shellOutput: String = "",
    public val shellExitCode: Int? = null,
    public val workActivity: AgentWorkActivity? = null,
    public val hookActivities: List<AgentHookActivity> = emptyList(),
    public val isTruncated: Boolean = false,
)

public data class AgentConversationState(
    public val status: AgentConversationStatus = AgentConversationStatus.NEW,
    public val conversationId: ConversationId? = null,
    public val conversation: AgentConversation? = null,
    public val turnProgress: AgentTurnProgress = AgentTurnProgress(),
    public val model: String? = null,
    public val effort: String? = null,
    public val serviceTier: String? = null,
    public val failure: CodexFailure? = null,
) {
    public val canStartTurn: Boolean
        get() = conversationId != null &&
            (status == AgentConversationStatus.READY ||
                status == AgentConversationStatus.FAILED && failure?.isRecoverable == true)

    public val canReload: Boolean get() = canStartTurn

    public val canCancelTurn: Boolean
        get() = status == AgentConversationStatus.STARTING_TURN ||
            status == AgentConversationStatus.RUNNING_TURN
}

public class CodexConversation internal constructor(
    private val client: CodexAgentClient,
    scope: CoroutineScope,
    private val workingDirectory: String,
    features: Set<CodexRuntimeFeature>,
    private val onClose: suspend (CodexConversation) -> Unit,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(AgentConversationState())
    private var generation = 0L
    private var closed = false
    private var closeRequested = false
    private var closeStarted = false
    private val closeCompletion = CompletableDeferred<Unit>()
    private var turnStarting = false
    private var pendingCancellation = false
    private var cancellationSent = false
    private var startupCompletion: CompletableDeferred<Unit>? = null
    private var cancellationCompletion: CompletableDeferred<Unit>? = null
    private var reasoningSegment: Pair<String, Long>? = null
    private var planItemId: String? = null

    private val features = features.toSet()

    public val state: StateFlow<AgentConversationState> = mutableState.asStateFlow()

    private val eventObservation: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        client.events.collect(::process)
    }

    internal suspend fun open(
        conversationId: ConversationId? = null,
        settings: AgentConversationSettings = AgentConversationSettings(),
    ): ConversationId {
        val operation = lock.withLock {
            check(!closeRequested && !closed && mutableState.value.status == AgentConversationStatus.NEW) {
                "Conversation has already been opened"
            }
            generation += 1
            mutableState.value = mutableState.value.copy(status = AgentConversationStatus.OPENING)
            generation
        }
        val openedId = try {
            client.openConversation(conversationId, settings, workingDirectory, features, this)
        } catch (error: Throwable) {
            throw recordFailure(operation, "open_failed", "Could not open conversation", error)
        }
        withContext(NonCancellable) {
            lock.withLock {
                val current = mutableState.value
                if (!closed && generation == operation && current.status == AgentConversationStatus.OPENING) {
                    mutableState.value = current.copy(
                        status = if (conversationId == null) {
                            AgentConversationStatus.READY
                        } else {
                            AgentConversationStatus.RELOADING
                        },
                        conversationId = openedId,
                    )
                }
            }
        }
        if (conversationId != null) reloadCanonical(operation, clearProgress = true, throwOnFailure = true)
        return openedId
    }

    @Throws(Exception::class)
    public suspend fun send(prompt: String): Unit = send(AgentTurnRequest(prompt))

    @Throws(Exception::class)
    public suspend fun send(request: AgentTurnRequest): Unit {
        requireOpen()
        if (request.invocations.any { it is AgentInvocation.Skill }) requireFeature(CodexRuntimeFeature.SKILLS)
        if (request.invocations.any { it is AgentInvocation.Plugin }) requireFeature(CodexRuntimeFeature.PLUGINS)
        startTurn { conversationId -> client.sendTurn(conversationId, request, workingDirectory) }
    }

    @Throws(Exception::class)
    public suspend fun runShellCommand(command: String): Unit {
        requireOpen()
        requireFeature(CodexRuntimeFeature.SHELL_COMMANDS)
        startTurn { conversationId -> client.runShellCommand(conversationId, command) }
    }

    @Throws(Exception::class)
    public suspend fun cancelTurn(): Unit {
        cancelTurn(allowClosing = false)
    }

    private suspend fun cancelTurn(allowClosing: Boolean) {
        val request = lock.withLock {
            check(!closed && (allowClosing || !closeRequested)) { "Conversation is closed" }
            val current = mutableState.value
            when {
                turnStarting && current.status in ACTIVE_TURN_STATUSES -> {
                    pendingCancellation = true
                    mutableState.value = current.copy(status = AgentConversationStatus.CANCELLING_TURN)
                    null
                }
                current.status == AgentConversationStatus.STARTING_TURN -> {
                    pendingCancellation = true
                    mutableState.value = current.copy(status = AgentConversationStatus.CANCELLING_TURN)
                    null
                }
                current.status == AgentConversationStatus.CANCELLING_TURN -> null
                current.status == AgentConversationStatus.RUNNING_TURN -> {
                    if (cancellationSent) return@withLock null
                    cancellationSent = true
                    cancellationCompletion = CompletableDeferred()
                    mutableState.value = current.copy(status = AgentConversationStatus.CANCELLING_TURN)
                    generation to checkNotNull(current.conversationId)
                }
                else -> error("Conversation does not have an active turn")
            }
        }
        if (request != null) cancelClientTurn(request.first, request.second)
    }

    @Throws(Exception::class)
    public suspend fun reload(): Unit {
        val operation = lock.withLock {
            val current = mutableState.value
            check(!closed && !closeRequested) { "Conversation is closed" }
            check(current.canReload) { "Conversation cannot reload while ${current.status.name.lowercase()}" }
            checkNotNull(current.conversationId)
            generation += 1
            mutableState.value = current.copy(
                status = AgentConversationStatus.RELOADING,
                failure = null,
            )
            generation
        }
        reloadCanonical(operation, clearProgress = true, throwOnFailure = true)
    }

    @Throws(Exception::class)
    public suspend fun close(): Unit {
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                onClose(this@CodexConversation)
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { throw it.asCodexOperationException("close_failed", "Could not close conversation", false) }
        currentCoroutineContext().ensureActive()
    }

    internal suspend fun closeOwned(): Unit {
        val ownsClose = lock.withLock {
            if (closed) return
            closeRequested = true
            if (closeStarted) false else true.also { closeStarted = true }
        }
        if (!ownsClose) {
            closeCompletion.await()
            return
        }
        withContext(NonCancellable) {
            try {
                val shouldCancel = lock.withLock {
                    mutableState.value.status in ACTIVE_TURN_STATUSES
                }
                if (shouldCancel) {
                    withTimeoutOrNull(CLOSE_CANCELLATION_TIMEOUT_MILLIS) {
                        try {
                            cancelTurn(allowClosing = true)
                        } catch (_: Throwable) {
                            // Closing still releases resources after a best-effort interrupt.
                        }
                        val completions = lock.withLock {
                            listOfNotNull(startupCompletion, cancellationCompletion)
                        }
                        completions.forEach { it.await() }
                    }
                }
            } finally {
                lock.withLock {
                    eventObservation.cancel()
                }
                eventObservation.join()
                lock.withLock {
                    closed = true
                    generation += 1
                    mutableState.value = mutableState.value.copy(status = AgentConversationStatus.CLOSED)
                    closeCompletion.complete(Unit)
                }
            }
        }
    }

    private suspend fun startTurn(block: suspend (ConversationId) -> Unit) {
        val start = lock.withLock {
            val current = mutableState.value
            check(!closed && !closeRequested) { "Conversation is closed" }
            check(current.canStartTurn) { "Conversation is not ready for a turn" }
            generation += 1
            turnStarting = true
            pendingCancellation = false
            cancellationSent = false
            startupCompletion = CompletableDeferred()
            cancellationCompletion = null
            reasoningSegment = null
            planItemId = null
            mutableState.value = current.copy(
                status = AgentConversationStatus.STARTING_TURN,
                turnProgress = AgentTurnProgress(),
                failure = null,
            )
            generation to checkNotNull(current.conversationId)
        }
        try {
            block(start.second)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                lock.withLock {
                    turnStarting = false
                    pendingCancellation = false
                    startupCompletion?.complete(Unit)
                }
            }
            throw recordFailure(start.first, "turn_start_failed", "Could not start turn", error)
        }

        val cancelAfterStart = withContext(NonCancellable) {
            lock.withLock {
                turnStarting = false
                val current = mutableState.value
                val mayTransition = generation == start.first &&
                    !closed &&
                    current.status in STARTING_TURN_STATUSES
                val shouldCancel = mayTransition && pendingCancellation
                if (mayTransition) {
                    mutableState.value = mutableState.value.copy(
                        status = if (shouldCancel) {
                            AgentConversationStatus.CANCELLING_TURN
                        } else {
                            AgentConversationStatus.RUNNING_TURN
                        },
                    )
                }
                if (shouldCancel && !cancellationSent) {
                    cancellationSent = true
                    cancellationCompletion = CompletableDeferred()
                    start.first to start.second
                } else {
                    null
                }
            }
        }
        try {
            if (cancelAfterStart != null) {
                cancelClientTurn(cancelAfterStart.first, cancelAfterStart.second)
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock { startupCompletion?.complete(Unit) }
            }
        }
    }

    private suspend fun cancelClientTurn(operation: Long, conversationId: ConversationId) {
        try {
            client.cancelTurn(conversationId)
        } catch (error: Throwable) {
            throw recordFailure(operation, "cancel_failed", "Could not cancel turn", error)
        } finally {
            withContext(NonCancellable) {
                lock.withLock { cancellationCompletion?.complete(Unit) }
            }
        }
    }

    internal suspend fun process(event: AgentEvent): Unit {
        when (event) {
            is AgentEvent.ConversationOpened -> lock.withLock {
                val current = mutableState.value
                if (!closed &&
                    (current.conversationId == event.conversationId ||
                        current.status == AgentConversationStatus.OPENING && current.conversationId == null)
                ) {
                    mutableState.value = current.copy(
                        conversationId = event.conversationId,
                        model = event.model,
                        effort = event.effort,
                        serviceTier = event.serviceTier,
                    )
                }
            }
            is AgentEvent.TextDelta -> updateProgress(event.conversationId) { progress ->
                if (event.isCommentary) {
                    progress.appendTo(progress.commentary, event.text) { value -> copy(commentary = value) }
                } else {
                    progress.appendTo(progress.text, event.text) { value -> copy(text = value) }
                }
            }
            is AgentEvent.ReasoningSummaryDelta -> updateProgress(event.conversationId) { progress ->
                val segment = event.itemId to event.summaryIndex
                val separator = if (reasoningSegment != null && reasoningSegment != segment && progress.reasoning.isNotEmpty()) {
                    "\n\n"
                } else {
                    ""
                }
                reasoningSegment = segment
                progress.appendTo(progress.reasoning, separator + event.text) { value -> copy(reasoning = value) }
            }
            is AgentEvent.PlanDelta -> updateProgress(event.conversationId) { progress ->
                val currentPlan = progress.plan.takeIf { planItemId == event.itemId }.orEmpty()
                planItemId = event.itemId
                progress.appendTo(currentPlan, event.text) { value -> copy(plan = value) }
            }
            is AgentEvent.PlanUpdated -> updateProgress(event.conversationId) {
                it.copy(planProgress = event.progress)
            }
            is AgentEvent.ShellOutputDelta -> updateProgress(event.conversationId) { progress ->
                progress.appendTo(progress.shellOutput, event.text) { value -> copy(shellOutput = value) }
            }
            is AgentEvent.ShellCommandCompleted -> updateProgress(event.conversationId) {
                it.copy(shellExitCode = event.exitCode)
            }
            is AgentEvent.WorkActivityChanged -> updateProgress(event.conversationId) {
                it.copy(workActivity = event.activity)
            }
            is AgentEvent.HookActivityChanged -> updateProgress(event.conversationId) { progress ->
                progress.copy(
                    hookActivities = (progress.hookActivities
                        .filterNot { it.id == event.activity.id } + event.activity)
                        .takeLast(MAX_LIVE_HOOK_ACTIVITIES),
                )
            }
            is AgentEvent.TurnCompleted -> completeTurn(event.conversationId)
            is AgentEvent.Failure -> processFailure(event)
            else -> Unit
        }
    }

    private suspend fun updateProgress(
        conversationId: ConversationId,
        update: (AgentTurnProgress) -> AgentTurnProgress,
    ) = lock.withLock {
        val current = mutableState.value
        if (!closed && current.conversationId == conversationId && current.status in ACTIVE_TURN_STATUSES) {
            mutableState.value = current.copy(
                status = if (current.status == AgentConversationStatus.STARTING_TURN) {
                    AgentConversationStatus.RUNNING_TURN
                } else {
                    current.status
                },
                turnProgress = update(current.turnProgress),
            )
        }
    }

    private fun AgentTurnProgress.appendTo(
        current: String,
        delta: String,
        copyValue: AgentTurnProgress.(String) -> AgentTurnProgress,
    ): AgentTurnProgress {
        val remaining = MAX_LIVE_TEXT_CHARS - current.length
        if (remaining <= 0) return copy(isTruncated = true)
        val appended = delta.take(remaining)
        return copyValue(current + appended).let { updated ->
            if (appended.length == delta.length) updated else updated.copy(isTruncated = true)
        }
    }

    private suspend fun completeTurn(conversationId: ConversationId) {
        val operation = lock.withLock {
            val current = mutableState.value
            if (closed || current.conversationId != conversationId || current.status !in ACTIVE_TURN_STATUSES) return
            startupCompletion?.complete(Unit)
            cancellationCompletion?.complete(Unit)
            mutableState.value = current.copy(status = AgentConversationStatus.RELOADING)
            generation
        }
        reloadCanonical(operation, clearProgress = true, throwOnFailure = false)
    }

    private suspend fun reloadCanonical(
        operation: Long,
        clearProgress: Boolean,
        throwOnFailure: Boolean,
    ) {
        val conversationId = lock.withLock {
            if (closed || generation != operation) return
            checkNotNull(mutableState.value.conversationId)
        }
        val conversation = try {
            client.readConversation(conversationId)
        } catch (error: Throwable) {
            val failure = recordFailure(operation, "reload_failed", "Could not reload conversation", error)
            if (error is CancellationException) throw error
            if (throwOnFailure) throw failure
            return
        }
        withContext(NonCancellable) {
            lock.withLock {
                val current = mutableState.value
                if (!closed && generation == operation && current.status == AgentConversationStatus.RELOADING) {
                    mutableState.value = current.copy(
                        status = AgentConversationStatus.READY,
                        conversation = conversation,
                        turnProgress = if (clearProgress) AgentTurnProgress() else current.turnProgress,
                        failure = null,
                    )
                }
            }
        }
    }

    private suspend fun processFailure(event: AgentEvent.Failure) {
        lock.withLock {
            val current = mutableState.value
            if (!closed && (event.conversationId == null || event.conversationId == current.conversationId)) {
                startupCompletion?.complete(Unit)
                cancellationCompletion?.complete(Unit)
                mutableState.value = current.copy(
                    status = AgentConversationStatus.FAILED,
                    failure = codexFailure(
                        event.code,
                        event.message,
                        "Codex operation failed",
                        event.isRecoverable,
                    ),
                )
            }
        }
    }

    private suspend fun recordFailure(
        operation: Long,
        code: String,
        fallback: String,
        error: Throwable,
        isRecoverable: Boolean = true,
    ): Throwable {
        val exception = if (error is CancellationException) {
            error
        } else {
            error.asCodexOperationException(code, fallback, isRecoverable)
        }
        val failure = if (error is CancellationException) {
            codexFailure(code, fallback, fallback, isRecoverable)
        } else {
            (exception as CodexOperationException).failure
        }
        withContext(NonCancellable) {
            lock.withLock {
                val current = mutableState.value
                if (!closed && generation == operation && current.status != AgentConversationStatus.READY) {
                    mutableState.value = current.copy(
                        status = AgentConversationStatus.FAILED,
                        failure = failure,
                    )
                }
            }
        }
        return exception
    }

    private fun requireFeature(feature: CodexRuntimeFeature) {
        if (feature !in features) {
            throw CodexOperationException(
                CodexFailure(
                    code = "unsupported_feature",
                    message = "Runtime feature ${feature.name} is not supported",
                    isRecoverable = false,
                ),
            )
        }
    }

    private suspend fun requireOpen() {
        lock.withLock { check(!closed && !closeRequested) { "Conversation is closed" } }
    }

    private companion object {
        const val MAX_LIVE_TEXT_CHARS = 256 * 1024
        const val MAX_LIVE_HOOK_ACTIVITIES = 20
        const val CLOSE_CANCELLATION_TIMEOUT_MILLIS = 5_000L

        val ACTIVE_TURN_STATUSES = setOf(
            AgentConversationStatus.STARTING_TURN,
            AgentConversationStatus.RUNNING_TURN,
            AgentConversationStatus.CANCELLING_TURN,
        )
        val STARTING_TURN_STATUSES = setOf(
            AgentConversationStatus.STARTING_TURN,
            AgentConversationStatus.CANCELLING_TURN,
        )
    }
}
