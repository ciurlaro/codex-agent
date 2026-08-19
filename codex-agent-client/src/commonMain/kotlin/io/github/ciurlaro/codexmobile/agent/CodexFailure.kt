package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import kotlinx.coroutines.CancellationException

public data class CodexFailure(
    public val code: String,
    public val message: String,
    public val isRecoverable: Boolean,
) {
    init {
        require(code.isNotBlank()) { "Failure code must not be blank" }
        require(message.isNotBlank()) { "Failure message must not be blank" }
        require(message.length <= MAX_CODEX_FAILURE_MESSAGE_CHARS) {
            "Failure message must not exceed $MAX_CODEX_FAILURE_MESSAGE_CHARS characters"
        }
    }
}

public class CodexOperationException(
    public val failure: CodexFailure,
    cause: Throwable? = null,
) : Exception(failure.message, cause)

internal fun codexFailure(
    code: String,
    message: String?,
    fallback: String,
    isRecoverable: Boolean,
): CodexFailure = CodexFailure(
    code = code,
    message = message?.takeIf(String::isNotBlank)?.take(MAX_CODEX_FAILURE_MESSAGE_CHARS) ?: fallback,
    isRecoverable = isRecoverable,
)

internal fun Throwable.asCodexOperationException(
    code: String,
    fallback: String,
    isRecoverable: Boolean = true,
): CodexOperationException {
    if (this is CancellationException) throw this
    if (this is CodexOperationException) return this
    if (this is AgentPluginUnavailableException) {
        return CodexOperationException(
            codexFailure(
                code = "plugin_unavailable",
                message = message,
                fallback = fallback,
                isRecoverable = true,
            ),
            this,
        )
    }
    val publicMessage = when (this) {
        is AppServerRpcException -> detail
        is AppServerTimeoutException -> message
        else -> fallback
    }
    return CodexOperationException(codexFailure(code, publicMessage, fallback, isRecoverable), this)
}

internal suspend inline fun <T> codexOperation(
    code: String = "operation_failed",
    fallback: String = "Codex operation failed",
    isRecoverable: Boolean = true,
    operation: suspend () -> T,
): T = try {
    operation()
} catch (error: Throwable) {
    throw error.asCodexOperationException(code, fallback, isRecoverable)
}

internal suspend fun completeCleanup(actions: List<suspend () -> Unit>) {
    var firstFailure: Throwable? = null
    actions.forEach { action ->
        try {
            action()
        } catch (error: Throwable) {
            if (firstFailure == null) firstFailure = error
        }
    }
    firstFailure?.let { throw it }
}

internal const val MAX_CODEX_FAILURE_MESSAGE_CHARS: Int = 500
