package io.github.ciurlaro.codexmobile.agent.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class CodexStateObservationTest {
    @Test
    fun observesTheCurrentValueAndStopsAfterClose() = runBlocking {
        val state = MutableStateFlow("initial")
        val values = Channel<Any?>(Channel.UNLIMITED)
        val observation = CodexStateObservation(state) { values.trySend(it) }

        assertEquals("initial", withTimeout(1_000) { values.receive() })
        state.value = "updated"
        assertEquals("updated", withTimeout(1_000) { values.receive() })

        observation.close()
        observation.close()
        state.value = "ignored"
        assertNull(withTimeoutOrNull(100) { values.receive() })
    }
}
