package io.github.codex_agent_labs.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okio.Path.Companion.toPath

class CodexStorageRootsTest {
    @Test
    fun routesAreasByDurabilityAndDisablesMissingRoots() {
        val roots = CodexStorageRoots("/cache".toPath(), "/state".toPath())

        assertEquals("/cache/plugins".toPath(), roots.directory(CodexStorageArea.PLUGIN_CACHE))
        assertEquals("/state/shell-transcripts".toPath(), roots.directory(CodexStorageArea.SHELL_TRANSCRIPTS))
        assertEquals(
            "/state/turn-input-metadata".toPath(),
            roots.directory(CodexStorageArea.TURN_INPUT_METADATA),
        )

        CodexStorageArea.entries.forEach { area ->
            assertNull(CodexStorageRoots().directory(area))
        }
    }
}
