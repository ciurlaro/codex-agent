package io.github.ciurlaro.codexmobile.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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

public class CodexAgent internal constructor(
    public val workspace: CodexWorkspace,
    private val workingDirectory: String,
    features: Set<CodexRuntimeFeature>,
    private val client: CodexAgentClient,
    parentScope: CoroutineScope,
    authorizationBrowser: CodexAuthorizationBrowser,
) {
    private val lock = Mutex()
    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )
    private val authentication = AuthenticationController(client, scope, authorizationBrowser)
    private val interactions = InteractionController(client, scope, authorizationBrowser)
    private val integrationAuthorization =
        IntegrationAuthorizationController(client, scope, authorizationBrowser)
    private val mutableActiveConversation = MutableStateFlow<CodexConversation?>(null)
    private val conversationReleases = mutableMapOf<CodexConversation, CompletableDeferred<Throwable?>>()
    private var conversationGeneration = 0L
    private var signingOut = false
    private var closed = false

    public val features: Set<CodexRuntimeFeature> = features.toSet()
    public val authenticationState: StateFlow<AgentAuthenticationState> = authentication.state
    public val interactionState: StateFlow<AgentInteractionState> = interactions.state
    public val integrationAuthorizationState: StateFlow<AgentIntegrationAuthorizationState> =
        integrationAuthorization.state
    public val activeConversation: StateFlow<CodexConversation?> =
        mutableActiveConversation.asStateFlow()

    internal suspend fun start(): Unit = client.start()

    @Throws(Exception::class)
    public suspend fun authenticate(
        method: CodexAuthenticationMethod = CodexAuthenticationMethod.ChatGptBrowser,
    ): Unit {
        requireOpen()
        authentication.authenticate(method)
    }

    @Throws(Exception::class)
    public suspend fun cancelAuthentication(): Unit {
        requireOpen()
        authentication.cancel()
    }

    @Throws(Exception::class)
    public suspend fun signOut(): Unit {
        val conversation = lock.withLock {
            checkOpenLocked()
            check(!signingOut) { "Sign-out is already in progress" }
            signingOut = true
            conversationGeneration += 1
            mutableActiveConversation.value.also { mutableActiveConversation.value = null }
        }
        var failure: Throwable? = null
        try {
            withContext(NonCancellable) {
                try {
                    completeCleanup(
                        listOf(
                            { conversation?.let { releaseConversation(it) } },
                            { authentication.signOut() },
                        ),
                    )
                } catch (error: Throwable) {
                    failure = error
                }
            }
            failure?.let {
                throw it.asCodexOperationException("authentication_failed", "Could not sign out")
            }
            currentCoroutineContext().ensureActive()
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (!closed) signingOut = false
                }
            }
        }
    }

    @Throws(Exception::class)
    public suspend fun resolveApproval(
        approval: AgentPendingApproval,
        decision: AgentApprovalDecision,
    ): Unit {
        requireOpen()
        interactions.resolveApproval(approval.requestId, decision)
    }

    @Throws(Exception::class)
    public suspend fun resolveElicitation(
        elicitation: AgentPendingElicitation,
        response: AgentElicitationResponse,
    ): Unit {
        requireOpen()
        interactions.resolveElicitation(elicitation.requestId, response)
    }

    @Throws(Exception::class)
    public suspend fun openElicitationUrl(elicitation: AgentPendingElicitation): Unit {
        requireOpen()
        interactions.openUrl(elicitation.requestId)
    }

    @Throws(Exception::class)
    public suspend fun listConversations(): List<AgentConversationSummary> {
        requireOpen()
        return codexOperation("conversation_list_failed", "Could not list conversations") {
            client.listConversations()
        }
    }

    @Throws(Exception::class)
    public suspend fun readConversation(conversationId: ConversationId): AgentConversation {
        requireOpen()
        return codexOperation("conversation_read_failed", "Could not read conversation") {
            client.readConversation(conversationId)
        }
    }

    @Throws(Exception::class)
    public suspend fun renameConversation(conversationId: ConversationId, name: String): Unit {
        requireOpen()
        require(name.isNotBlank()) { "Conversation name must not be blank" }
        codexOperation("conversation_rename_failed", "Could not rename conversation") {
            client.renameConversation(conversationId, name)
        }
    }

    @Throws(Exception::class)
    public suspend fun deleteConversation(conversationId: ConversationId): Unit {
        requireOpen()
        mutableActiveConversation.value
            ?.takeIf { it.state.value.conversationId == conversationId }
            ?.close()
        requireOpen()
        codexOperation("conversation_delete_failed", "Could not delete conversation") {
            client.deleteConversation(conversationId)
        }
    }

    @Throws(Exception::class)
    public suspend fun openConversation(
        conversationId: ConversationId? = null,
        settings: AgentConversationSettings = AgentConversationSettings(),
    ): CodexConversation {
        val conversation = CodexConversation(
            client = client,
            scope = scope,
            workingDirectory = workingDirectory,
            features = features,
            onClose = ::closeConversation,
        )
        val transition = lock.withLock {
            checkOpenLocked()
            check(!signingOut) { "Sign-out is in progress" }
            conversationGeneration += 1
            val previous = mutableActiveConversation.value
            mutableActiveConversation.value = conversation
            ConversationTransition(conversationGeneration, previous)
        }

        var openedId: ConversationId? = null
        try {
            withContext(NonCancellable) {
                transition.previous?.let { releaseConversation(it) }
            }
            currentCoroutineContext().ensureActive()
            val mayOpen = lock.withLock {
                !closed &&
                    mutableActiveConversation.value === conversation &&
                    conversationGeneration == transition.generation &&
                    !signingOut
            }
            if (!mayOpen) throw CancellationException("Conversation opening was superseded")
            openedId = conversation.open(conversationId, settings)
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                lock.withLock {
                    if (mutableActiveConversation.value === conversation) {
                        mutableActiveConversation.value = null
                    }
                }
                try {
                    releaseConversation(conversation, openedId ?: conversation.state.value.conversationId)
                } catch (_: Throwable) {
                    // Preserve the failure that prevented the conversation from opening.
                }
            }
            if (error is CancellationException || error is CodexOperationException) throw error
            throw error.asCodexOperationException("open_failed", "Could not open conversation")
        }

        val accepted = withContext(NonCancellable) {
            lock.withLock {
                !closed &&
                    mutableActiveConversation.value === conversation &&
                    conversationGeneration == transition.generation &&
                    !signingOut
            }
        }
        if (!accepted) {
            withContext(NonCancellable) { releaseConversation(conversation, openedId) }
            throw CancellationException("Conversation opening was superseded")
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                lock.withLock {
                    if (mutableActiveConversation.value === conversation) {
                        conversationGeneration += 1
                        mutableActiveConversation.value = null
                    }
                }
                releaseConversation(conversation, openedId)
            }
            throw error
        }
        return conversation
    }

    @Throws(Exception::class)
    public suspend fun listModels(): List<AgentModel> {
        requireOpen()
        return codexOperation("model_list_failed", "Could not list models") { client.listModels() }
    }

    @Throws(Exception::class)
    public suspend fun listSkills(forceReload: Boolean = false): AgentSkillCatalog {
        requireOpen()
        requireFeature(CodexRuntimeFeature.SKILLS)
        return codexOperation("skill_list_failed", "Could not list skills") {
            client.listSkills(workingDirectory, forceReload)
        }
    }

    @Throws(Exception::class)
    public suspend fun readSkill(path: String, offset: Long = 0): AgentSkillChunk {
        requireOpen()
        requireFeature(CodexRuntimeFeature.SKILLS)
        require(path.isAbsoluteHostPath()) { "Skill path must be absolute" }
        require(offset >= 0) { "Offset must not be negative" }
        return codexOperation("skill_read_failed", "Could not read skill") { client.readSkill(path, offset) }
    }

    @Throws(Exception::class)
    public suspend fun setSkillEnabled(skill: AgentSkill, isEnabled: Boolean): Unit {
        requireOpen()
        requireFeature(CodexRuntimeFeature.SKILLS)
        require(skill.path.isAbsoluteHostPath()) { "Skill path must be absolute" }
        codexOperation("skill_update_failed", "Could not update skill") {
            client.setSkillEnabled(skill.path, isEnabled)
        }
    }

    @Throws(Exception::class)
    public suspend fun listHooks(): AgentHookCatalog {
        requireOpen()
        requireFeature(CodexRuntimeFeature.HOOKS)
        return codexOperation("hook_list_failed", "Could not list hooks") { client.listHooks(workingDirectory) }
    }

    @Throws(Exception::class)
    public suspend fun setHookEnabled(hook: AgentHook, isEnabled: Boolean): Unit {
        requireOpen()
        requireFeature(CodexRuntimeFeature.HOOKS)
        require(hook.key.isNotBlank()) { "Hook key must not be blank" }
        codexOperation("hook_update_failed", "Could not update hook") {
            client.setHookEnabled(hook.key, isEnabled)
        }
    }

    @Throws(Exception::class)
    public suspend fun trustHook(hook: AgentHook): Unit {
        requireOpen()
        requireFeature(CodexRuntimeFeature.HOOKS)
        require(hook.key.isNotBlank()) { "Hook key must not be blank" }
        require(hook.currentHash.isNotBlank()) { "Hook hash must not be blank" }
        codexOperation("hook_trust_failed", "Could not trust hook") {
            client.trustHook(hook.key, hook.currentHash)
        }
    }

    @Throws(Exception::class)
    public suspend fun listPlugins(forceRefresh: Boolean = false): AgentPluginCatalog {
        requireOpen()
        requireFeature(CodexRuntimeFeature.PLUGINS)
        return codexOperation("plugin_list_failed", "Could not list plugins") {
            client.listMergedPlugins(workingDirectory, forceRefresh)
        }
    }

    @Throws(Exception::class)
    public suspend fun readPlugin(plugin: AgentPluginReference): AgentPluginDetail {
        requireOpen()
        requireFeature(CodexRuntimeFeature.PLUGINS)
        requireValidPlugin(plugin)
        return codexOperation("plugin_read_failed", "Could not read plugin") { client.readPlugin(plugin) }
    }

    @Throws(Exception::class)
    public suspend fun installPlugin(plugin: AgentPluginReference): AgentPluginInstallResult {
        requireOpen()
        requireFeature(CodexRuntimeFeature.PLUGINS)
        requireValidPlugin(plugin)
        return codexOperation("plugin_install_failed", "Could not install plugin") { client.installPlugin(plugin) }
    }

    @Throws(Exception::class)
    public suspend fun uninstallPlugin(plugin: AgentPluginReference): Unit {
        requireOpen()
        requireFeature(CodexRuntimeFeature.PLUGINS)
        requireValidPlugin(plugin)
        codexOperation("plugin_uninstall_failed", "Could not uninstall plugin") {
            client.uninstallPlugin(plugin)
        }
    }

    @Throws(Exception::class)
    public suspend fun setPluginEnabled(plugin: AgentPluginSummary, isEnabled: Boolean): Unit {
        requireOpen()
        requireFeature(CodexRuntimeFeature.PLUGINS)
        require(plugin.reference.id.isNotBlank() && '.' !in plugin.reference.id) { "Invalid plugin ID" }
        codexOperation("plugin_update_failed", "Could not update plugin") {
            client.setPluginEnabled(plugin.reference.id, isEnabled)
        }
    }

    @Throws(Exception::class)
    public suspend fun listConnectors(forceReload: Boolean = false): List<AgentConnector> {
        requireOpen()
        requireFeature(CodexRuntimeFeature.CONNECTORS)
        return codexOperation("connector_list_failed", "Could not list connectors") {
            client.listConnectors(
                mutableActiveConversation.value?.state?.value?.conversationId,
                forceReload,
            )
        }
    }

    @Throws(Exception::class)
    public suspend fun listMcpServers(): List<AgentMcpServer> {
        requireOpen()
        requireFeature(CodexRuntimeFeature.MCP_SERVERS)
        return codexOperation("mcp_server_list_failed", "Could not list MCP servers") {
            client.listMcpServers()
        }
    }

    @Throws(Exception::class)
    public suspend fun authorizeConnector(connector: AgentConnector): Unit {
        requireOpen()
        requireFeature(CodexRuntimeFeature.CONNECTORS)
        integrationAuthorization.authorizeConnector(
            connector.id,
            mutableActiveConversation.value?.state?.value?.conversationId,
        )
    }

    @Throws(Exception::class)
    public suspend fun authorizeMcpServer(
        server: AgentMcpServer,
        conversationId: ConversationId? = null,
    ): Unit {
        requireOpen()
        requireFeature(CodexRuntimeFeature.MCP_SERVERS)
        integrationAuthorization.authorizeMcpServer(
            server.name,
            conversationId ?: mutableActiveConversation.value?.state?.value?.conversationId,
        )
    }

    @Throws(Exception::class)
    public suspend fun dismissIntegrationAuthorization(): Unit {
        requireOpen()
        integrationAuthorization.dismiss()
    }

    internal suspend fun close(): Unit {
        val conversation = lock.withLock {
            if (closed) return
            closed = true
            signingOut = false
            conversationGeneration += 1
            mutableActiveConversation.value.also { mutableActiveConversation.value = null }
        }
        withContext(NonCancellable) {
            completeCleanup(
                listOf(
                    { conversation?.let { releaseConversation(it) } },
                    { integrationAuthorization.close() },
                    { interactions.close() },
                    { authentication.close() },
                    { client.closeSuspendingAction() },
                    { scope.cancel() },
                ),
            )
        }
    }

    private suspend fun closeConversation(conversation: CodexConversation) {
        lock.withLock {
            if (!closed && mutableActiveConversation.value === conversation) {
                conversationGeneration += 1
                mutableActiveConversation.value = null
            }
        }
        releaseConversation(conversation)
    }

    private suspend fun releaseConversation(
        conversation: CodexConversation,
        knownConversationId: ConversationId? = conversation.state.value.conversationId,
    ) {
        val release = lock.withLock {
            conversationReleases[conversation]?.let { ReleaseClaim(it, false) }
                ?: CompletableDeferred<Throwable?>().let { completion ->
                    conversationReleases[conversation] = completion
                    ReleaseClaim(completion, true)
                }
        }
        if (!release.owner) {
            release.completion.await()?.let { throw it }
            knownConversationId?.let { detachConversation(conversation, it) }
            currentCoroutineContext().ensureActive()
            return
        }
        var failure: Throwable? = null
        withContext(NonCancellable) {
            try {
                val conversationId = knownConversationId ?: conversation.state.value.conversationId
                completeCleanup(
                    listOf(
                        { conversation.closeOwned() },
                        { conversationId?.let { detachConversation(conversation, it) } },
                    ),
                )
            } catch (error: Throwable) {
                failure = error
            } finally {
                release.completion.complete(failure)
            }
        }
        failure?.let { throw it }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun detachConversation(conversation: CodexConversation, conversationId: ConversationId) {
        val result = client.detachConversation(conversationId, conversation)
        var failure = result.failure
        if (result.owned) {
            try {
                interactions.detachConversation(conversationId)
            } catch (error: Throwable) {
                if (failure == null) failure = error
            }
        }
        failure?.let { throw it }
    }

    private suspend fun requireOpen(): Unit = lock.withLock { checkOpenLocked() }

    private fun checkOpenLocked() {
        check(!closed) { "Codex agent is closed" }
    }

    private fun requireFeature(feature: CodexRuntimeFeature) {
        if (feature !in features) {
            throw CodexOperationException(
                CodexFailure(
                    code = "unsupported_feature",
                    message = "Runtime feature ${feature.name} is not supported",
                    isRecoverable = false,
                ),
            )
        }
    }

    private fun requireValidPlugin(plugin: AgentPluginReference) {
        require(plugin.id.isNotBlank()) { "Plugin ID must not be blank" }
        require(plugin.name.isNotBlank()) { "Plugin name must not be blank" }
        require(plugin.marketplaceName.isNotBlank()) { "Plugin marketplace must not be blank" }
        require(plugin.marketplacePath != null || !plugin.remotePluginId.isNullOrBlank()) {
            "Remote plugin is missing its catalog identifier"
        }
    }

    private data class ConversationTransition(
        val generation: Long,
        val previous: CodexConversation?,
    )

    private data class ReleaseClaim(
        val completion: CompletableDeferred<Throwable?>,
        val owner: Boolean,
    )
}
