package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.appserver.client.AppServerConnection
import io.github.codex_agent_labs.codexmobile.appserver.client.AppServerEvent
import io.github.codex_agent_labs.codexmobile.appserver.client.AppServerRpcException
import io.github.codex_agent_labs.codexmobile.appserver.client.AppServerTimeoutException
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.*
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntimeFactory
import io.github.codex_agent_labs.codexmobile.agent.AgentCatalogFreshness
import io.github.codex_agent_labs.codexmobile.agent.AgentCapability
import io.github.codex_agent_labs.codexmobile.agent.AgentConnector
import io.github.codex_agent_labs.codexmobile.agent.AgentCollaborationMode
import io.github.codex_agent_labs.codexmobile.agent.AgentConversation
import io.github.codex_agent_labs.codexmobile.agent.AgentConversationSummary
import io.github.codex_agent_labs.codexmobile.agent.AgentElicitationAction
import io.github.codex_agent_labs.codexmobile.agent.AgentElicitationResponse
import io.github.codex_agent_labs.codexmobile.agent.AgentEvent
import io.github.codex_agent_labs.codexmobile.agent.AgentFormValue
import io.github.codex_agent_labs.codexmobile.agent.AgentInvocation
import io.github.codex_agent_labs.codexmobile.agent.AgentHook
import io.github.codex_agent_labs.codexmobile.agent.AgentHookActivity
import io.github.codex_agent_labs.codexmobile.agent.AgentHookCatalog
import io.github.codex_agent_labs.codexmobile.agent.AgentHookRunStatus
import io.github.codex_agent_labs.codexmobile.agent.AgentHookTrustStatus
import io.github.codex_agent_labs.codexmobile.agent.AgentMcpServer
import io.github.codex_agent_labs.codexmobile.agent.AgentMessage
import io.github.codex_agent_labs.codexmobile.agent.AgentMessageRole
import io.github.codex_agent_labs.codexmobile.agent.AgentModel
import io.github.codex_agent_labs.codexmobile.agent.AgentApprovalDecision
import io.github.codex_agent_labs.codexmobile.agent.AgentApprovalPreset
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginAuthPolicy
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginCatalog
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginDetail
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginInstallResult
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginReference
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginUnavailableException
import io.github.codex_agent_labs.codexmobile.agent.AgentPlanProgress
import io.github.codex_agent_labs.codexmobile.agent.AgentPlanStep
import io.github.codex_agent_labs.codexmobile.agent.AgentPlanStepStatus
import io.github.codex_agent_labs.codexmobile.agent.AgentConversationSettings
import io.github.codex_agent_labs.codexmobile.agent.AgentServiceTier
import io.github.codex_agent_labs.codexmobile.agent.AgentSkillCatalog
import io.github.codex_agent_labs.codexmobile.agent.AgentSkillChunk
import io.github.codex_agent_labs.codexmobile.agent.AgentTurnRequest
import io.github.codex_agent_labs.codexmobile.agent.AgentWorkActivity
import io.github.codex_agent_labs.codexmobile.agent.ConversationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.KSerializer


internal suspend fun CodexAgentClient.listInstalledPluginsAction(
    workingDirectory: String?,
    forceRefresh: Boolean,
): AgentPluginCatalog {
    validateWorkingDirectory(workingDirectory)
    val cache = pluginCacheFile(workingDirectory, "installed")
    val cached = readPluginCache(
        cache,
        PluginInstalledResponse.serializer(),
        PluginInstalledResponse::marketplaces,
        PluginInstalledResponse::marketplaceLoadErrors,
    )
    if (!forceRefresh && cached != null) {
        return cached
    }
    val catalog = runCatching {
        listPlugins(
            workingDirectory,
            AppServerClientMethods.PluginInstalled,
            PluginInstalledParams(cwds = workingDirectory?.let(::listOf)),
            timeoutMillis = pluginRequestTimeoutMillis,
            marketplaces = PluginInstalledResponse::marketplaces,
            loadErrors = PluginInstalledResponse::marketplaceLoadErrors,
        ) { writePluginCache(cache, PluginInstalledResponse.serializer(), it) }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        return cached?.asStale(error.visibleMessage()) ?: throw error
    }.let { live ->
        if (live.errors.isNotEmpty() && cached?.plugins?.isNotEmpty() == true) {
            live.withCachedFallback(
                cached,
                "Installed plugins could not be fully verified; showing saved plugins.",
            )
        } else {
            live
        }
    }
    return catalog
}

