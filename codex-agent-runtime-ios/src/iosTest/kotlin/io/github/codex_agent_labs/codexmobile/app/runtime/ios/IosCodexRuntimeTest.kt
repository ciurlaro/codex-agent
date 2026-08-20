@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.codex_agent_labs.codexmobile.app.runtime.ios

import io.github.codex_agent_labs.codexmobile.agent.CodexClientInfo
import io.github.codex_agent_labs.codexmobile.agent.CodexHost
import io.github.codex_agent_labs.codexmobile.agent.CodexHostState
import io.github.codex_agent_labs.codexmobile.agent.CodexStorageRoots
import io.github.codex_agent_labs.codexmobile.agent.runtime.IosCodexCredentialProtection
import io.github.codex_agent_labs.codexmobile.agent.runtime.IosCodexPlatform
import io.github.codex_agent_labs.codexmobile.agent.runtime.IosCodexWorkspaceSelection
import io.github.codex_agent_labs.codexmobile.agent.runtime.resolveIosStorageRoots
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okio.Path.Companion.toPath
import platform.Foundation.NSURL

class IosCodexRuntimeTest {
    @Test
    fun localWorkspaceToolsReadSearchListAndModifyFiles() = runBlocking {
        TestWorkspace().use { test ->
            val tools = IosCodexRuntimeFactory(test.configuration).workspaceTools
            assertEquals(
                setOf("apply_patch", "read_file", "list_directory", "search_text", "write_file"),
                tools.definitions().mapTo(mutableSetOf()) { it.name },
            )
            assertTrue(tools.definitions().all { !it.requiresEnabledPlugin })
            assertFalse(tools.definitions().any { it.name.contains("command") || it.name.contains("git") })

            assertTrue(tools.call(test, "write_file", json("path" to "note.txt", "content" to "alpha\nbeta\n")).success)
            assertEquals("alpha\nbeta\n", tools.call(test, "read_file", json("path" to "note.txt")).text())
            assertTrue(tools.call(test, "search_text", json("query" to "BETA")).text().contains("note.txt:2:beta"))
            assertTrue(tools.call(test, "list_directory", buildJsonObject {}).text().contains("file\tnote.txt"))
            assertTrue(tools.call(test, "read_file", json("path" to "note.txt"), "${test.workspace}/.").success)
            assertFalse(tools.call(test, "read_file", json("path" to "note.txt"), test.sandboxRoot).success)

            assertTrue(tools.call(test, "write_file", json("path" to "note.txt", "content" to "modified locally\n")).success)
            assertEquals("modified locally\n", tools.call(test, "read_file", json("path" to "note.txt")).text())
            val patch = """
                *** Begin Patch
                *** Update File: note.txt
                @@
                -modified locally
                +patched locally
                *** End Patch
            """.trimIndent() + "\n"
            assertTrue(tools.call(test, "apply_patch", json("patch" to patch)).success)
            assertEquals("patched locally\n", tools.call(test, "read_file", json("path" to "note.txt")).text())
            val traversal = tools.call(test, "read_file", json("path" to "../outside.txt"))
            assertFalse(traversal.success)
            assertTrue(traversal.text().contains("must not contain '..'"))
        }
    }

    @Test
    fun publicPlatformStartsTheHostAndRestartsTheRuntime() = runBlocking {
        TestWorkspace().use { test ->
            val platform = IosCodexPlatform(
                sandboxRootPath = test.sandboxRoot,
                credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
            )
            repeat(2) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val host = CodexHost(
                    platform,
                    scope,
                    CodexClientInfo("ios-runtime-test", "iOS Runtime Test", "0.2.0"),
                )
                try {
                    host.selectWorkspace(
                        IosCodexWorkspaceSelection(NSURL.fileURLWithPath(test.workspace)),
                    )
                    assertIs<CodexHostState.Ready>(host.state.value)
                } finally {
                    host.close()
                    scope.cancel()
                }
            }
        }
    }

    @Test
    fun storageRootsDefaultOverrideAndDisableAreDistinct() {
        val defaults = resolveIosStorageRoots("/sandbox", "/codex-home", null)
        assertEquals("/sandbox/Library/Caches/CodexAgent".toPath(), defaults.cacheRoot)
        assertEquals("/codex-home".toPath(), defaults.stateRoot)

        val override = CodexStorageRoots("/cache".toPath(), "/state".toPath())
        assertEquals(override, resolveIosStorageRoots("/sandbox", "/codex-home", override))

        val disabled = resolveIosStorageRoots("/sandbox", "/codex-home", CodexStorageRoots())
        assertNull(disabled.cacheRoot)
        assertNull(disabled.stateRoot)
    }

    @Test
    fun nativeConfigurationRejectsEqualAndNestedPaths() = runBlocking {
        TestWorkspace().use { test ->
            assertRejected(test.configuration.copy(codexHomePath = test.workspace))
            assertRejected(test.configuration.copy(codexHomePath = "${test.workspace}/state"))

            val home = "${test.sandboxRoot}/state"
            val nestedWorkspace = "$home/workspace"
            createDirectory(nestedWorkspace)
            assertRejected(test.configuration.copy(workspacePath = nestedWorkspace, codexHomePath = home))
        }
    }

    @Test
    fun nativeConfigurationRejectsEitherPathOutsideSandbox() = runBlocking {
        TestWorkspace().use { test ->
            TestWorkspace().use { outside ->
                assertRejected(test.configuration.copy(workspacePath = outside.workspace))
                assertRejected(test.configuration.copy(codexHomePath = outside.codexHome))
            }
        }
    }

    @Test
    fun nativeConfigurationAcceptsSiblingDirectories() = runBlocking {
        TestWorkspace().use { test ->
            val result = executeIosWorkspaceTool(test.configuration, "list_directory", buildJsonObject {})
            assertTrue(result.success)
        }
    }

    @Test
    fun duplicateRuntimeOwnershipIsRejectedAndReusableAfterCleanShutdown(): Unit = runBlocking {
        TestWorkspace().use { test ->
            val first = IosCodexRuntime(test.configuration)
            val duplicate = IosCodexRuntime(test.configuration)
            first.start()
            try {
                val error = runCatching { duplicate.start() }.exceptionOrNull()
                assertIs<IosCodexRuntimeException>(error)
                assertTrue(error.message.orEmpty().contains("already owns"))
            } finally {
                duplicate.close()
                first.close()
            }
            IosCodexRuntime(test.configuration).also {
                it.start()
                it.close()
            }
        }
    }
}

private suspend fun assertRejected(configuration: IosCodexRuntimeConfiguration) {
    val error = runCatching {
        executeIosWorkspaceTool(configuration, "list_directory", buildJsonObject {})
    }.exceptionOrNull()
    assertIs<IosCodexRuntimeException>(error)
}
