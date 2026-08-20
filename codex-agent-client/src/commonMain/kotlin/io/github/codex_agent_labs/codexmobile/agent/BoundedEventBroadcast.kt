package io.github.codex_agent_labs.codexmobile.agent

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class BoundedEventBroadcast<T>(
    private val capacity: Int,
    private val observerOverflow: () -> T,
    private val backlogOverflow: () -> T,
) {
    private val lock = Mutex()
    private val subscriptions = mutableSetOf<Subscription<T>>()
    private val backlog = ArrayDeque<T>(capacity)
    private var backlogOverflowed = false
    private var closed = false

    val events: Flow<T> = flow {
        val subscription = Subscription<T>(capacity)
        val registered = lock.withLock {
            if (closed) {
                false
            } else {
                subscription.retained.addAll(backlog)
                backlog.clear()
                backlogOverflowed = false
                subscriptions += subscription
                true
            }
        }
        if (!registered) return@flow
        try {
            subscription.retained.forEach { emit(it) }
            subscription.retained.clear()
            for (event in subscription.live) emit(event)
        } catch (_: ObserverOverflowException) {
            emit(observerOverflow())
        } finally {
            lock.withLock { subscriptions.remove(subscription) }
            subscription.live.close()
        }
    }

    suspend fun send(event: T) {
        val overflowed = mutableListOf<Channel<T>>()
        lock.withLock {
            if (closed) return
            if (subscriptions.isEmpty()) {
                if (!backlogOverflowed && backlog.size < capacity) {
                    backlog.addLast(event)
                } else if (!backlogOverflowed) {
                    backlog.clear()
                    backlog.addLast(backlogOverflow())
                    if (capacity > 1) backlog.addLast(event)
                    backlogOverflowed = true
                }
                return
            }
            val iterator = subscriptions.iterator()
            while (iterator.hasNext()) {
                val mailbox = iterator.next().live
                if (mailbox.trySend(event).isFailure) {
                    iterator.remove()
                    overflowed += mailbox
                }
            }
        }
        overflowed.forEach { it.close(ObserverOverflowException()) }
    }

    suspend fun close() {
        val mailboxes = lock.withLock {
            if (closed) return
            closed = true
            backlog.clear()
            subscriptions.map(Subscription<T>::live).also { subscriptions.clear() }
        }
        mailboxes.forEach { it.close() }
    }
}

private class Subscription<T>(capacity: Int) {
    val retained = ArrayDeque<T>(capacity)
    val live = Channel<T>(capacity)
}

private class ObserverOverflowException : IllegalStateException("Event observer overflow")