internal suspend fun CodexAgentClient.listAvailablePluginsAction(
    workingDirectory: String?,
    forceRefresh: Boolean,
): AgentPluginCatalog {
    validateWorkingDirectory(workingDirectory)
    val cache = pluginCacheFile(workingDirectory, "available")
    val cached = readPluginCache(
        cache,
        PluginListResponse.serializer(),
        PluginListResponse::marketplaces,
        PluginListResponse::marketplaceLoadErrors,
    )
    if (!forceRefresh && cached != null) return cached
    return runCatching {
        var catalog = requestAvailablePlugins(workingDirectory, cache)
        for (retryDelay in emptyPluginCatalogRetryDelaysMillis) {
            if (catalog.plugins.isNotEmpty() || catalog.errors.isNotEmpty()) break
            delay(retryDelay)
            catalog = requestAvailablePlugins(workingDirectory, cache)
        }
        when {
            catalog.errors.isNotEmpty() && cached?.plugins?.isNotEmpty() == true -> {
                catalog.withCachedFallback(cached, "Some marketplaces could not be refreshed; showing saved plugins.")
            }
            catalog.plugins.isNotEmpty() || catalog.errors.isNotEmpty() -> catalog
            cached?.plugins?.isNotEmpty() == true -> cached.asStale(
                "The plugin marketplace is not ready; showing saved plugins.",
            )
            else -> catalog.copy(errors = listOf("The plugin marketplace is not ready yet. Retry in a moment."))
        }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        cached?.asStale(error.visibleMessage()) ?: throw error
    }
}

internal suspend fun CodexAgentClient.listMergedPluginsAction(
    workingDirectory: String?,
    forceRefresh: Boolean,
): AgentPluginCatalog = mergePluginCatalogs(
    available = listAvailablePluginsAction(workingDirectory, forceRefresh),
    installed = listInstalledPluginsAction(workingDirectory, forceRefresh),
)

internal fun mergePluginCatalogs(
    available: AgentPluginCatalog,
    installed: AgentPluginCatalog,
): AgentPluginCatalog {
    val installedById = installed.plugins.associateBy { it.reference.id }
    val availableIds = available.plugins.mapTo(mutableSetOf()) { it.reference.id }
    val plugins = available.plugins.map { candidate ->
        installedById[candidate.reference.id]?.let { installedPlugin ->
            candidate.copy(
                isInstalled = installedPlugin.isInstalled,
                isEnabled = installedPlugin.isEnabled,
                installPolicy = installedPlugin.installPolicy,
                authPolicy = installedPlugin.authPolicy,
            )
        } ?: candidate
    } + installed.plugins.filterNot { it.reference.id in availableIds }
    return AgentPluginCatalog(
        plugins = plugins,
        errors = (available.errors + installed.errors).distinct(),
        freshness = leastFresh(available.freshness, installed.freshness),
    )
}

private fun leastFresh(
    first: AgentCatalogFreshness,
    second: AgentCatalogFreshness,
): AgentCatalogFreshness = when {
    first == AgentCatalogFreshness.STALE_CACHE || second == AgentCatalogFreshness.STALE_CACHE ->
        AgentCatalogFreshness.STALE_CACHE
    first == AgentCatalogFreshness.FRESH_CACHE || second == AgentCatalogFreshness.FRESH_CACHE ->
        AgentCatalogFreshness.FRESH_CACHE
    else -> AgentCatalogFreshness.LIVE
}

internal suspend fun CodexAgentClient.requestAvailablePluginsAction(workingDirectory: String?, cache: Path?): AgentPluginCatalog =
    listPlugins(
        workingDirectory,
        AppServerClientMethods.PluginList,
        PluginListParams(cwds = workingDirectory?.let(::listOf)),
        pluginRequestTimeoutMillis,
        marketplaces = PluginListResponse::marketplaces,
        loadErrors = PluginListResponse::marketplaceLoadErrors,
    ) { writePluginCache(cache, PluginListResponse.serializer(), it) }

