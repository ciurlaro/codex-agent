@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.app.runtime.ios

import cnames.structs.CodexAgentIosRuntime
import io.github.ciurlaro.codexmobile.agent.BuiltInToolResult
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.CodexAgentIosBuffer
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_buffer_free
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_runtime_receive
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_runtime_send
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_runtime_shutdown
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_runtime_start
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_workspace_execute
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSURL

internal suspend fun executeIosWorkspaceTool(
    configuration: IosCodexRuntimeConfiguration,
    tool: String,
    arguments: JsonObject,
): BuiltInToolResult = withContext(Dispatchers.Default) {
    if (configuration.securityScopedWorkspace) {
        coordinateIosWorkspace(configuration, tool in IOS_MUTATING_TOOLS) { coordinated ->
            executeIosWorkspaceToolDirect(coordinated, tool, arguments)
        }
    } else {
        executeIosWorkspaceToolDirect(configuration, tool, arguments)
    }
}

private fun executeIosWorkspaceToolDirect(
    configuration: IosCodexRuntimeConfiguration,
    tool: String,
    arguments: JsonObject,
): BuiltInToolResult {
    val response = memScoped {
        val result = alloc<CodexAgentIosBuffer>()
        val error = alloc<CodexAgentIosBuffer>()
        result.clear()
        error.clear()
        val status = withUtf8(RUNTIME_JSON.encodeToString(configuration)) { configData, configSize ->
            withUtf8(tool) { toolData, toolSize ->
                withUtf8(arguments.toString()) { argumentData, argumentSize ->
                    codex_agent_ios_workspace_execute(
                        configData,
                        configSize,
                        toolData,
                        toolSize,
                        argumentData,
                        argumentSize,
                        result.ptr,
                        error.ptr,
                    )
                }
            }
        }
        checkStatus(status, error)
        result.takeString()
    }
    val decoded = RUNTIME_JSON.decodeFromString<NativeWorkspaceToolResult>(response)
    return BuiltInToolResult.text(decoded.text, decoded.success)
}

private fun coordinateIosWorkspace(
    configuration: IosCodexRuntimeConfiguration,
    isMutation: Boolean,
    operation: (IosCodexRuntimeConfiguration) -> BuiltInToolResult,
): BuiltInToolResult = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    var result: BuiltInToolResult? = null
    var failure: Throwable? = null
    val accessor: (NSURL?) -> Unit = { coordinatedUrl ->
        try {
            val path = requireNotNull(coordinatedUrl?.path) { "Coordinated workspace URL is unavailable" }
            result = operation(configuration.copy(workspacePath = path))
        } catch (caught: Throwable) {
            failure = caught
        }
    }
    val coordinator = NSFileCoordinator(null)
    val workspaceUrl = NSURL.fileURLWithPath(configuration.workspacePath)
    if (isMutation) {
        coordinator.coordinateWritingItemAtURL(workspaceUrl, 0u, error.ptr, accessor)
    } else {
        coordinator.coordinateReadingItemAtURL(workspaceUrl, 0u, error.ptr, accessor)
    }
    failure?.let { throw it }
    error.value?.let { throw IosCodexRuntimeException(it.localizedDescription) }
    checkNotNull(result) { "Workspace coordination did not execute" }
}

internal fun startNative(configuration: IosCodexRuntimeConfiguration): CPointer<CodexAgentIosRuntime> =
    memScoped {
        val output = alloc<CPointerVar<CodexAgentIosRuntime>>()
        val error = alloc<CodexAgentIosBuffer>()
        output.value = null
        error.clear()
        val status = withUtf8(RUNTIME_JSON.encodeToString(configuration)) { data, size ->
            codex_agent_ios_runtime_start(data, size, output.ptr, error.ptr)
        }
        checkStatus(status, error)
        checkNotNull(output.value) { "Native iOS runtime returned a null handle" }
    }

internal fun sendNative(runtime: CPointer<CodexAgentIosRuntime>, message: String) = memScoped {
    val error = alloc<CodexAgentIosBuffer>()
    error.clear()
    val status = withUtf8(message) { data, size ->
        codex_agent_ios_runtime_send(runtime, data, size, error.ptr)
    }
    checkStatus(status, error)
}

internal fun receiveNative(runtime: CPointer<CodexAgentIosRuntime>): CodexRuntimeEvent? = memScoped {
    val kind = alloc<kotlinx.cinterop.IntVar>()
    val payload = alloc<CodexAgentIosBuffer>()
    val error = alloc<CodexAgentIosBuffer>()
    payload.clear()
    error.clear()
    val status = codex_agent_ios_runtime_receive(runtime, kind.ptr, payload.ptr, error.ptr)
    if (status == 1) return@memScoped null
    checkStatus(status, error)
    val value = payload.takeString()
    when (kind.value) {
        1 -> CodexRuntimeEvent.Received(CodexJsonLine(value))
        2 -> CodexRuntimeEvent.IoFailure(value)
        3 -> CodexRuntimeEvent.EndOfFile
        4 -> CodexRuntimeEvent.Exited(value.toIntOrNull() ?: -1)
        else -> throw IosCodexRuntimeException("Unknown native iOS runtime event ${kind.value}")
    }
}

internal fun shutdownNative(runtime: CPointer<CodexAgentIosRuntime>) = memScoped {
    val error = alloc<CodexAgentIosBuffer>()
    error.clear()
    checkStatus(codex_agent_ios_runtime_shutdown(runtime, error.ptr), error)
}

private inline fun <T> withUtf8(
    value: String,
    block: (CPointer<UByteVar>?, ULong) -> T,
): T {
    val bytes = value.encodeToByteArray()
    return bytes.usePinned { pinned ->
        block(
            if (bytes.isEmpty()) null else pinned.addressOf(0).reinterpret(),
            bytes.size.convert(),
        )
    }
}

private fun CodexAgentIosBuffer.clear() {
    data = null
    length = 0u
}

private fun CodexAgentIosBuffer.takeString(): String {
    val bytes = data?.readBytes(length.toInt()) ?: ByteArray(0)
    codex_agent_ios_buffer_free(ptr)
    return bytes.decodeToString()
}

private fun checkStatus(status: Int, error: CodexAgentIosBuffer) {
    if (status == 0) return
    val message = error.takeString().ifBlank { "Native iOS runtime operation failed" }
    throw IosCodexRuntimeException(message)
}

@Serializable
private data class NativeWorkspaceToolResult(
    val success: Boolean,
    val text: String,
)

internal class IosCodexRuntimeException(message: String) : IllegalStateException(message)

private val RUNTIME_JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}

internal val IOS_MUTATING_TOOLS = setOf("apply_patch", "write_file")
