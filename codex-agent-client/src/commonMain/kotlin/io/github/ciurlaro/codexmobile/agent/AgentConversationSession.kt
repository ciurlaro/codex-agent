package io.github.ciurlaro.codexmobile.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class AgentConversationStatus {
    NEW,
    OPENING,
    IDLE,
    STARTING,
    RUNNING,
    CANCELLING,
    REFRESHING,
    FAILED,
    CLOSED,
}

data class AgentConversationDraft(
    val text: String = "",
    val commentary: String = "",
    val reasoning: String = "",
    val plan: String = "",
    val planProgress: AgentPlanProgress? = null,
    val shellOutput: String = "",
    val shellExitCode: Int? = null,
    val workActivity: AgentWorkActivity? = null,
    val hookActivities: List<AgentHookActivity> = emptyList(),
)

data class AgentConversationError(
    val code: String,
    val message: String,
    val recoverable: Boolean,
)

data class AgentConversationState(
    val status: AgentConversationStatus = AgentConversationStatus.NEW,
    val sessionId: SessionId? = null,
    val conversation: AgentConversation? = null,
    val draft: AgentConversationDraft = AgentConversationDraft(),
    val pendingInteractions: List<AgentPendingInteraction> = emptyList(),
    val model: String? = null,
    val effort: String? = null,
    val serviceTier: String? = null,
    val error: AgentConversationError? = null,
)