internal suspend fun <P, R> CodexAgentClient.listPluginsAction(
    workingDirectory: String?,
    method: AppServerMethod<P, R>,
    params: P,
    timeoutMillis: Long? = null,
    marketplaces: (R) -> List<PluginMarketplaceEntry>,
    loadErrors: (R) -> List<MarketplaceLoadErrorInfo>?,
    onResponse: (R) -> Unit = {},
): AgentPluginCatalog {
    validateWorkingDirectory(workingDirectory)
    val result = pluginRequest(method, params, timeoutMillis ?: pluginRequestTimeoutMillis)
    val errors = loadErrors(result).orEmpty().map { it.message }.distinct()
    val catalog = AgentPluginCatalog(parsePluginMarketplaces(marketplaces(result)), errors)
    if (toolProvider != null) {
        builtInToolGate.withLock {
            applyBuiltInPluginEnablement(catalog)
            builtInEnablementLoaded = true
        }
    }
    if (catalog.plugins.isNotEmpty() && catalog.errors.isEmpty()) runCatching { onResponse(result) }
    return catalog
}

internal suspend fun <P, R> CodexAgentClient.pluginRequestAction(
    method: AppServerMethod<P, R>,
    params: P,
    timeoutMillis: Long = pluginRequestTimeoutMillis,
    retryOnTimeout: Boolean = false,
): R = pluginRequestMutex.withLock {
    try {
        connection.request(method, params, timeoutMillis)
    } catch (error: AppServerTimeoutException) {
        if (!retryOnTimeout) throw error
        connection.request(method, params, timeoutMillis)
    }
}

internal fun CodexAgentClient.pluginCacheFileAction(workingDirectory: String?, kind: String): Path? {
    val directory = pluginCacheDirectory ?: return null
    val key = "${clientInfo.name}\u0000${clientInfo.title}\u0000${clientInfo.version}\u0000${workingDirectory.orEmpty()}\u0000$kind".sha256Hex()
    return directory / "$key.json"
}

internal fun <T> CodexAgentClient.readPluginCacheAction(
    file: Path?,
    serializer: KSerializer<T>,
    marketplaces: (T) -> List<PluginMarketplaceEntry>,
    loadErrors: (T) -> List<MarketplaceLoadErrorInfo>?,
): AgentPluginCatalog? {
    if (file == null) return null
    val fileSystem = requireFileSystem()
    if (!file.isRegularFile(fileSystem)) return null
    return runCatching {
        val result = PROTOCOL_JSON.decodeFromString(serializer, file.readUtf8(fileSystem))
        val writtenAt = file.cacheTimestamp().takeIf { it.isRegularFile(fileSystem) }
            ?.readUtf8(fileSystem)?.toLongOrNull()
        val freshness = if (writtenAt != null && currentEpochMillis() - writtenAt <= CATALOG_CACHE_TTL_MILLIS) {
            AgentCatalogFreshness.FRESH_CACHE
        } else {
            AgentCatalogFreshness.STALE_CACHE
        }
        AgentPluginCatalog(
            plugins = parsePluginMarketplaces(marketplaces(result)),
            errors = loadErrors(result).orEmpty().map { it.message }.distinct(),
            freshness = freshness,
        )
    }.getOrNull()
}

internal fun <T> CodexAgentClient.writePluginCacheAction(file: Path?, serializer: KSerializer<T>, response: T) {
    if (file == null) return
    val fileSystem = requireFileSystem()
    file.writeUtf8Atomically(fileSystem, PROTOCOL_JSON.encodeToString(serializer, response))
    file.cacheTimestamp().writeUtf8Atomically(fileSystem, currentEpochMillis().toString())
}

internal fun CodexAgentClient.validateWorkingDirectoryAction(workingDirectory: String?) {
    require(workingDirectory == null || workingDirectory.isAbsoluteHostPath()) {
        "Working directory must be absolute"
    }
}

internal fun CodexAgentClient.clearPluginCacheAction() {
    val directory = pluginCacheDirectory ?: return
    val fileSystem = requireFileSystem()
    fileSystem.clearDirectory(directory)
}

private fun Path.cacheTimestamp(): Path = checkNotNull(parent) / "$name.timestamp"
