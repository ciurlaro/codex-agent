@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.app.runtime.ios

import cnames.structs.CodexAgentIosRuntime
import io.github.ciurlaro.codexmobile.agent.BuiltInToolResult
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.CodexAgentIosBuffer
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_buffer_free
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_runtime_destroy
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_runtime_receive
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_runtime_send
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_runtime_shutdown
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_runtime_start
import io.github.ciurlaro.codexmobile.agent.runtime.ios.native.codex_agent_ios_workspace_execute
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import kotlin.concurrent.Volatile
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class IosCodexRuntime(
    private val configuration: IosCodexRuntimeConfiguration,
) : CodexRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventsChannel = Channel<CodexRuntimeEvent>(EVENT_CAPACITY)
    private val lifecycle = Mutex()

    @Volatile
    private var closed = false

    private var native: CPointer<CodexAgentIosRuntime>? = null
    private var receiver: Job? = null
    private var started = false

    override val events: Flow<CodexRuntimeEvent> = eventsChannel.receiveAsFlow()

    override suspend fun start() = lifecycle.withLock {
        check(!closed) { "Codex runtime is closed" }
        check(!started) { "Codex runtime was already started" }
        started = true
        try {
            val handle = withContext(Dispatchers.Default) { startNative(configuration) }
            native = handle
            receiver = scope.launch { receiveEvents(handle) }
        } catch (error: Throwable) {
            eventsChannel.trySend(CodexRuntimeEvent.StartFailure(error.visibleMessage()))
            throw error
        }
    }

    override suspend fun send(line: CodexJsonLine) = lifecycle.withLock {
        check(!closed) { "Codex runtime is closed" }
        val handle = checkNotNull(native) { "Codex App Server is not running" }
        try {
            withContext(Dispatchers.Default) { sendNative(handle, line.value) }
        } catch (error: Throwable) {
            eventsChannel.trySend(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
            throw error
        }
    }

    override fun close() = runBlocking { closeSuspending() }

    private suspend fun closeSuspending() {
        val state = lifecycle.withLock {
            if (closed) return
            closed = true
            native to receiver
        }
        val handle = state.first
        val receiverJob = state.second
        var failure: Throwable? = null
        if (handle != null) {
            runCatching {
                withContext(Dispatchers.Default) { shutdownNative(handle) }
            }.onFailure { error ->
                failure = error
                eventsChannel.trySend(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
            }
        }
        receiverJob?.join()
        if (handle != null) codex_agent_ios_runtime_destroy(handle)
        lifecycle.withLock {
            native = null
            receiver = null
        }
        scope.cancel()
        eventsChannel.close()
        failure?.let { throw it }
    }

    private fun receiveEvents(handle: CPointer<CodexAgentIosRuntime>) {
        while (true) {
            val event = runCatching { receiveNative(handle) }.getOrElse { error ->
                emit(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
                return
            } ?: return
            if (!closed) emit(event)
        }
    }

    private fun emit(event: CodexRuntimeEvent) {
        val result = eventsChannel.trySend(event)
        if (result.isFailure && !result.isClosed && !closed) {
            eventsChannel.close(IosCodexRuntimeException("iOS runtime event queue overflow"))
        }
    }

    private fun Throwable.visibleMessage(): String =
        message?.take(500)?.takeIf(String::isNotBlank) ?: "iOS Codex runtime failure"

    private companion object {
        const val EVENT_CAPACITY = 64
    }
}

internal suspend fun executeIosWorkspaceTool(
    configuration: IosCodexRuntimeConfiguration,
    tool: String,
    arguments: JsonObject,
): BuiltInToolResult = withContext(Dispatchers.Default) {
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
    BuiltInToolResult.text(decoded.text, decoded.success)
}

private fun startNative(configuration: IosCodexRuntimeConfiguration): CPointer<CodexAgentIosRuntime> =
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

private fun sendNative(runtime: CPointer<CodexAgentIosRuntime>, message: String) = memScoped {
    val error = alloc<CodexAgentIosBuffer>()
    error.clear()
    val status = withUtf8(message) { data, size ->
        codex_agent_ios_runtime_send(runtime, data, size, error.ptr)
    }
    checkStatus(status, error)
}

private fun receiveNative(runtime: CPointer<CodexAgentIosRuntime>): CodexRuntimeEvent? = memScoped {
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

private fun shutdownNative(runtime: CPointer<CodexAgentIosRuntime>) = memScoped {
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

class IosCodexRuntimeException(message: String) : IllegalStateException(message)

private val RUNTIME_JSON = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
}
