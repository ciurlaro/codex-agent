package io.github.codex_agent_labs.codexmobile.appserver.client

import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.AppServerClientMethods
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.ClientNotification
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.ClientNotificationInitializedNotification
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.JSONRPCRequest
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntime
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntimeEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal suspend fun AppServerConnection.sendResponse(encoded: String, timeoutMillis: Long) {
    val acknowledgement = CompletableDeferred<Unit>()
    withAppServerTimeout(timeoutMillis, "App-server response") {
        commands.send(ConnectionCommand.Response(encoded, acknowledgement))
        acknowledgement.await()
    }
}

internal suspend fun AppServerConnection.commandLoop() {
    var closeRequested = false
    try {
        loop@ for (command in commands) {
            when (command) {
                is ConnectionCommand.Start -> start(command.response)
                is ConnectionCommand.CancelStart -> cancelStart(command.response)
                is ConnectionCommand.Request -> sendRequest(command)
                is ConnectionCommand.CancelRequest -> cancelRequest(command.response)
                is ConnectionCommand.Response -> sendServerResponse(command)
                is ConnectionCommand.RuntimeEvent -> handleRuntimeEvent(command.source, command.event)
                is ConnectionCommand.RuntimeFlowFailed -> failRuntime(
                    command.source,
                    "io_failure",
                    command.message,
                )
                ConnectionCommand.Close -> {
                    closeRequested = true
                    break@loop
                }
            }
        }
    } finally {
        val error = AppServerRuntimeException("Codex connection is closed")
        pending.values.forEach { it.fail(error) }
        pending.clear()
        startWaiters.forEach { it.completeExceptionally(error) }
        startWaiters.clear()
        val stopFailure = try {
            stopRuntime()
            null
        } catch (error: Throwable) {
            error
        }
        if (closeRequested || mutableState.value !is AppServerConnectionState.Failed) {
            mutableState.value = AppServerConnectionState.Closed
        }
        commands.close()
        eventChannel.close()
        if (stopFailure == null) closed.complete(Unit) else closed.completeExceptionally(stopFailure)
        scope.cancel()
    }
}

internal suspend fun AppServerConnection.start(waiter: CompletableDeferred<io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.InitializeResponse>) {
    terminalFailure?.let {
        waiter.completeExceptionally(it)
        return
    }
    when (val current = mutableState.value) {
        is AppServerConnectionState.Ready -> {
            waiter.complete(current.initializeResponse)
            return
        }
        AppServerConnectionState.Starting -> {
            startWaiters += waiter
            return
        }
        AppServerConnectionState.Closed -> {
            waiter.completeExceptionally(AppServerRuntimeException("Codex connection is closed"))
            return
        }
        is AppServerConnectionState.Failed,
        AppServerConnectionState.Stopped,
        -> Unit
    }

    startWaiters += waiter
    mutableState.value = AppServerConnectionState.Starting
    val started = try {
        runtimeFactory.create()
    } catch (error: Throwable) {
        failRuntime(null, "process_start", error.visibleMessage())
        return
    }
    runtime = started
    val stopRequested = CompletableDeferred<Unit>()
    runtimeStopRequested = stopRequested
    runtimeEvents = scope.launch { collectRuntime(started, stopRequested) }
    try {
        started.start()
        val id = nextRequestId++
        pending[id] = PendingRequest.Initialize
        write(
            CONNECTION_JSON.encodeToString(
                JSONRPCRequest(
                    id = JsonPrimitive(id),
                    method = AppServerClientMethods.Initialize.descriptor.method,
                    params = CONNECTION_JSON.encodeToJsonElement(
                        AppServerClientMethods.Initialize.paramsSerializer,
                        initializeParams,
                    ),
                ),
            ),
        )
    } catch (error: Throwable) {
        failRuntime(started, "initialize_failed", error.visibleMessage())
    }
}

internal suspend fun AppServerConnection.cancelStart(waiter: CompletableDeferred<io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.InitializeResponse>) {
    startWaiters -= waiter
    if (startWaiters.isNotEmpty() || mutableState.value != AppServerConnectionState.Starting) return
    pending.entries.removeAll { (_, value) -> value === PendingRequest.Initialize }
    stopRuntime()
    mutableState.value = AppServerConnectionState.Stopped
}

internal suspend fun AppServerConnection.sendRequest(command: ConnectionCommand.Request) {
    if (mutableState.value !is AppServerConnectionState.Ready) {
        command.response.completeExceptionally(AppServerRuntimeException("Codex app-server is not ready"))
        return
    }
    val id = nextRequestId++
    pending[id] = PendingRequest.Request(command.response)
    try {
        write(CONNECTION_JSON.encodeToString(JSONRPCRequest(JsonPrimitive(id), command.method, command.params)))
    } catch (error: Throwable) {
        pending.remove(id)
        command.response.completeExceptionally(error)
        failRuntime(runtime, "io_failure", error.visibleMessage())
    }
}

internal fun AppServerConnection.cancelRequest(response: CompletableDeferred<JsonElement>) {
    val entry = pending.entries.firstOrNull {
        (it.value as? PendingRequest.Request)?.response === response
    } ?: return
    pending.remove(entry.key)
    response.cancel()
}

internal suspend fun AppServerConnection.sendServerResponse(command: ConnectionCommand.Response) {
    try {
        write(command.encoded)
        command.acknowledgement.complete(Unit)
    } catch (error: Throwable) {
        command.acknowledgement.completeExceptionally(error)
        failRuntime(runtime, "io_failure", error.visibleMessage())
    }
}

internal suspend fun AppServerConnection.collectRuntime(
    source: CodexRuntime,
    stopRequested: CompletableDeferred<Unit>,
) {
    try {
        source.events.collect { event ->
            forwardRuntimeCommand(ConnectionCommand.RuntimeEvent(source, event), stopRequested)
        }
        forwardRuntimeCommand(ConnectionCommand.RuntimeEvent(source, CodexRuntimeEvent.EndOfFile), stopRequested)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        forwardRuntimeCommand(ConnectionCommand.RuntimeFlowFailed(source, error.visibleMessage()), stopRequested)
    }
}

private suspend fun AppServerConnection.forwardRuntimeCommand(
    command: ConnectionCommand,
    stopRequested: CompletableDeferred<Unit>,
) {
    if (stopRequested.isCompleted) return
    select<Unit> {
        commands.onSend(command) {}
        stopRequested.onAwait {}
    }
}

internal suspend fun AppServerConnection.completeInitialization(result: JsonElement) {
    val response = try {
        decode(AppServerClientMethods.Initialize.responseSerializer, result, "InitializeResponse")
    } catch (error: Throwable) {
        failRuntime(runtime, "initialize_failed", error.visibleMessage())
        return
    }
    try {
        write(CONNECTION_JSON.encodeToString<ClientNotification>(ClientNotificationInitializedNotification()))
    } catch (error: Throwable) {
        failRuntime(runtime, "initialize_failed", error.visibleMessage())
        return
    }
    mutableState.value = AppServerConnectionState.Ready(response)
    startWaiters.forEach { it.complete(response) }
    startWaiters.clear()
}
