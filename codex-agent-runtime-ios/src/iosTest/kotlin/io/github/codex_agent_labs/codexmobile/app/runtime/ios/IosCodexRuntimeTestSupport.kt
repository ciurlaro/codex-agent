@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.codex_agent_labs.codexmobile.app.runtime.ios

import io.github.codex_agent_labs.codexmobile.agent.runtime.IosCodexCredentialProtection
import io.github.codex_agent_labs.codexmobile.agent.BuiltInToolCall
import io.github.codex_agent_labs.codexmobile.agent.BuiltInToolContent
import io.github.codex_agent_labs.codexmobile.agent.BuiltInToolResult
import io.github.codex_agent_labs.codexmobile.agent.ConversationId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

internal class TestWorkspace : AutoCloseable {
    val sandboxRoot = "${NSTemporaryDirectory().trimEnd('/')}/codex-agent-ios-${NSUUID().UUIDString}"
    val workspace = "$sandboxRoot/workspace"
    val codexHome = "$sandboxRoot/Library/Application Support/CodexAgent"
    val configuration = IosCodexRuntimeConfiguration(
        sandboxRootPath = sandboxRoot,
        workspacePath = workspace,
        credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
    )

    init {
        createDirectory(workspace)
    }

    override fun close() {
        NSFileManager.defaultManager.removeItemAtPath(sandboxRoot, error = null)
    }
}

internal fun createDirectory(path: String) {
    check(
        NSFileManager.defaultManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        ),
    ) { "Could not create test directory" }
}

internal suspend fun IosCodexWorkspaceTools.call(
    test: TestWorkspace,
    tool: String,
    arguments: JsonObject,
    workspace: String = test.workspace,
) = executeForTest(
    BuiltInToolCall(
        conversationId = ConversationId("thread"),
        turnId = "turn",
        callId = "call-$tool",
        pluginId = "ios-local-workspace",
        tool = tool,
        arguments = arguments,
        workspacePath = workspace,
        argumentsHash = "test",
    ),
)

internal fun BuiltInToolResult.text(): String = (content.single() as BuiltInToolContent.Text).value

internal fun json(vararg values: Pair<String, String>) = buildJsonObject {
    values.forEach { (key, value) -> put(key, value) }
}
