package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentConversation
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentHookCatalog
import io.github.ciurlaro.codexmobile.agent.AgentHookActivity
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentMessage
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.agent.AgentPluginDetail
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentConversationSettings
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkillChunk
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.ConversationId
import okio.Path
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.KSerializer

internal class CodexAgentClient(
    runtimeFactory: CodexRuntimeFactory,
    internal val clientInfo: CodexClientInfo,
    internal val requestTimeoutMillis: Long = 20_000,
    internal val pluginCacheDirectory: Path? = null,
    shellTranscriptDirectory: Path? = null,
    turnInputMetadataDirectory: Path? = null,
    internal val toolProvider: CodexToolProvider? = null,
    internal val pluginRequestTimeoutMillis: Long = 120_000,
    internal val emptyPluginCatalogRetryDelaysMillis: List<Long> = EMPTY_PLUGIN_CATALOG_RETRY_DELAYS_MILLIS,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    fileSystem: AgentFileStore? = null,
) : AutoCloseable {
    internal val coroutineDispatcher = coroutineDispatcher
    internal val fileSystem = fileSystem
    internal val builtInToolDefinitions = toolProvider?.definitions().orEmpty().toList()
    internal val builtInToolsByName = builtInToolDefinitions.associateBy(BuiltInToolDefinition::name)
    internal val scope = CoroutineScope(SupervisorJob() + coroutineDispatcher)
    internal val eventsChannel = BoundedEventBroadcast<AgentEvent>(
        capacity = EVENT_BUFFER_SIZE,
        observerOverflow = {
            AgentEvent.Failure(
                conversationId = null,
                code = "event_observer_overflow",
                message = "The event observer was closed because its 64-event mailbox overflowed.",
                isRecoverable = true,
            )
        },
        backlogOverflow = {
            AgentEvent.Failure(
                conversationId = null,
                code = "event_backlog_overflow",
                message = "The event backlog overflowed while no observers were registered.",
                isRecoverable = true,
            )
        },
    )
    internal val authMutex = Mutex()
    internal val loginStateLock = Mutex()
    internal val stateLock = Mutex()
    internal val conversationOwnershipLock = Mutex()
    internal val cancelledLoginIds = mutableSetOf<String>()
    internal val pendingApprovalRequests = mutableMapOf<String, PendingApproval>()
    internal val pendingBuiltInApprovals = mutableMapOf<String, PendingBuiltInApproval>()
    internal val pendingElicitationRequests = mutableMapOf<String, PendingElicitation>()
    internal val workItems = mutableMapOf<String, Pair<ConversationId, AgentWorkActivity>>()
    internal val userShellItems = mutableSetOf<String>()
    internal val commentaryItems = mutableSetOf<String>()
    internal val knownSkillPaths = mutableSetOf<String>()
    internal val openedConversations = mutableSetOf<ConversationId>()
    internal val conversationOwners = mutableMapOf<ConversationId, Any>()
    internal val conversationRuntimeSettings = mutableMapOf<ConversationId, ConversationRuntimeSettings>()
    internal val shellTranscriptStore = ShellTranscriptStore(shellTranscriptDirectory, fileSystem)
    internal val turnInputMetadataStore = TurnInputMetadataStore(turnInputMetadataDirectory, fileSystem)
    internal val builtInPluginEnabled = mutableMapOf<String, Boolean>().apply {
        builtInToolDefinitions.filter(BuiltInToolDefinition::requiresEnabledPlugin)
            .map(BuiltInToolDefinition::pluginId).distinct().forEach { put(it, true) }
    }
    internal val builtInToolGate = Mutex()
    internal val pluginRequestMutex = Mutex()
    private val extensionMutationGate = Mutex()
    internal var builtInEnablementLoaded = false
    internal val turnStateLock = Mutex()
    internal val activeTurns = mutableMapOf<ConversationId, String>()
    internal val startingTurns = mutableSetOf<ConversationId>()
    internal val pendingTerminalsDuringStart = mutableMapOf<Pair<ConversationId, String>, AgentEvent>()
    internal val recentTerminalTurnIds = mutableMapOf<ConversationId, ArrayDeque<String>>()
    internal val shellStartupCompletions = mutableMapOf<ConversationId, CompletableDeferred<Boolean>>()
    internal val cancellingTurns = mutableSetOf<ConversationId>()
    internal val cancelledTurns = mutableMapOf<ConversationId, String>()
    internal var authenticated = false
    internal var closed = false
    internal val connection = AppServerConnection(
        runtimeFactory = runtimeFactory,
        initializeParams = InitializeParams(
            clientInfo = ClientInfo(clientInfo.name, clientInfo.version, clientInfo.title),
            capabilities = InitializeCapabilities(
                experimentalApi = true,
                mcpServerOpenaiFormElicitation = false,
            ),
        ),
        requestTimeoutMillis = requestTimeoutMillis,
    )

    init {
        require(pluginRequestTimeoutMillis > 0) { "Plugin request timeout must be positive" }
        require(emptyPluginCatalogRetryDelaysMillis.all { it >= 0 }) { "Plugin retry delays must not be negative" }
        require(
            fileSystem != null || listOf(
                pluginCacheDirectory,
                shellTranscriptDirectory,
                turnInputMetadataDirectory,
            ).all { it == null },
        ) { "A file system is required when persistent agent directories are configured" }
    }

    init {
        scope.launch {
            try {
                connection.events.collect(::handleConnectionEvent)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed) handleConnectionFailure("event_stream", error.visibleMessage())
            }
        }
    }

    @Volatile
    internal var loginId: String? = null

    internal var loginStarting = false
    internal var loginCompletedDuringStart: LoginCompletion? = null

    internal val events: Flow<AgentEvent> = eventsChannel.events

    internal suspend fun start(): Unit {
        connection.ensureStarted()
    }

    internal suspend fun authenticate(
        method: CodexAuthenticationMethod = CodexAuthenticationMethod.ChatGptBrowser,
    ) = authenticateAction(method)
    internal suspend fun cancelAuthentication() = cancelAuthenticationAction()
    internal suspend fun signOut() = signOutAction()
    internal suspend fun listModels(): List<AgentModel> = listModelsAction()
    internal suspend fun listSkills(
        workingDirectory: String,
        forceReload: Boolean = false,
    ): AgentSkillCatalog =
        listSkillsAction(workingDirectory, forceReload)
    internal suspend fun readSkill(path: String, offset: Long): AgentSkillChunk = readSkillAction(path, offset)
    internal suspend fun setSkillEnabled(path: String, isEnabled: Boolean) = mutateExtension {
        setSkillEnabledAction(path, isEnabled)
    }
    internal suspend fun listInstalledPlugins(
        workingDirectory: String? = null,
        forceRefresh: Boolean = false,
    ): AgentPluginCatalog =
        listInstalledPluginsAction(workingDirectory, forceRefresh)
    internal suspend fun listAvailablePlugins(
        workingDirectory: String? = null,
        forceRefresh: Boolean = false,
    ): AgentPluginCatalog =
        listAvailablePluginsAction(workingDirectory, forceRefresh)
    internal suspend fun listMergedPlugins(
        workingDirectory: String? = null,
        forceRefresh: Boolean = false,
    ): AgentPluginCatalog =
        listMergedPluginsAction(workingDirectory, forceRefresh)
    internal suspend fun requestAvailablePlugins(workingDirectory: String?, cache: Path?): AgentPluginCatalog = requestAvailablePluginsAction(workingDirectory, cache)
    internal suspend fun <P, R> listPlugins( workingDirectory: String?, method: AppServerMethod<P, R>, params: P, timeoutMillis: Long? = null, marketplaces: (R) -> List<PluginMarketplaceEntry>, loadErrors: (R) -> List<MarketplaceLoadErrorInfo>?, onResponse: (R) -> Unit = {}, ): AgentPluginCatalog = listPluginsAction(workingDirectory, method, params, timeoutMillis, marketplaces, loadErrors, onResponse)
    internal suspend fun <P, R> pluginRequest( method: AppServerMethod<P, R>, params: P, timeoutMillis: Long = pluginRequestTimeoutMillis, retryOnTimeout: Boolean = false, ): R = pluginRequestAction(method, params, timeoutMillis, retryOnTimeout)
    internal fun pluginCacheFile(workingDirectory: String?, kind: String): Path? = pluginCacheFileAction(workingDirectory, kind)
    internal fun <T> readPluginCache( file: Path?, serializer: KSerializer<T>, marketplaces: (T) -> List<PluginMarketplaceEntry>, loadErrors: (T) -> List<MarketplaceLoadErrorInfo>?, ): AgentPluginCatalog? = readPluginCacheAction(file, serializer, marketplaces, loadErrors)
    internal fun <T> writePluginCache(file: Path?, serializer: KSerializer<T>, response: T) = writePluginCacheAction(file, serializer, response)
    internal fun validateWorkingDirectory(workingDirectory: String?) = validateWorkingDirectoryAction(workingDirectory)
    internal fun clearPluginCache() = clearPluginCacheAction()
    internal suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail = readPluginAction(plugin)
    internal suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult = mutateExtension {
        installPluginAction(plugin)
    }
    internal suspend fun uninstallPlugin(plugin: AgentPluginReference): Unit = mutateExtension {
        uninstallPluginAction(plugin)
    }
    internal suspend fun setPluginEnabled(pluginId: String, isEnabled: Boolean) = mutateExtension {
        setPluginEnabledAction(pluginId, isEnabled)
    }
    internal suspend fun listConnectors(
        conversationId: ConversationId? = null,
        forceReload: Boolean = false,
    ): List<AgentConnector> =
        listConnectorsAction(conversationId, forceReload)
    internal suspend fun listMcpServers(): List<AgentMcpServer> = listMcpServersAction()
    internal suspend fun listHooks(workingDirectory: String): AgentHookCatalog = listHooksAction(workingDirectory)
    internal suspend fun setHookEnabled(key: String, isEnabled: Boolean) = mutateExtension {
        setHookEnabledAction(key, isEnabled)
    }
    internal suspend fun trustHook(key: String, currentHash: String) = mutateExtension {
        trustHookAction(key, currentHash)
    }
    internal suspend fun writeHookState(key: String, state: JsonObjectBuilder.() -> Unit) = writeHookStateAction(key, state)
    internal suspend fun startMcpOauth(
        serverName: String,
        conversationId: ConversationId? = null,
    ): String = startMcpOauthAction(serverName, conversationId)
    internal suspend fun listConversations(): List<AgentConversationSummary> = listConversationsAction()
    internal suspend fun readConversation(conversationId: ConversationId): AgentConversation = readConversationAction(conversationId)
    internal suspend fun renameConversation(conversationId: ConversationId, name: String) = renameConversationAction(conversationId, name)
    internal suspend fun deleteConversation(conversationId: ConversationId) = deleteConversationAction(conversationId)
    internal suspend fun detachConversation(
        conversationId: ConversationId,
        owner: Any = DEFAULT_CONVERSATION_OWNER,
    ): ConversationDetachResult = detachConversationAction(conversationId, owner)
    internal suspend fun openConversation(
        conversationId: ConversationId?,
        settings: AgentConversationSettings,
        workingDirectory: String,
        features: Set<CodexRuntimeFeature> = CodexRuntimeFeature.entries.toSet(),
        owner: Any = DEFAULT_CONVERSATION_OWNER,
    ): ConversationId = openConversationAction(conversationId, settings, workingDirectory, features, owner)
    internal suspend fun sendTurn(
        conversationId: ConversationId,
        request: AgentTurnRequest,
        workingDirectory: String,
    ) = sendTurnAction(conversationId, request, workingDirectory)
    internal suspend fun runShellCommand(conversationId: ConversationId, command: String) = runShellCommandAction(conversationId, command)
    internal suspend fun cancelTurn(conversationId: ConversationId) = cancelTurnAction(conversationId)
    internal suspend fun resolveApproval(requestId: String, decision: AgentApprovalDecision) = resolveApprovalAction(requestId, decision)
    internal suspend fun resolveElicitation(requestId: String, response: AgentElicitationResponse) =
        resolveElicitationAction(requestId, response)
    override fun close(): Unit = closeAction()
    internal suspend fun refreshBuiltInPluginEnablement(workingDirectory: String) = refreshBuiltInPluginEnablementAction(workingDirectory)
    internal fun applyBuiltInPluginEnablement(catalog: AgentPluginCatalog) = applyBuiltInPluginEnablementAction(catalog)
    internal suspend fun handleConnectionEvent(event: AppServerEvent) = handleConnectionEventAction(event)
    internal suspend fun handleServerRequest(request: ServerRequest, method: String) = handleServerRequestAction(request, method)
    internal suspend fun handleBuiltInToolCall(id: JsonElement, params: DynamicToolCallParams) =
        handleBuiltInToolCallAction(id, params)
    internal suspend fun continueBuiltInToolCall(pending: PendingBuiltInApproval) = continueBuiltInToolCallAction(pending)
    internal suspend fun executeBuiltInTool(pending: PendingBuiltInApproval) = executeBuiltInToolAction(pending)
    internal suspend fun validateBuiltInCall(pending: PendingBuiltInApproval) = validateBuiltInCallAction(pending)
    internal suspend fun cancelPendingBuiltInTools(conversationId: ConversationId, turnId: String?, message: String) = cancelPendingBuiltInToolsAction(conversationId, turnId, message)
    internal suspend fun respondBuiltInResult(id: JsonElement, result: BuiltInToolResult) = respondBuiltInResultAction(id, result)
    internal suspend fun handleElicitationRequest( id: JsonElement, params: McpServerElicitationRequestParams, ) = handleElicitationRequestAction(id, params)
    internal suspend fun handleUserInputRequest(id: JsonElement, params: ToolRequestUserInputParams) = handleUserInputRequestAction(id, params)
    internal suspend fun handleApprovalRequest( id: JsonElement, threadId: String, reason: String?, detailLines: List<String>, type: ApprovalType, ) = handleApprovalRequestAction(id, threadId, reason, detailLines, type)
    internal suspend fun rejectServerRequest(id: JsonElement, method: String) = rejectServerRequestAction(id, method)
    internal suspend fun respondServerError(id: JsonElement, code: Int, message: String) = respondServerErrorAction(id, code, message)
    internal suspend fun handleNotification(notification: ServerNotification) = handleNotificationAction(notification)
    internal suspend fun emitAuthenticated() = emitAuthenticatedAction()
    internal suspend fun applyLoginCompletion(completion: LoginCompletion) = applyLoginCompletionAction(completion)
    internal suspend fun finishTurn(
        conversationId: ConversationId,
        turnId: String,
        event: AgentEvent,
    ): Boolean = finishTurnAction(conversationId, turnId, event)
    internal suspend fun updateItemActivity( threadId: String, turnId: String, item: ThreadItem, started: Boolean, ) = updateItemActivityAction(threadId, turnId, item, started)
    internal suspend fun completeUserShellItem(threadId: String, turnId: String, item: ThreadItem) = completeUserShellItemAction(threadId, turnId, item)
    internal suspend fun handleConnectionFailure(code: String, message: String) = handleConnectionFailureAction(code, message)
    internal fun shellTranscriptMessages(transcript: ShellTranscript): List<AgentMessage> = shellTranscriptMessagesAction(transcript)
    internal suspend fun <P, R, T, U> requestAllPages( method: AppServerMethod<P, R>, params: (String?) -> P, data: (R) -> List<T>, nextCursor: (R) -> String?, transform: (T) -> U, ): List<U> = requestAllPagesAction(method, params, data, nextCursor, transform)
    internal fun pluginReadParams(plugin: AgentPluginReference) = pluginReadParamsAction(plugin)
    internal fun pluginInstallParams(plugin: AgentPluginReference) = pluginInstallParamsAction(plugin)
    internal fun pluginUninstallParams(plugin: AgentPluginReference) = pluginUninstallParamsAction(plugin)
    internal fun pluginEnablementParams(pluginId: String, enabled: Boolean) = pluginEnablementParamsAction(pluginId, enabled)
    internal fun approvalsReviewer(preset: AgentApprovalPreset) = approvalsReviewerAction(preset)
    internal fun elicitationResponse(response: AgentElicitationResponse): McpServerElicitationRequestResponse = elicitationResponseAction(response)

    private suspend fun <T> mutateExtension(block: suspend () -> T): T = extensionMutationGate.withLock {
        val hasActiveTurn = turnStateLock.withLock {
            startingTurns.isNotEmpty() || activeTurns.isNotEmpty() || cancellingTurns.isNotEmpty()
        }
        check(!hasActiveTurn) { "Extensions cannot be changed while a turn is active" }
        block()
    }

    internal suspend fun markTurnStarting(conversationId: ConversationId, clearCancellation: Boolean = false) {
        extensionMutationGate.withLock {
            stateLock.withLock {
                check(conversationId in openedConversations) { "Conversation is not open" }
                turnStateLock.withLock {
                    check(conversationId !in startingTurns && !activeTurns.containsKey(conversationId)) {
                        "A turn is already active for this conversation"
                    }
                    if (clearCancellation) cancelledTurns -= conversationId
                    startingTurns += conversationId
                }
            }
        }
    }

    internal suspend fun markShellTurnStarting(conversationId: ConversationId): CompletableDeferred<Boolean> {
        val completion = CompletableDeferred<Boolean>()
        extensionMutationGate.withLock {
            stateLock.withLock {
                check(conversationId in openedConversations) { "Conversation is not open" }
                turnStateLock.withLock {
                    check(conversationId !in startingTurns && !activeTurns.containsKey(conversationId)) {
                        "A turn is already active for this conversation"
                    }
                    startingTurns += conversationId
                    cancelledTurns -= conversationId
                    shellStartupCompletions[conversationId] = completion
                }
            }
        }
        return completion
    }

    internal fun AgentPluginCatalog.asStale(message: String): AgentPluginCatalog = copy(
        freshness = AgentCatalogFreshness.STALE_CACHE,
        errors = (errors + message).distinct(),
    )

    internal fun AgentPluginCatalog.withCachedFallback(
        cached: AgentPluginCatalog,
        message: String,
    ): AgentPluginCatalog = copy(
        plugins = (cached.plugins + plugins).associateBy { it.reference.id }.values.toList(),
        freshness = AgentCatalogFreshness.STALE_CACHE,
        errors = (cached.errors + errors + message).distinct(),
    )

    internal fun HookRunSummary.toAgentHookActivity() = AgentHookActivity(
        id = id,
        eventName = eventName.name,
        handlerType = handlerType.name,
        status = enumValueOf(status.name),
        statusMessage = statusMessage,
        details = entries.map(HookOutputEntry::text),
    )

    internal fun String.boundedShellTranscript(): String =
        if (length <= MAX_SHELL_TRANSCRIPT_CHARS) this
        else take(MAX_SHELL_TRANSCRIPT_CHARS) + TRUNCATION_MARKER

    internal fun AgentPluginReference.appServerPluginName(): String = if (marketplacePath == null) {
        requireNotNull(remotePluginId) { "Remote plugin $id is missing its catalog identifier; refresh the catalog" }
    } else {
        name
    }

    internal fun AppServerRpcException.forPlugin(plugin: AgentPluginReference): Throwable =
        if (detail.contains("Plugin not found", ignoreCase = true) ||
            detail.contains("status 404", ignoreCase = true) && detail.contains("/plugins/")) {
            AgentPluginUnavailableException(
                plugin.id,
                plugin.name.replace('-', ' ').replaceFirstChar(Char::uppercase),
            )
        } else {
            this
        }
}

private data object DEFAULT_CONVERSATION_OWNER

internal data class ConversationDetachResult(
    val owned: Boolean,
    val failure: Throwable? = null,
)
