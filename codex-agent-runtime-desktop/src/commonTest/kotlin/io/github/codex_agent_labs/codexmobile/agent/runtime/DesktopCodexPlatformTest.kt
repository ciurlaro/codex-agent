package io.github.codex_agent_labs.codexmobile.agent.runtime

import io.github.codex_agent_labs.codexmobile.agent.CodexRuntimeFeature
import io.github.codex_agent_labs.codexmobile.agent.CodexStorageRoots
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

class DesktopCodexPlatformTest {
    @Test
    fun supportsEveryPublicRuntimeFeature() {
        assertEquals(CodexRuntimeFeature.entries.toSet(), desktopCodexRuntimeFeatures)
    }

    @Test
    fun storageRootsUseDesktopDefaultsOnlyWhenNotOverridden() {
        val data = "/desktop/data".toPath()

        assertEquals(
            CodexStorageRoots(data / "cache", data / "state"),
            resolveDesktopStorageRoots(data, configured = null),
        )

        val disabled = CodexStorageRoots()
        assertEquals(disabled, resolveDesktopStorageRoots(data, configured = disabled))

        val custom = CodexStorageRoots("/custom/cache".toPath(), "/custom/state".toPath())
        assertEquals(custom, resolveDesktopStorageRoots(data, configured = custom))
    }
}
