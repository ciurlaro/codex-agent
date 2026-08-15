package io.github.ciurlaro.codexmobile.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AgentPendingInteraction {
    val requestId: String
    val sessionId: SessionId
}

data class AgentPendingApproval(
    override val requestId: String,
    override val sessionId: SessionId,
    val title: String,
    val details: String,
) : AgentPendingInteraction

data class AgentPendingElicitation(
    val elicitation: AgentElicitation,
) : AgentPendingInteraction {
    override val requestId: String get() = elicitation.requestId
    override val sessionId: SessionId get() = elicitation.sessionId
}

data class AgentInteractionState(
    val pending: List<AgentPendingInteraction> = emptyList(),
    val resolvingRequestIds: Set<String> = emptySet(),
    val terminalReason: String? = null,
    val closed: Boolean = false,
) {
    fun pendingFor(sessionId: SessionId): List<AgentPendingInteraction> =
        pending.filter { it.sessionId == sessionId }
}

class AgentInteractionSession(
    private val client: AgentClient,
    scope: CoroutineScope,
    private val browser: CodexAuthorizationBrowser? = null,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(AgentInteractionState())
    private val presentations = mutableMapOf<String, CodexAuthorizationPresentation>()

    val state: StateFlow<AgentInteractionState> = mutableState

    private val observation: Job = scope.launch {
        client.events.collect(::process)
    }

    suspend fun resolveApproval(requestId: String, decision: AgentApprovalDecision) {
        beginResolution<AgentPendingApproval>(requestId)
        try {
            client.resolveApproval(requestId, decision)
        } catch (error: Throwable) {
            finishResolution(requestId, error.message ?: "Could not resolve approval")
            throw error
        }
        finishResolution(requestId)
    }

    suspend fun resolveElicitation(requestId: String, response: AgentElicitationResponse) {
        beginResolution<AgentPendingElicitation>(requestId)
        try {
            client.resolveElicitation(requestId, response)
        } catch (error: Throwable) {
            finishResolution(requestId, error.message ?: "Could not resolve elicitation")
            throw error
        }
        finishResolution(requestId)
    }

    suspend fun openUrl(requestId: String) {
        val url = lock.withLock {
            check(!mutableState.value.closed) { "Interaction session is closed" }
            val pending = mutableState.value.pending.find { it.requestId == requestId }
                as? AgentPendingElicitation
                ?: error("URL elicitation is no longer pending")
            pending.elicitation.url ?: error("Elicitation does not contain a URL")
        }
        val opener = browser ?: error("An authorization browser is required to open elicitation URLs")
        val opened = try {
            opener.open(CodexAuthorizationUrl.external(url))
        } catch (error: Throwable) {
            lock.withLock {
                mutableState.value = mutableState.value.copy(
                    terminalReason = error.message ?: "Could not open elicitation URL",
                )
            }
            throw error
        }
        val previous = lock.withLock {
            if (mutableState.value.closed || mutableState.value.pending.none { it.requestId == requestId }) {
                opened.close()
                return
            }
            presentations.put(requestId, opened)
        }
        previous?.close()
    }

    suspend fun close() {
        val owned = lock.withLock {
            if (mutableState.value.closed) return
            observation.cancel()
            val result = presentations.values.toList()
            presentations.clear()
            mutableState.value = AgentInteractionState(closed = true)
            result
        }
        owned.forEach(CodexAuthorizationPresentation::close)
    }

    private suspend inline fun <reified T : AgentPendingInteraction> beginResolution(requestId: String) {
        lock.withLock {
            val current = mutableState.value
            check(!current.closed) { "Interaction session is closed" }
            check(requestId !in current.resolvingRequestIds) { "Interaction is already resolving" }
            check(current.pending.find { it.requestId == requestId } is T) {
                "Interaction is no longer pending or has another type"
            }
            mutableState.value = current.copy(
                resolvingRequestIds = current.resolvingRequestIds + requestId,
                terminalReason = null,
            )
        }
    }

    private suspend fun finishResolution(requestId: String, error: String? = null) {
        val presentation = lock.withLock {
            val current = mutableState.value
            mutableState.value = current.copy(
                pending = current.pending.filterNot { it.requestId == requestId },
                resolvingRequestIds = current.resolvingRequestIds - requestId,
                terminalReason = error,
            )
            presentations.remove(requestId)
        }
        presentation?.close()
    }

    private suspend fun process(event: AgentEvent) {
        val closedPresentations = lock.withLock {
            if (mutableState.value.closed) return
            when (event) {
                is AgentEvent.ApprovalRequested -> {
                    add(
                        AgentPendingApproval(
                            event.requestId,
                            event.sessionId,
                            event.title,
                            event.details,
                        ),
                    )
                    emptyList()
                }
                is AgentEvent.ElicitationRequested -> {
                    add(AgentPendingElicitation(event.elicitation))
                    emptyList()
                }
                is AgentEvent.TurnCompleted -> removeForSession(event.sessionId)
                is AgentEvent.Failure -> if (event.sessionId == null) {
                    val owned = presentations.values.toList()
                    presentations.clear()
                    mutableState.value = mutableState.value.copy(
                        pending = emptyList(),
                        resolvingRequestIds = emptySet(),
                        terminalReason = event.message,
                    )
                    owned
                } else {
                    removeForSession(event.sessionId)
                }
                else -> emptyList()
            }
        }
        closedPresentations.forEach(CodexAuthorizationPresentation::close)
    }

    private fun add(interaction: AgentPendingInteraction) {
        val current = mutableState.value
        if (current.pending.none { it.requestId == interaction.requestId }) {
            mutableState.value = current.copy(
                pending = current.pending + interaction,
                terminalReason = null,
            )
        }
    }

    private fun removeForSession(sessionId: SessionId): List<CodexAuthorizationPresentation> {
        val current = mutableState.value
        val ids = current.pending.filter { it.sessionId == sessionId }.mapTo(mutableSetOf()) { it.requestId }
        if (ids.isEmpty()) return emptyList()
        mutableState.value = current.copy(
            pending = current.pending.filterNot { it.requestId in ids },
            resolvingRequestIds = current.resolvingRequestIds - ids,
        )
        return ids.mapNotNull(presentations::remove)
    }
}
