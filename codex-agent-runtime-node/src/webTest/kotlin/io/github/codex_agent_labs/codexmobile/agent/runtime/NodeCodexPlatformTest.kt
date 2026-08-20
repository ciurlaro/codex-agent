package io.github.codex_agent_labs.codexmobile.agent.runtime

import io.github.codex_agent_labs.codexmobile.agent.CodexRuntimeFeature
import io.github.codex_agent_labs.codexmobile.agent.CodexStorageRoots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import okio.Path.Companion.toPath

class NodeCodexPlatformTest {
    @Test
    fun supportsEveryPublicRuntimeFeature() {
        assertEquals(CodexRuntimeFeature.entries.toSet(), nodeCodexRuntimeFeatures)
    }

    @Test
    fun storageRootsDefaultOverrideAndDisableAreDistinct() {
        val data = "/app/data".toPath()
        val defaults = resolveNodeStorageRoots(data, null)
        assertEquals("/app/data/cache".toPath(), defaults.cacheRoot)
        assertEquals("/app/data/state".toPath(), defaults.stateRoot)

        val override = CodexStorageRoots("/cache".toPath(), "/state".toPath())
        assertEquals(override, resolveNodeStorageRoots(data, override))

        val disabled = resolveNodeStorageRoots(data, CodexStorageRoots())
        assertNull(disabled.cacheRoot)
        assertNull(disabled.stateRoot)
    }
}
