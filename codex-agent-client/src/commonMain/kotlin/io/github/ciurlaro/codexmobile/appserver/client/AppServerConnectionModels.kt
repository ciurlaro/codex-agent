package io.github.ciurlaro.codexmobile.appserver.client

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerNotificationDescriptor
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerRequestDescriptor
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeResponse
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ServerNotification
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ServerRequest
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal sealed interface AppServerConnectionState {
    public data object Stopped : AppServerConnectionState
    public data object Starting : AppServerConnectionState
    public data class Ready(public val initializeResponse: InitializeResponse) : AppServerConnectionState
    public data class Failed(public val code: String, public val message: String) : AppServerConnectionState
    public data object Closed : AppServerConnectionState
}

internal sealed interface AppServerEvent {
    public data class Request(
        public val value: ServerRequest,
        public val descriptor: AppServerRequestDescriptor,
    ) : AppServerEvent

    public data class Notification(
        public val value: ServerNotification,
        public val descriptor: AppServerNotificationDescriptor,
    ) : AppServerEvent

    public data class Failure(public val code: String, public val message: String) : AppServerEvent
}

internal class AppServerRpcException(
    public val code: Long,
    public val detail: String,
    public val data: JsonElement? = null,
) : IllegalStateException("App-server error $code: $detail")

internal class AppServerRuntimeException(message: String) : IllegalStateException(message)

internal class AppServerProtocolException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class AppServerDeliveryException(message: String) : IllegalStateException(message)

internal class AppServerTimeoutException(message: String) : IllegalStateException(message)

internal sealed interface PendingRequest {
    data object Initialize : PendingRequest
    data class Request(val response: CompletableDeferred<JsonElement>) : PendingRequest

    fun fail(error: Throwable) {
        if (this is Request) response.completeExceptionally(error)
    }
}

internal sealed interface ConnectionCommand {
    data class Start(val response: CompletableDeferred<InitializeResponse>) : ConnectionCommand
    data class CancelStart(val response: CompletableDeferred<InitializeResponse>) : ConnectionCommand
    data class Request(
        val method: String,
        val params: JsonElement,
        val response: CompletableDeferred<JsonElement>,
    ) : ConnectionCommand
    data class CancelRequest(val response: CompletableDeferred<JsonElement>) : ConnectionCommand
    data class Response(val encoded: String, val acknowledgement: CompletableDeferred<Unit>) : ConnectionCommand
    data class RuntimeEvent(val source: CodexRuntime, val event: CodexRuntimeEvent) : ConnectionCommand
    data class RuntimeFlowFailed(val source: CodexRuntime, val message: String) : ConnectionCommand
    data object Close : ConnectionCommand
}

internal val CONNECTION_JSON = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

internal const val DEFAULT_COMMAND_CAPACITY = 256
internal const val DEFAULT_EVENT_CAPACITY = 256
internal const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024

internal fun Throwable.visibleMessage(): String =
    message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"

internal suspend fun <T> withAppServerTimeout(
    timeoutMillis: Long,
    operation: String,
    block: suspend () -> T,
): T = try {
    withTimeout(timeoutMillis) { block() }
} catch (error: TimeoutCancellationException) {
    currentCoroutineContext().ensureActive()
    throw AppServerTimeoutException("$operation timed out after ${timeoutMillis}ms")
}
