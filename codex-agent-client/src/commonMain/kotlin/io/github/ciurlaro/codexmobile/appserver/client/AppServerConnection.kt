package io.github.ciurlaro.codexmobile.appserver.client

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerMethod
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeResponse
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.JSONRPCError
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.JSONRPCErrorError
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.JSONRPCResponse
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement

internal class AppServerConnection(
    internal val runtimeFactory: CodexRuntimeFactory,
    internal val initializeParams: InitializeParams,
    internal val requestTimeoutMillis: Long = 30.seconds.inWholeMilliseconds,
    commandCapacity: Int = DEFAULT_COMMAND_CAPACITY,
    internal val eventCapacity: Int = DEFAULT_EVENT_CAPACITY,
) : AutoCloseable {
    init {
        require(requestTimeoutMillis > 0) { "Request timeout must be positive" }
        require(commandCapacity > 0) { "Command capacity must be positive" }
        require(eventCapacity > 0) { "Event capacity must be positive" }
    }

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val commands = Channel<ConnectionCommand>(commandCapacity)
    internal val eventChannel = Channel<AppServerEvent>(eventCapacity)
    internal val mutableState = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Stopped)
    internal val closed = CompletableDeferred<Unit>()

    public val state: StateFlow<AppServerConnectionState> = mutableState.asStateFlow()
    public val events: Flow<AppServerEvent> = eventChannel.receiveAsFlow()

    // These fields are confined to the command-loop coroutine.
    internal var runtime: CodexRuntime? = null
    internal var runtimeEvents: Job? = null
    internal var runtimeStopRequested: CompletableDeferred<Unit>? = null
    internal var nextRequestId = 1L
    internal val pending = mutableMapOf<Long, PendingRequest>()
    internal val startWaiters = mutableSetOf<CompletableDeferred<InitializeResponse>>()
    internal var terminalFailure: AppServerRuntimeException? = null

    init {
        scope.launch { commandLoop() }
    }

    public suspend fun ensureStarted(timeoutMillis: Long = requestTimeoutMillis): InitializeResponse {
        val response = CompletableDeferred<InitializeResponse>()
        try {
            return withAppServerTimeout(timeoutMillis, "App-server startup") {
                commands.send(ConnectionCommand.Start(response))
                response.await()
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { commands.send(ConnectionCommand.CancelStart(response)) }
            }
        }
    }

    public suspend fun <P, R> request(
        method: AppServerMethod<P, R>,
        params: P,
        timeoutMillis: Long = requestTimeoutMillis,
    ): R {
        ensureStarted()
        val response = CompletableDeferred<JsonElement>()
        val encodedParams = CONNECTION_JSON.encodeToJsonElement(method.paramsSerializer, params)
        val encodedResponse = try {
            withAppServerTimeout(timeoutMillis, "App-server request ${method.descriptor.method}") {
                commands.send(ConnectionCommand.Request(method.descriptor.method, encodedParams, response))
                response.await()
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { commands.send(ConnectionCommand.CancelRequest(response)) }
            }
        }
        return decode(method.responseSerializer, encodedResponse, method.descriptor.responseType)
    }

    public suspend fun <P, R> respond(
        id: JsonElement,
        method: AppServerMethod<P, R>,
        result: R,
        timeoutMillis: Long = requestTimeoutMillis,
    ) {
        val encoded = CONNECTION_JSON.encodeToJsonElement(method.responseSerializer, result)
        sendResponse(CONNECTION_JSON.encodeToString(JSONRPCResponse(id, encoded)), timeoutMillis)
    }

    public suspend fun respondError(
        id: JsonElement,
        code: Long,
        message: String,
        data: JsonElement? = null,
        timeoutMillis: Long = requestTimeoutMillis,
    ) {
        sendResponse(
            CONNECTION_JSON.encodeToString(JSONRPCError(JSONRPCErrorError(code, message, data), id)),
            timeoutMillis,
        )
    }

    public suspend fun shutdown() {
        close()
        closed.await()
    }

    override fun close() {
        if (commands.trySend(ConnectionCommand.Close).isFailure) scope.cancel()
    }
}