class AgentConversationSession(
    private val client: AgentClient,
    private val interactions: AgentInteractionSession,
    scope: CoroutineScope,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(AgentConversationState())
    private var generation = 0L

    val state: StateFlow<AgentConversationState> = mutableState

    private val eventObservation: Job = scope.launch {
        client.events.collect(::process)
    }
    private val interactionObservation: Job = scope.launch {
        interactions.state.collect { interactionState ->
            lock.withLock {
                val current = mutableState.value
                if (current.status != AgentConversationStatus.CLOSED) {
                    mutableState.value = current.copy(
                        pendingInteractions = current.sessionId
                            ?.let(interactionState::pendingFor)
                            .orEmpty(),
                    )
                }
            }
        }
    }

    suspend fun open(
        previous: SessionId? = null,
        settings: AgentRuntimeSettings = AgentRuntimeSettings(),
    ): SessionId {
        val operation = lock.withLock {
            check(mutableState.value.status == AgentConversationStatus.NEW) {
                "Conversation session has already been opened"
            }
            generation += 1
            mutableState.value = mutableState.value.copy(status = AgentConversationStatus.OPENING)
            generation
        }
        val sessionId = try {
            client.openSession(previous, settings)
        } catch (error: Throwable) {
            failOperation(operation, "open_failed", error)
            throw error
        }
        lock.withLock {
            val current = mutableState.value
            if (generation == operation && current.status == AgentConversationStatus.OPENING) {
                mutableState.value = current.copy(
                    status = if (previous == null) {
                        AgentConversationStatus.IDLE
                    } else {
                        AgentConversationStatus.REFRESHING
                    },
                    sessionId = sessionId,
                    pendingInteractions = interactions.state.value.pendingFor(sessionId),
                )
            }
        }
        if (previous != null) refreshCanonical(operation, clearDraft = true)
        return sessionId
    }

    suspend fun send(request: AgentTurnRequest) = startTurn { sessionId ->
        client.sendTurn(sessionId, request)
    }

    suspend fun runShellCommand(command: String) = startTurn { sessionId ->
        client.runShellCommand(sessionId, command)
    }

    suspend fun cancel() {
        val operation = lock.withLock {
            val current = mutableState.value
            check(current.status == AgentConversationStatus.STARTING || current.status == AgentConversationStatus.RUNNING) {
                "Conversation does not have an active turn"
            }
            mutableState.value = current.copy(status = AgentConversationStatus.CANCELLING)
            generation to checkNotNull(current.sessionId)
        }
        try {
            client.cancelTurn(operation.second)
        } catch (error: Throwable) {
            failOperation(operation.first, "cancel_failed", error)
            throw error
        }
    }

    suspend fun refresh() {
        val operation = lock.withLock {
            val current = mutableState.value
            check(
                current.status == AgentConversationStatus.IDLE ||
                    current.status == AgentConversationStatus.FAILED,
            ) { "Conversation cannot refresh while ${current.status.name.lowercase()}" }
            checkNotNull(current.sessionId)
            generation += 1
            mutableState.value = current.copy(
                status = AgentConversationStatus.REFRESHING,
                error = null,
            )
            generation
        }
        refreshCanonical(operation, clearDraft = true)
    }

    suspend fun close() {
        lock.withLock {
            if (mutableState.value.status == AgentConversationStatus.CLOSED) return
            generation += 1
            eventObservation.cancel()
            interactionObservation.cancel()
            mutableState.value = mutableState.value.copy(
                status = AgentConversationStatus.CLOSED,
                pendingInteractions = emptyList(),
            )
        }
    }

    private suspend fun startTurn(block: suspend (SessionId) -> Unit) {
        val operation = lock.withLock {
            val current = mutableState.value
            check(
                current.status == AgentConversationStatus.IDLE ||
                    current.status == AgentConversationStatus.FAILED && current.error?.recoverable == true,
            ) { "Conversation is not ready for a turn" }
            generation += 1
            mutableState.value = current.copy(
                status = AgentConversationStatus.STARTING,
                draft = AgentConversationDraft(),
                error = null,
            )
            generation to checkNotNull(current.sessionId)
        }
        try {
            block(operation.second)
        } catch (error: Throwable) {
            failOperation(operation.first, "turn_start_failed", error)
            throw error
        }
        lock.withLock {
            if (generation == operation.first && mutableState.value.status == AgentConversationStatus.STARTING) {
                mutableState.value = mutableState.value.copy(status = AgentConversationStatus.RUNNING)
            }
        }
    }

    internal suspend fun process(event: AgentEvent) {
        when (event) {
            is AgentEvent.SessionOpened -> lock.withLock {
                val current = mutableState.value
                if (current.status != AgentConversationStatus.CLOSED &&
                    (current.sessionId == event.sessionId ||
                        current.status == AgentConversationStatus.OPENING && current.sessionId == null)
                ) {
                    mutableState.value = current.copy(
                        sessionId = event.sessionId,
                        model = event.model,
                        effort = event.effort,
                        serviceTier = event.serviceTier,
                        pendingInteractions = interactions.state.value.pendingFor(event.sessionId),
                    )
                }
            }
            is AgentEvent.TextDelta -> updateDraft(event.sessionId) { draft ->
                if (event.isCommentary) {
                    draft.copy(commentary = draft.commentary + event.text)
                } else {
                    draft.copy(text = draft.text + event.text)
                }
            }
            is AgentEvent.ReasoningSummaryDelta -> updateDraft(event.sessionId) {
                it.copy(reasoning = it.reasoning + event.text)
            }
            is AgentEvent.PlanDelta -> updateDraft(event.sessionId) {
                it.copy(plan = it.plan + event.text)
            }
            is AgentEvent.PlanUpdated -> updateDraft(event.sessionId) {
                it.copy(planProgress = event.progress)
            }
            is AgentEvent.ShellOutputDelta -> updateDraft(event.sessionId) {
                it.copy(shellOutput = it.shellOutput + event.text)
            }
            is AgentEvent.ShellCommandCompleted -> updateDraft(event.sessionId) {
                it.copy(shellExitCode = event.exitCode)
            }
            is AgentEvent.WorkActivityChanged -> updateDraft(event.sessionId) {
                it.copy(workActivity = event.activity)
            }
            is AgentEvent.HookActivityChanged -> updateDraft(event.sessionId) { draft ->
                draft.copy(
                    hookActivities = draft.hookActivities
                        .filterNot { it.id == event.activity.id } + event.activity,
                )
            }
            is AgentEvent.TurnCompleted -> completeTurn(event.sessionId)
            is AgentEvent.Failure -> processFailure(event)
            else -> Unit
        }
    }

    private suspend fun updateDraft(
        sessionId: SessionId,
        update: (AgentConversationDraft) -> AgentConversationDraft,
    ) = lock.withLock {
        val current = mutableState.value
        if (current.sessionId == sessionId && current.status in ACTIVE_TURN_STATUSES) {
            mutableState.value = current.copy(
                status = if (current.status == AgentConversationStatus.STARTING) {
                    AgentConversationStatus.RUNNING
                } else {
                    current.status
                },
                draft = update(current.draft),
            )
        }
    }

    private suspend fun completeTurn(sessionId: SessionId) {
        val operation = lock.withLock {
            val current = mutableState.value
            if (current.sessionId != sessionId || current.status !in ACTIVE_TURN_STATUSES) return
            mutableState.value = current.copy(status = AgentConversationStatus.REFRESHING)
            generation
        }
        refreshCanonical(operation, clearDraft = true)
    }

    private suspend fun refreshCanonical(operation: Long, clearDraft: Boolean) {
        val sessionId = lock.withLock {
            if (generation != operation || mutableState.value.status == AgentConversationStatus.CLOSED) return
            checkNotNull(mutableState.value.sessionId)
        }
        val conversation = try {
            client.readSession(sessionId)
        } catch (error: Throwable) {
            failOperation(operation, "refresh_failed", error, recoverable = true)
            return
        }
        lock.withLock {
            val current = mutableState.value
            if (generation == operation && current.status == AgentConversationStatus.REFRESHING) {
                mutableState.value = current.copy(
                    status = AgentConversationStatus.IDLE,
                    conversation = conversation,
                    draft = if (clearDraft) AgentConversationDraft() else current.draft,
                    error = null,
                )
            }
        }
    }

    private suspend fun processFailure(event: AgentEvent.Failure) {
        lock.withLock {
            val current = mutableState.value
            if (event.sessionId == null || event.sessionId == current.sessionId) {
                mutableState.value = current.copy(
                    status = AgentConversationStatus.FAILED,
                    error = AgentConversationError(event.code, event.message, event.recoverable),
                )
            }
        }
    }

    private suspend fun failOperation(
        operation: Long,
        code: String,
        error: Throwable,
        recoverable: Boolean = true,
    ) {
        if (error is CancellationException) throw error
        lock.withLock {
            val current = mutableState.value
            if (generation == operation &&
                current.status != AgentConversationStatus.IDLE &&
                current.status != AgentConversationStatus.CLOSED
            ) {
                mutableState.value = current.copy(
                    status = AgentConversationStatus.FAILED,
                    error = AgentConversationError(
                        code,
                        error.message ?: code.replace('_', ' '),
                        recoverable,
                    ),
                )
            }
        }
    }

    private companion object {
        val ACTIVE_TURN_STATUSES = setOf(
            AgentConversationStatus.STARTING,
            AgentConversationStatus.RUNNING,
            AgentConversationStatus.CANCELLING,
        )
    }
}
