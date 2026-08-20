package io.github.codex_agent_labs.codexmobile.agent.runtime

import io.github.codex_agent_labs.codexmobile.agent.CodexRuntimeFeature
import kotlin.test.Test
import kotlin.test.assertEquals

class IosCodexPlatformTest {
    @Test
    fun supportsOnlyFilesystemSkills() {
        assertEquals(setOf(CodexRuntimeFeature.SKILLS), iosCodexRuntimeFeatures)
    }
}
