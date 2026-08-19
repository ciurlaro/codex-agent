package io.github.ciurlaro.codexmobile.agent.runtime

import io.github.ciurlaro.codexmobile.agent.CodexRuntimeFeature
import kotlin.test.Test
import kotlin.test.assertEquals

class IosCodexPlatformTest {
    @Test
    fun supportsOnlyFilesystemSkills() {
        assertEquals(setOf(CodexRuntimeFeature.SKILLS), iosCodexRuntimeFeatures)
    }
}
