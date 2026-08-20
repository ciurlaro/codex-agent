package io.github.codex_agent_labs.codexmobile.agent.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A cancellable StateFlow observation for Swift clients. */
public class CodexStateObservation(
    state: StateFlow<*>,
    onValue: (Any?) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.collect(onValue)
        }
    }

    /** Stops delivering values. Safe to call more than once. */
    override fun close() {
        scope.cancel()
    }
}
