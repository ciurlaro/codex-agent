package io.github.ciurlaro.codexmobile.agent.runtime

import io.github.ciurlaro.codexmobile.agent.CodexRuntimeFeature
import io.github.ciurlaro.codexmobile.agent.CodexStorageRoots
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.Path.Companion.toPath

class AndroidCodexPlatformTest {
    @Test
    fun supportsEveryPublicRuntimeFeature() {
        assertEquals(CodexRuntimeFeature.entries.toSet(), androidCodexRuntimeFeatures)
    }

    @Test
    fun storageRootsUseAndroidDefaultsOnlyWhenNotOverridden() {
        val cache = "/android/cache".toPath()
        val state = "/android/no-backup".toPath()

        assertEquals(
            CodexStorageRoots(cache / "codex-agent", state / "codex-agent"),
            resolveAndroidStorageRoots(cache, state, configured = null),
        )

        val disabled = CodexStorageRoots()
        assertEquals(disabled, resolveAndroidStorageRoots(cache, state, configured = disabled))

        val custom = CodexStorageRoots("/custom/cache".toPath(), "/custom/state".toPath())
        assertEquals(custom, resolveAndroidStorageRoots(cache, state, configured = custom))
    }
}
