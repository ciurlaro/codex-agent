package io.github.ciurlaro.codexmobile.agent

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class CodexHostSessionTest {
    @Test
    fun restoresSelectsReplacesRetriesAndClosesOneOwnedGraph(): Unit = runBlocking {
        val runtimes = ArrayDeque<FakeCodexRuntime>()
        val threadIds = AtomicInteger()
        val conversationWorkspaces = mutableListOf<String?>()
        repeat(3) { runtimes += hostRuntime(threadIds, conversationWorkspaces) }
        val workspaceOne = CodexWorkspace("/workspace/one", "One")
        val workspaceTwo = CodexWorkspace("/workspace/two", "Two")
        val store = FakeWorkspaceStore(
            restoreResult = CodexWorkspaceResolution.SelectionRequired(
                CodexWorkspaceSelectionReason.NOT_FOUND,
                "Choose a workspace",
            ),
        )
        val support = FakePlatformSupport(store, runtimes)
        val host = CodexHostSession(support, this, clientVersion = "test")

        host.start()
        assertEquals(CodexHostStatus.WORKSPACE_REQUIRED, host.state.value.status)
        assertEquals(CodexWorkspaceSelectionReason.NOT_FOUND, host.state.value.workspaceRequirement?.reason)

        store.selectResult = CodexWorkspaceResolution.Available(workspaceOne)
        host.selectWorkspace(CodexPathWorkspaceSelection(workspaceOne.path))
        val firstReady = host.state.value
        assertEquals(CodexHostStatus.READY, firstReady.status)
        assertNotNull(firstReady.authentication)
        assertNotNull(firstReady.interactions)
        assertNotNull(firstReady.mcpAuthorization)
        firstReady.client!!.listModels()
        val firstRuntime = support.preparedRuntimes[0]

        val firstConversation = host.openConversation()
        assertEquals("/workspace/one", conversationWorkspaces.last())
        val secondConversation = host.openConversation()
        assertEquals(AgentConversationStatus.CLOSED, firstConversation.state.value.status)
        assertTrue(host.state.value.conversation === secondConversation)

        store.selectResult = CodexWorkspaceResolution.Available(workspaceTwo)
        host.selectWorkspace(CodexPathWorkspaceSelection(workspaceTwo.path))
        assertEquals(CodexHostStatus.READY, host.state.value.status)
        assertEquals(AgentConversationStatus.CLOSED, secondConversation.state.value.status)
        assertTrue(firstRuntime.allClientStreamsClosed())
        assertEquals(AgentAuthenticationStatus.CLOSED, firstReady.authentication.state.value.status)

        support.failNextPrepare = true
        store.selectResult = CodexWorkspaceResolution.Available(CodexWorkspace("/workspace/three", "Three"))
        host.selectWorkspace(CodexPathWorkspaceSelection("/workspace/three"))
        assertEquals(CodexHostStatus.FAILED, host.state.value.status)
        assertEquals("prepare failed", host.state.value.error)
        host.retry()
        assertEquals(CodexHostStatus.READY, host.state.value.status)
        host.state.value.client!!.listModels()

        host.close()
        host.close()
        assertEquals(CodexHostStatus.CLOSED, host.state.value.status)
        assertTrue(support.preparedRuntimes.last().allClientStreamsClosed())
    }
}

private class FakeWorkspaceStore(
    var restoreResult: CodexWorkspaceResolution,
) : CodexWorkspaceStore {
    lateinit var selectResult: CodexWorkspaceResolution

    override suspend fun select(selection: CodexWorkspaceSelection): CodexWorkspaceResolution = selectResult

    override suspend fun restore(): CodexWorkspaceResolution = restoreResult

    override suspend fun clear() = Unit
}

private class FakePlatformSupport(
    override val workspaces: CodexWorkspaceStore,
    private val runtimes: ArrayDeque<FakeCodexRuntime>,
) : CodexPlatformSupport {
    val preparedRuntimes = mutableListOf<FakeCodexRuntime>()
    var failNextPrepare = false

    override val browser = CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }

    override suspend fun prepare(workspace: CodexWorkspace): CodexPreparedRuntime {
        if (failNextPrepare) {
            failNextPrepare = false
            error("prepare failed")
        }
        val runtime = runtimes.removeFirst()
        preparedRuntimes += runtime
        return CodexPreparedRuntime(
            runtimeFactory = { runtime },
            workspacePath = workspace.path,
        )
    }
}

private fun hostRuntime(
    threadIds: AtomicInteger,
    conversationWorkspaces: MutableList<String?>,
): FakeCodexRuntime = FakeCodexRuntime { message, server ->
    when (message.method) {
        "initialize" -> server.respond(message.id, buildJsonObject {})
        "model/list" -> server.respond(message.id, buildJsonObject { putJsonArray("data") {} })
        "thread/start" -> {
            conversationWorkspaces += message.params.optionalString("cwd")
            val id = "thread-${threadIds.incrementAndGet()}"
            server.respond(message.id, buildJsonObject { putJsonObject("thread") { put("id", id) } })
        }
    }
}
