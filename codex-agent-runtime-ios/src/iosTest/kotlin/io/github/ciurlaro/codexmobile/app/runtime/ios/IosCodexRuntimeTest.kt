@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.BuiltInToolContent
import io.github.ciurlaro.codexmobile.agent.BuiltInToolResult
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.CodexAgentClient
import io.github.ciurlaro.codexmobile.agent.CodexAuthenticationMethod
import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ClientInfo
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeCapabilities
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

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
    fun commonConnectionOwnsInitializationAndRuntimeRestarts() = runBlocking {
        TestWorkspace().use { test ->
            repeat(2) {
                val connection = AppServerConnection(
                    runtimeFactory = IosCodexRuntimeFactory(test.configuration),
                    initializeParams = InitializeParams(
                        clientInfo = ClientInfo("ios-runtime-test", "0.2.0", "iOS Runtime Test"),
                        capabilities = InitializeCapabilities(
                            experimentalApi = true,
                            mcpServerOpenaiFormElicitation = false,
                        ),
                    ),
                    requestTimeoutMillis = 60_000,
                )
                try {
                    val initialized = connection.ensureStarted()
                    assertEquals("ios", initialized.platformOs)
                    assertTrue(initialized.codexHome.contains("CodexAgent"))
                } finally {
                    connection.shutdown()
                }
            }
        }
    }

    @Test
    fun authenticatedAgentReadsAndModifiesTheLocalWorkspaceWhenCredentialIsAvailable() = runBlocking {
        val apiKey = NSProcessInfo.processInfo.environment["OPENAI_API_KEY"] as? String
        if (apiKey.isNullOrBlank()) {
            println("OPENAI_API_KEY is absent; protected simulator model E2E was not executed")
            return@runBlocking
        }
        TestWorkspace().use { test ->
            val factory = IosCodexRuntimeFactory(test.configuration)
            val fixture = "ios-local-e2e-${NSUUID().UUIDString}"
            assertTrue(factory.workspaceTools.call(test, "write_file", json("path" to "input.txt", "content" to fixture)).success)
            CodexAgentClient(
                runtimeFactory = factory,
                requestTimeoutMillis = 60_000,
                clientVersion = "0.2.0-e2e",
                builtInToolDispatcher = factory.workspaceTools,
            ).use { client ->
                client.authenticate(CodexAuthenticationMethod.ApiKey(apiKey))
                val session = client.openSession(
                    settings = AgentRuntimeSettings(
                        approvalPreset = AgentApprovalPreset.NEVER,
                        workingDirectory = test.workspace,
                    ),
                )
                val terminal = async {
                    withTimeout(180_000) {
                        client.events.filter { event ->
                            event is AgentEvent.TurnCompleted || event is AgentEvent.Failure
                        }.first()
                    }
                }
                client.sendTurn(
                    session,
                    AgentTurnRequest(
                        prompt = "Use read_file to read input.txt, then use apply_patch to add output.txt with exactly the same text.",
                        approvalPreset = AgentApprovalPreset.NEVER,
                        workingDirectory = test.workspace,
                    ),
                )
                assertIs<AgentEvent.TurnCompleted>(terminal.await())
                assertEquals(fixture, factory.workspaceTools.call(test, "read_file", json("path" to "output.txt")).text())
            }
        }
    }
}

private class TestWorkspace : AutoCloseable {
    val sandboxRoot = "${NSTemporaryDirectory().trimEnd('/')}/codex-agent-ios-${NSUUID().UUIDString}"
    val workspace = "$sandboxRoot/workspace"
    val configuration = IosCodexRuntimeConfiguration(
        sandboxRootPath = sandboxRoot,
        workspacePath = workspace,
    )

    init {
        check(
            NSFileManager.defaultManager.createDirectoryAtPath(
                workspace,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            ),
        ) { "Could not create test workspace" }
    }

    override fun close() {
        NSFileManager.defaultManager.removeItemAtPath(sandboxRoot, error = null)
    }
}

private suspend fun IosCodexWorkspaceTools.call(
    test: TestWorkspace,
    tool: String,
    arguments: JsonObject,
) = execute(
    BuiltInToolCall(
        threadId = "thread",
        turnId = "turn",
        callId = "call-$tool",
        pluginId = "ios-local-workspace",
        tool = tool,
        arguments = arguments,
        workspace = test.workspace,
        argumentsHash = "test",
    ),
)

private fun BuiltInToolResult.text(): String = (content.single() as BuiltInToolContent.Text).value

private fun json(vararg values: Pair<String, String>) = buildJsonObject {
    values.forEach { (key, value) -> put(key, value) }
}
