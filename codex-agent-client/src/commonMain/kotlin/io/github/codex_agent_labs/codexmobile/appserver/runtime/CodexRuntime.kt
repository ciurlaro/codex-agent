package io.github.codex_agent_labs.codexmobile.appserver.runtime

import kotlinx.coroutines.flow.Flow

public data class CodexJsonLine(public val value: String) {
    init {
        require('\n' !in value && '\r' !in value) { "A Codex JSON line must not contain a line break" }
    }
}

public sealed interface CodexRuntimeEvent {
    public data class Received(public val line: CodexJsonLine) : CodexRuntimeEvent
    public data class StartFailure(public val message: String) : CodexRuntimeEvent
    public data class IoFailure(public val message: String) : CodexRuntimeEvent
    public data object EndOfFile : CodexRuntimeEvent
    public data class Exited(public val code: Int) : CodexRuntimeEvent
}

public interface CodexRuntime : AutoCloseable {
    public val events: Flow<CodexRuntimeEvent>

    @Throws(Exception::class)
    public suspend fun start()

    @Throws(Exception::class)
    public suspend fun send(line: CodexJsonLine)

    public override fun close()
}

public fun interface CodexRuntimeFactory {
    @Throws(Exception::class)
    public fun create(): CodexRuntime
}
