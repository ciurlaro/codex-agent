package io.github.codex_agent_labs.codexmobile.appserver.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlinx.coroutines.test.runTest

class JsonLineFramerTest {
    @Test
    fun framesFragmentedUtf8AndCrLfWithoutEmptyMessages() = runTest {
        val received = mutableListOf<String>()
        val framer = JsonLineFramer()
        val encoded = "é\r\n\n{\"id\":1}\nlast".encodeToByteArray()

        framer.accept(encoded.copyOfRange(0, 1), onLine = received::add)
        framer.accept(encoded.copyOfRange(1, encoded.size), onLine = received::add)
        framer.finish(received::add)

        assertEquals(listOf("é", "{\"id\":1}", "last"), received)
    }

    @Test
    fun enforcesRawByteLimitAndStopsAfterTerminalFailure() = runTest {
        val received = mutableListOf<String>()
        val framer = JsonLineFramer(maxBytes = 4)

        framer.accept("éé\n".encodeToByteArray(), onLine = received::add)
        assertFails {
            framer.accept("ééé\n".encodeToByteArray(), onLine = received::add)
        }
        framer.accept("later\n".encodeToByteArray(), onLine = received::add)

        assertEquals(listOf("éé"), received)
    }

    @Test
    fun rejectsMalformedUtf8() = runTest {
        val framer = JsonLineFramer()

        assertFails {
            framer.accept(byteArrayOf(0xc3.toByte(), 0x28, '\n'.code.toByte())) {}
        }
    }

    @Test
    fun acceptsLiteralReplacementCharacter() = runTest {
        val received = mutableListOf<String>()

        JsonLineFramer().accept("�\n".encodeToByteArray(), onLine = received::add)

        assertEquals(listOf("�"), received)
    }
}
