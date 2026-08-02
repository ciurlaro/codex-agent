package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.jvm.JvmInline
import kotlinx.coroutines.flow.Flow

@JvmInline
value class CodexJsonLine(val value: String) {
    init {
        require('\n' !in value && '\r' !in value) { "A Codex JSON line must not contain a line break" }
    }
}

sealed interface CodexRuntimeEvent {
    data class Received(val line: CodexJsonLine) : CodexRuntimeEvent
    data class StartFailure(val message: String) : CodexRuntimeEvent
    data class IoFailure(val message: String) : CodexRuntimeEvent
    data object EndOfFile : CodexRuntimeEvent
    data class Exited(val code: Int) : CodexRuntimeEvent
}

interface CodexRuntime : AutoCloseable {
    val events: Flow<CodexRuntimeEvent>

    suspend fun start()

    suspend fun send(line: CodexJsonLine)
}

fun interface CodexRuntimeFactory {
    fun create(): CodexRuntime
}
