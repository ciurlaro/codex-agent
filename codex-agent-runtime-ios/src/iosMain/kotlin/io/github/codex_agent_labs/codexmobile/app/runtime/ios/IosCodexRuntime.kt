@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.codex_agent_labs.codexmobile.app.runtime.ios

import cnames.structs.CodexAgentIosRuntime
import io.github.codex_agent_labs.codexmobile.agent.runtime.ios.native.codex_agent_ios_runtime_destroy
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexJsonLine
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntime
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntimeEvent
import kotlin.concurrent.Volatile
import kotlinx.cinterop.CPointer
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
    private var credentialMonitor: IosCodexCredentialProtectionMonitor? = null
    private var started = false

    override val events: Flow<CodexRuntimeEvent> = eventsChannel.receiveAsFlow()

    override suspend fun start() = lifecycle.withLock {
        check(!closed) { "Codex runtime is closed" }
        check(!started) { "Codex runtime was already started" }
        started = true
        try {
            val handle = withContext(Dispatchers.Default) { startNative(configuration) }
            var monitor: IosCodexCredentialProtectionMonitor? = null
            try {
                monitor = withContext(Dispatchers.Default) {
                    IosCodexCredentialProtectionMonitor(configuration) { error ->
                        emit(CodexRuntimeEvent.IoFailure(error.visibleMessage()))
                    }
                }
            } catch (error: Throwable) {
                try {
                    withContext(Dispatchers.Default) { shutdownNative(handle) }
                } catch (_: Throwable) {
                    // The protection error remains authoritative; destruction still joins native work.
                }
                codex_agent_ios_runtime_destroy(handle)
                throw error
            }
            native = handle
            credentialMonitor = monitor
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
            Triple(native, receiver, credentialMonitor)
        }
        val handle = state.first
        val receiverJob = state.second
        val protectionMonitor = state.third
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
        protectionMonitor?.close()
        lifecycle.withLock {
            native = null
            receiver = null
            credentialMonitor = null
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
