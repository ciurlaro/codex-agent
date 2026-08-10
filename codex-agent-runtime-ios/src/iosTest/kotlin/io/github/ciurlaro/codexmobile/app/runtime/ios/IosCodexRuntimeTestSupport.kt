@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.BuiltInToolContent
import io.github.ciurlaro.codexmobile.agent.BuiltInToolResult
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
    val unusedTemporaryPath = "$sandboxRoot/deprecated-unused-temporary-path"
    @Suppress("DEPRECATION")
    val configuration = IosCodexRuntimeConfiguration(
        sandboxRootPath = sandboxRoot,
        workspacePath = workspace,
        credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
        temporaryPath = unusedTemporaryPath,
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
) = execute(
    BuiltInToolCall(
        threadId = "thread",
        turnId = "turn",
        callId = "call-$tool",
        pluginId = "ios-local-workspace",
        tool = tool,
        arguments = arguments,
        workspace = workspace,
        argumentsHash = "test",
    ),
)

internal fun BuiltInToolResult.text(): String = (content.single() as BuiltInToolContent.Text).value

internal fun json(vararg values: Pair<String, String>) = buildJsonObject {
    values.forEach { (key, value) -> put(key, value) }
}
