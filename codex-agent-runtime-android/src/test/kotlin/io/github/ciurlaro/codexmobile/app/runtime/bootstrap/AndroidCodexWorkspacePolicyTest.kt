package io.github.ciurlaro.codexmobile.app.runtime.bootstrap

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceSelectionReason

class AndroidCodexWorkspacePolicyTest {
    @Test
    fun allowsCanonicalSharedDirectoriesAndRejectsRestrictedOrOutsidePaths() {
        val root = Files.createTempDirectory("codex-android-root-").toFile().canonicalFile
        try {
            val workspace = File(root, "Projects/demo").apply { mkdirs() }
            val available = assertIs<CodexWorkspaceResolution.Available>(
                resolveAndroidWorkspace(workspace.path, listOf(root)),
            )
            assertEquals(workspace.canonicalPath, available.workspace.path)

            val restricted = File(root, "Android/Data/app").apply { mkdirs() }
            val rejected = assertIs<CodexWorkspaceResolution.SelectionRequired>(
                resolveAndroidWorkspace(restricted.path, listOf(root)),
            )
            assertEquals(CodexWorkspaceSelectionReason.INVALID_SELECTION, rejected.reason)

            val outside = Files.createTempDirectory("codex-android-outside-").toFile()
            try {
                val outsideResult = assertIs<CodexWorkspaceResolution.SelectionRequired>(
                    resolveAndroidWorkspace(outside.path, listOf(root)),
                )
                assertEquals(CodexWorkspaceSelectionReason.INVALID_SELECTION, outsideResult.reason)
            } finally {
                assertTrue(outside.deleteRecursively())
            }
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }

    @Test
    fun reportsMissingAndInvalidSelectionsWithoutPersistingThem() {
        val root = Files.createTempDirectory("codex-android-root-").toFile()
        try {
            val missing = assertIs<CodexWorkspaceResolution.SelectionRequired>(
                resolveAndroidWorkspace(File(root, "missing").path, listOf(root)),
            )
            assertEquals(CodexWorkspaceSelectionReason.NOT_FOUND, missing.reason)
            val invalid = assertIs<CodexWorkspaceResolution.SelectionRequired>(
                resolveAndroidWorkspace("\u0000", listOf(root)),
            )
            assertEquals(CodexWorkspaceSelectionReason.INVALID_SELECTION, invalid.reason)
        } finally {
            assertTrue(root.deleteRecursively())
        }
    }
}
