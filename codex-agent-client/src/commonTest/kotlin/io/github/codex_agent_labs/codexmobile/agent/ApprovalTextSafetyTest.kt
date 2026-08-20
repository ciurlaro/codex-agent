package io.github.codex_agent_labs.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class ApprovalTextSafetyTest {
    @Test
    fun escapesUnsafeUnicodeAndPreservesOrdinaryText() {
        val cases = listOf(
            "\u0000" to "\\u{0}",
            "\u001B" to "\\u{1B}",
            "\u200E" to "\\u{200E}",
            "\u2066" to "\\u{2066}",
            "\u2028" to "\\u{2028}",
            "\u2029" to "\\u{2029}",
            codePoint(0x110BD) to "\\u{110BD}",
            codePoint(0x13430) to "\\u{13430}",
            codePoint(0x1BCA0) to "\\u{1BCA0}",
            codePoint(0x1D173) to "\\u{1D173}",
            codePoint(0xE0001) to "\\u{E0001}",
            codePoint(0xE0020) to "\\u{E0020}",
            "Grüße ${codePoint(0x1F642)}" to "Grüße ${codePoint(0x1F642)}",
        )

        cases.forEach { (raw, expected) -> assertEquals(expected, raw.safeApprovalText()) }
    }
}

private fun codePoint(value: Int): String {
    val offset = value - 0x10000
    return "${(0xD800 + (offset shr 10)).toChar()}${(0xDC00 + (offset and 0x3FF)).toChar()}"
}
