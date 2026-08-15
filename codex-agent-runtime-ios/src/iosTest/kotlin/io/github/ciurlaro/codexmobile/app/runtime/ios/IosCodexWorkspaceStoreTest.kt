@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceSelectionReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

class IosCodexWorkspaceStoreTest {
    @Test
    fun selectionPersistsRestoresClearsAndRejectsAStaleBookmark() = runBlocking {
        val sandbox = "${NSTemporaryDirectory().trimEnd('/')}/codex-workspace-${NSUUID().UUIDString}"
        val workspace = "$sandbox/workspace"
        val bookmark = "$sandbox/state/workspace.bookmark"
        val backend = FakeBookmarkBackend(NSURL.fileURLWithPath(workspace))
        createDirectory(workspace)
        try {
            val store = IosCodexWorkspaceStore(sandbox, bookmark, backend)
            val selectedPath = assertIs<CodexWorkspaceResolution.Available>(
                store.select(IosCodexWorkspaceSelection(NSURL.fileURLWithPath(workspace))),
            ).workspace.path
            assertTrue(selectedPath.endsWith("/${sandbox.substringAfterLast('/')}/workspace"))
            assertTrue(NSFileManager.defaultManager.fileExistsAtPath(bookmark))
            assertEquals(
                selectedPath,
                assertIs<CodexWorkspaceResolution.Available>(store.restore()).workspace.path,
            )

            backend.stale = true
            assertEquals(
                CodexWorkspaceSelectionReason.ACCESS_REVOKED,
                assertIs<CodexWorkspaceResolution.SelectionRequired>(store.restore()).reason,
            )

            store.clear()
            assertEquals(
                CodexWorkspaceSelectionReason.NOT_SELECTED,
                assertIs<CodexWorkspaceResolution.SelectionRequired>(store.restore()).reason,
            )
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(sandbox, null)
        }
    }
}

private class FakeBookmarkBackend(private val url: NSURL) : IosWorkspaceBookmarkBackend {
    var stale = false

    override fun create(url: NSURL, securityScoped: Boolean): ByteArray = byteArrayOf(1)

    override fun resolve(bookmark: ByteArray, securityScoped: Boolean) =
        IosResolvedBookmark(url, stale)
}
