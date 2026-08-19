package io.github.ciurlaro.codexmobile.appserver.client

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerProtocolDescriptors
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.JSONRPCError
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.JSONRPCErrorError
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.JSONRPCResponse
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ServerNotification
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ServerRequest
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal suspend fun AppServerConnection.handleRuntimeEvent(source: CodexRuntime, event: CodexRuntimeEvent) {
    if (runtime !== source) return
    when (event) {
        is CodexRuntimeEvent.Received -> try {
            handleMessage(event.line.value)
        } catch (error: AppServerDeliveryException) {
            failRuntime(source, "event_delivery_overflow", error.visibleMessage(), terminal = true)
        } catch (error: Throwable) {
            failRuntime(source, "protocol_failure", error.visibleMessage())
        }
        is CodexRuntimeEvent.StartFailure -> failRuntime(source, "process_start", event.message)
        is CodexRuntimeEvent.IoFailure -> failRuntime(source, "io_failure", event.message)
        CodexRuntimeEvent.EndOfFile ->
            failRuntime(source, "unexpected_eof", "Codex app-server closed its output")
        is CodexRuntimeEvent.Exited ->
            failRuntime(source, "process_exit", "Codex app-server exited with code ${event.code}")
    }
}

internal suspend fun AppServerConnection.handleMessage(line: String) {
    val message = CONNECTION_JSON.parseToJsonElement(line) as? JsonObject
        ?: throw AppServerProtocolException("App-server message must be a JSON object")
    val method = message["method"]?.jsonPrimitive?.contentOrNull
    val id = message["id"]
    when {
        method != null && id != null -> {
            val descriptor = AppServerProtocolDescriptors.serverRequests[method]
            if (descriptor == null) {
                write(
                    CONNECTION_JSON.encodeToString(
                        JSONRPCError(JSONRPCErrorError(-32601, "Unknown server request: $method"), id),
                    ),
                )
                return
            }
            emit(AppServerEvent.Request(decode(ServerRequest.serializer(), message, "ServerRequest"), descriptor))
        }
        method != null -> {
            val descriptor = AppServerProtocolDescriptors.serverNotifications[method] ?: return
            emit(
                AppServerEvent.Notification(
                    decode(ServerNotification.serializer(), message, "ServerNotification"),
                    descriptor,
                ),
            )
        }
        id != null -> handleResponse(message)
        else -> throw AppServerProtocolException("App-server message has neither method nor id")
    }
}

internal suspend fun AppServerConnection.handleResponse(message: JsonObject) {
    val id = message["id"]?.jsonPrimitive?.longOrNull
        ?: throw AppServerProtocolException("App-server response id is not a client request id")
    val request = pending.remove(id) ?: return
    val error = message["error"]?.let {
        decode(JSONRPCError.serializer(), message, "JSONRPCError").error
    }
    if (error != null) {
        val exception = AppServerRpcException(error.code, error.message, error.data)
        when (request) {
            PendingRequest.Initialize -> failRuntime(runtime, "initialize_failed", exception.message.orEmpty())
            is PendingRequest.Request -> request.response.completeExceptionally(exception)
        }
        return
    }
    val response = decode(JSONRPCResponse.serializer(), message, "JSONRPCResponse").result
    when (request) {
        PendingRequest.Initialize -> completeInitialization(response)
        is PendingRequest.Request -> request.response.complete(response)
    }
}

internal suspend fun AppServerConnection.failRuntime(
    source: CodexRuntime?,
    code: String,
    message: String,
    terminal: Boolean = false,
) {
    if (source != null && runtime !== source) return
    val error = AppServerRuntimeException(message)
    pending.values.forEach { it.fail(error) }
    pending.clear()
    startWaiters.forEach { it.completeExceptionally(error) }
    startWaiters.clear()
    stopRuntime()
    if (terminal) terminalFailure = error
    mutableState.value = AppServerConnectionState.Failed(code, message)
    if (eventChannel.trySend(AppServerEvent.Failure(code, message)).isFailure) {
        val deliveryError = AppServerDeliveryException(
            "App Server event buffer exceeded $eventCapacity entries; connection stopped",
        )
        terminalFailure = AppServerRuntimeException(deliveryError.message.orEmpty())
        mutableState.value = AppServerConnectionState.Failed(
            "event_delivery_overflow",
            deliveryError.message.orEmpty(),
        )
        eventChannel.close(deliveryError)
    }
}

internal suspend fun AppServerConnection.stopRuntime() = withContext(NonCancellable) {
    val events = runtimeEvents
    runtimeEvents = null
    val stopRequested = runtimeStopRequested
    runtimeStopRequested = null
    val stopped = runtime
    runtime = null
    stopRequested?.complete(Unit)
    var failure: Throwable? = null
    try {
        stopped?.close()
    } catch (error: Throwable) {
        failure = error
    }
    try {
        if (failure == null) events?.join() else events?.cancelAndJoin()
    } catch (error: Throwable) {
        if (failure == null) failure = error
    }
    failure?.let { throw it }
}

internal suspend fun AppServerConnection.write(encoded: String) {
    check(encoded.encodeToByteArray().size <= MAX_MESSAGE_BYTES) { "JSON-RPC message exceeds the byte limit" }
    checkNotNull(runtime) { "Codex app-server is not running" }.send(CodexJsonLine(encoded))
}

internal fun AppServerConnection.emit(event: AppServerEvent) {
    if (eventChannel.trySend(event).isFailure) {
        throw AppServerDeliveryException(
            "App Server event buffer exceeded $eventCapacity entries; connection stopped",
        )
    }
}

internal fun <T> decode(serializer: KSerializer<T>, element: JsonElement, type: String): T =
    try {
        CONNECTION_JSON.decodeFromJsonElement(serializer, element)
    } catch (error: Throwable) {
        throw AppServerProtocolException("Invalid $type", error)
    }
