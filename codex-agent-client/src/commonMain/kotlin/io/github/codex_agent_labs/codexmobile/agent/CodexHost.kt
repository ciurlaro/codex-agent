package io.github.codex_agent_labs.codexmobile.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public sealed interface CodexHostState {
    public data object New : CodexHostState

    public data object Restoring : CodexHostState

    public data class WorkspaceRequired(
        public val requirement: CodexWorkspaceResolution.SelectionRequired,
    ) : CodexHostState

    public data class Preparing(
        public val workspace: CodexWorkspace,
    ) : CodexHostState

    public data class Ready(
        public val agent: CodexAgent,
    ) : CodexHostState

    public data class Failed(
        public val workspace: CodexWorkspace?,
        public val failure: CodexFailure,
    ) : CodexHostState

    public data object Closed : CodexHostState
}

public class CodexHost private constructor(
    private val platform: CodexPlatform,
    private val clientInfo: CodexClientInfo,
    private val scope: CoroutineScope,
    private val requestTimeoutMillis: Long = 20_000,
) {
    public constructor(
        platform: CodexPlatform,
        clientInfo: CodexClientInfo,
    ) : this(
        platform,
        clientInfo,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    public constructor(
        platform: CodexPlatform,
        parentScope: CoroutineScope,
        clientInfo: CodexClientInfo,
    ) : this(
        platform,
        clientInfo,
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])),
    )

    internal constructor(
        platform: CodexPlatform,
        parentScope: CoroutineScope,
        clientInfo: CodexClientInfo,
        requestTimeoutMillis: Long,
        @Suppress("UNUSED_PARAMETER") testHook: Unit = Unit,
    ) : this(
        platform,
        clientInfo,
        CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job])),
        requestTimeoutMillis,
    )

    private val lifecycleLock = Mutex()
    private val workspaceSelectionLock = Mutex()
    private val mutableState = MutableStateFlow<CodexHostState>(CodexHostState.New)
    private var agent: CodexAgent? = null
    private var generation = 0L
    private var closeStarted = false
    private val closeCompletion = CompletableDeferred<Throwable?>()
    private val activeTransitions = mutableSetOf<CompletableDeferred<Throwable?>>()

    public val state: StateFlow<CodexHostState> = mutableState.asStateFlow()

    init {
        require(requestTimeoutMillis > 0) { "Request timeout must be positive" }
    }

    @Throws(Exception::class)
    public suspend fun start(): Unit {
        val start = lifecycleLock.withLock {
            when (val current = mutableState.value) {
                CodexHostState.New -> StartTransition(beginTransition(CodexHostState.Restoring), null)
                is CodexHostState.Failed -> {
                    val workspace = current.workspace
                    StartTransition(
                        beginTransition(
                            workspace?.let(CodexHostState::Preparing) ?: CodexHostState.Restoring,
                        ),
                        workspace,
                    )
                }
                is CodexHostState.WorkspaceRequired -> error("A workspace selection is required")
                CodexHostState.Closed -> error("Codex host is closed")
                else -> error("Codex host has already been started")
            }
        }
        var cleanupFailure: Throwable? = null
        try {
            closePrevious(start.transition, start.workspace)
            val workspace = start.workspace
            if (workspace != null) {
                prepare(start.transition.generation, workspace)
            } else {
                restore(start.transition.generation)
            }
            ensureTransitionCurrent(start.transition)
        } catch (error: HostTransitionCleanupException) {
            cleanupFailure = error.cleanupFailure
            failLifecycle(
                start.transition.generation,
                start.workspace,
                error.cleanupFailure,
                error.code,
                error.fallback,
                error.isRecoverable,
            )
        } catch (error: HostTransitionAcquisitionException) {
            cleanupFailure = error.cleanupFailure
            failLifecycle(
                start.transition.generation,
                start.workspace,
                error.acquisitionFailure,
                "runtime_prepare_failed",
                "Could not prepare Codex",
            )
        } finally {
            finishTransition(start.transition, cleanupFailure)
        }
    }

    @Throws(Exception::class)
    public suspend fun selectWorkspace(selection: CodexWorkspaceSelection): Unit {
        val transition = lifecycleLock.withLock {
            check(mutableState.value != CodexHostState.Closed) { "Codex host is closed" }
            beginTransition(CodexHostState.Restoring)
        }
        var cleanupFailure: Throwable? = null
        try {
            closePrevious(transition, null)
            val resolution = try {
                workspaceSelectionLock.withLock {
                    val isCurrent = lifecycleLock.withLock {
                        transition.generation == generation && mutableState.value != CodexHostState.Closed
                    }
                    if (!isCurrent) throw CancellationException("Workspace selection was superseded")
                    platform.workspaceStore.select(selection)
                }
            } catch (error: Throwable) {
                failLifecycle(
                    transition.generation,
                    null,
                    error,
                    "workspace_selection_failed",
                    "Could not select the Codex workspace",
                )
            }
            resolveWorkspace(transition.generation, resolution)
            ensureTransitionCurrent(transition)
        } catch (error: HostTransitionCleanupException) {
            cleanupFailure = error.cleanupFailure
            failLifecycle(
                transition.generation,
                null,
                error.cleanupFailure,
                error.code,
                error.fallback,
                error.isRecoverable,
            )
        } catch (error: HostTransitionAcquisitionException) {
            cleanupFailure = error.cleanupFailure
            failLifecycle(
                transition.generation,
                null,
                error.acquisitionFailure,
                "runtime_prepare_failed",
                "Could not prepare Codex",
            )
        } finally {
            finishTransition(transition, cleanupFailure)
        }
    }

    @Throws(Exception::class)
    public suspend fun close(): Unit {
        val claim = withContext(NonCancellable) {
            lifecycleLock.withLock {
                if (closeStarted) {
                    HostCloseClaim(owner = false, previous = null)
                } else {
                    closeStarted = true
                    generation += 1
                    HostCloseClaim(
                        owner = true,
                        previous = agent,
                        transitions = activeTransitions.toList(),
                    ).also {
                        agent = null
                        mutableState.value = CodexHostState.Closed
                    }
                }
            }
        }
        if (!claim.owner) {
            val existingFailure = withContext(NonCancellable) { closeCompletion.await() }
            existingFailure?.let {
                throw it.asCodexOperationException("host_close_failed", "Could not close Codex", false)
            }
            currentCoroutineContext().ensureActive()
            return
        }
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                completeCleanup(
                    listOf(
                        { claim.previous?.close() },
                        { scope.cancel() },
                        {
                            completeCleanup(
                                claim.transitions.map { completion ->
                                    { completion.await()?.let { throw it } }
                                },
                            )
                        },
                    ),
                )
            } catch (error: Throwable) {
                failure = error
            } finally {
                closeCompletion.complete(failure)
            }
        }
        failure?.let { throw it.asCodexOperationException("host_close_failed", "Could not close Codex", false) }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun restore(operation: Long) {
        val resolution = try {
            platform.workspaceStore.restore()
        } catch (error: Throwable) {
            failLifecycle(operation, null, error, "workspace_restore_failed", "Could not restore the Codex workspace")
        }
        resolveWorkspace(operation, resolution)
    }

    private suspend fun resolveWorkspace(
        operation: Long,
        resolution: CodexWorkspaceResolution,
    ) {
        when (resolution) {
            is CodexWorkspaceResolution.Available -> prepare(operation, resolution.workspace)
            is CodexWorkspaceResolution.SelectionRequired -> lifecycleLock.withLock {
                if (operation == generation && mutableState.value != CodexHostState.Closed) {
                    mutableState.value = CodexHostState.WorkspaceRequired(resolution)
                }
            }
        }
    }

    private suspend fun prepare(operation: Long, workspace: CodexWorkspace) {
        val mayPrepare = lifecycleLock.withLock {
            if (operation != generation || mutableState.value == CodexHostState.Closed) {
                false
            } else {
                mutableState.value = CodexHostState.Preparing(workspace)
                true
            }
        }
        if (!mayPrepare) return

        val prepared = try {
            platform.prepare(workspace)
        } catch (error: Throwable) {
            failLifecycle(operation, workspace, error, "runtime_prepare_failed", "Could not prepare Codex")
        }
        var client: CodexAgentClient? = null
        var acquiredAgent: CodexAgent? = null
        try {
            val acquiredClient = prepared.createClient(clientInfo, requestTimeoutMillis)
            client = acquiredClient
            acquiredAgent = CodexAgent(
                workspace = workspace,
                workingDirectory = prepared.workspacePath,
                features = prepared.features,
                client = acquiredClient,
                parentScope = scope,
                authorizationBrowser = platform.authorizationBrowser,
            )
            acquiredAgent.start()
        } catch (error: Throwable) {
            var cleanupFailure: Throwable? = null
            withContext(NonCancellable) {
                try {
                    acquiredAgent?.close() ?: client?.closeSuspendingAction()
                } catch (cleanupError: Throwable) {
                    cleanupFailure = cleanupError
                }
            }
            cleanupFailure?.let { throw HostTransitionAcquisitionException(error, it) }
            failLifecycle(operation, workspace, error, "runtime_prepare_failed", "Could not prepare Codex")
        }
        val acquired = checkNotNull(acquiredAgent)

        val accepted = withContext(NonCancellable) {
            lifecycleLock.withLock {
                if (operation != generation || mutableState.value == CodexHostState.Closed) {
                    false
                } else {
                    agent = acquired
                    mutableState.value = CodexHostState.Ready(acquired)
                    true
                }
            }
        }
        if (!accepted) withContext(NonCancellable) {
            try {
                acquired.close()
            } catch (error: Throwable) {
                throw HostTransitionCleanupException(
                    error,
                    "host_close_failed",
                    "Could not close superseded Codex resources",
                    false,
                )
            }
        }
        currentCoroutineContext().ensureActive()
    }

    private fun beginTransition(state: CodexHostState): HostTransition {
        generation += 1
        val previous = agent
        agent = null
        mutableState.value = state
        val completion = CompletableDeferred<Throwable?>()
        activeTransitions += completion
        return HostTransition(generation, previous, completion)
    }

    private suspend fun ensureTransitionCurrent(transition: HostTransition) {
        val current = lifecycleLock.withLock {
            transition.generation == generation && mutableState.value != CodexHostState.Closed
        }
        if (!current) throw CancellationException("Codex host operation was superseded")
    }

    private suspend fun finishTransition(
        transition: HostTransition,
        cleanupFailure: Throwable?,
    ) = withContext(NonCancellable) {
        lifecycleLock.withLock {
            activeTransitions -= transition.completion
            transition.completion.complete(cleanupFailure)
        }
    }

    private suspend fun closePrevious(transition: HostTransition, workspace: CodexWorkspace?) {
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                transition.previous?.close()
            } catch (error: Throwable) {
                failure = error
            }
        }
        failure?.let { error ->
            throw HostTransitionCleanupException(
                error,
                "runtime_prepare_failed",
                "Could not replace Codex",
                true,
            )
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            failLifecycle(transition.generation, workspace, error, "runtime_prepare_failed", "Could not replace Codex")
        }
    }

    private suspend fun failLifecycle(
        operation: Long,
        workspace: CodexWorkspace?,
        error: Throwable,
        code: String,
        fallback: String,
        isRecoverable: Boolean = true,
    ): Nothing {
        val failure = if (error is CancellationException) {
            codexFailure(code, fallback, fallback, isRecoverable)
        } else {
            error.asCodexOperationException(code, fallback, isRecoverable).failure
        }
        withContext(NonCancellable) {
            lifecycleLock.withLock {
                if (operation == generation && mutableState.value != CodexHostState.Closed) {
                    mutableState.value = CodexHostState.Failed(
                        workspace = workspace,
                        failure = failure,
                    )
                }
            }
        }
        if (error is CancellationException) throw error
        throw CodexOperationException(failure, error)
    }

    private data class HostTransition(
        val generation: Long,
        val previous: CodexAgent?,
        val completion: CompletableDeferred<Throwable?>,
    )

    private data class StartTransition(
        val transition: HostTransition,
        val workspace: CodexWorkspace?,
    )

    private data class HostCloseClaim(
        val owner: Boolean,
        val previous: CodexAgent?,
        val transitions: List<CompletableDeferred<Throwable?>> = emptyList(),
    )

    private class HostTransitionCleanupException(
        val cleanupFailure: Throwable,
        val code: String,
        val fallback: String,
        val isRecoverable: Boolean,
    ) : Exception(cleanupFailure)

    private class HostTransitionAcquisitionException(
        val acquisitionFailure: Throwable,
        val cleanupFailure: Throwable,
    ) : Exception(acquisitionFailure)

}
