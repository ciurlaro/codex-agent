package io.github.codex_agent_labs.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexPlatformTest {
    @Test
    fun absoluteWorkspaceValidationSupportsUnixWindowsAndUncHosts() {
        listOf("/workspace", "C:\\workspace", "d:/workspace", "\\\\server\\share").forEach {
            assertTrue(it.isAbsoluteHostPath(), it)
        }
        listOf("", "workspace", "C:workspace", "\\workspace", "C:\u0000workspace").forEach {
            assertFalse(it.isAbsoluteHostPath(), it)
        }
    }
}
