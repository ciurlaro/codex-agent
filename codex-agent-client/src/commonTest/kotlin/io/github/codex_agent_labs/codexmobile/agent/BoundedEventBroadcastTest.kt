package io.github.codex_agent_labs.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

class BoundedEventBroadcastTest {
    @Test
    fun everyActiveCollectorReceivesTheSameEvents() = runTest {
        val broadcast = broadcast(capacity = 4)
        val first = async(start = CoroutineStart.UNDISPATCHED) { broadcast.events.take(3).toList() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { broadcast.events.take(3).toList() }

        repeat(3) { broadcast.send(it) }

        assertEquals(listOf(0, 1, 2), first.await())
        assertEquals(listOf(0, 1, 2), second.await())
        broadcast.close()
    }

    @Test
    fun oneSlowCollectorDoesNotBlockOrDropFromAnother() = runTest {
        val broadcast = broadcast(capacity = 2)
        val slowEntered = CompletableDeferred<Unit>()
        val releaseSlow = CompletableDeferred<Unit>()
        val slow = async(start = CoroutineStart.UNDISPATCHED) {
            broadcast.events.onEach {
                if (it == 0) {
                    slowEntered.complete(Unit)
                    releaseSlow.await()
                }
            }.take(4).toList()
        }
        val fast = async(start = CoroutineStart.UNDISPATCHED) { broadcast.events.take(4).toList() }

        broadcast.send(0)
        slowEntered.await()
        repeat(3) { broadcast.send(it + 1) }

        assertEquals(listOf(0, 1, 2, 3), fast.await())
        releaseSlow.complete(Unit)
        assertEquals(listOf(0, 1, 2, OBSERVER_OVERFLOW), slow.await())
        broadcast.close()
    }

    @Test
    fun aSingleBoundedBacklogIsRetainedOnlyWithoutCollectors() = runTest {
        val broadcast = broadcast(capacity = 2)
        repeat(3) { broadcast.send(it) }

        val first = async(start = CoroutineStart.UNDISPATCHED) { broadcast.events.take(2).toList() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { broadcast.events.take(1).toList() }
        broadcast.send(7)

        assertEquals(listOf(BACKLOG_OVERFLOW, 2), first.await())
        assertEquals(listOf(7), second.await())
        broadcast.close()
    }
}

private fun broadcast(capacity: Int) = BoundedEventBroadcast(
    capacity = capacity,
    observerOverflow = { OBSERVER_OVERFLOW },
    backlogOverflow = { BACKLOG_OVERFLOW },
)

private const val OBSERVER_OVERFLOW = -1
private const val BACKLOG_OVERFLOW = -2
