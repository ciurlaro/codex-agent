package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.AgentEvent
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import platform.Foundation.NSLock

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
