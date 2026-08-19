package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.Path

internal fun CodexAgentClient(
    runtimeFactory: CodexRuntimeFactory,
    requestTimeoutMillis: Long = 20_000,
    pluginCacheDirectory: Path? = null,
    shellTranscriptDirectory: Path? = null,
    turnInputMetadataDirectory: Path? = null,
    toolProvider: CodexToolProvider? = null,
    pluginRequestTimeoutMillis: Long = 120_000,
    emptyPluginCatalogRetryDelaysMillis: List<Long> = EMPTY_PLUGIN_CATALOG_RETRY_DELAYS_MILLIS,
    clientName: String = "codex_agent",
    clientTitle: String = "Codex Agent",
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    fileSystem: FileSystem? = null,
): CodexAgentClient = CodexAgentClient(
    runtimeFactory = runtimeFactory,
    clientInfo = CodexClientInfo(clientName, clientTitle, "codex-agent-tests"),
    requestTimeoutMillis = requestTimeoutMillis,
    pluginCacheDirectory = pluginCacheDirectory,
    shellTranscriptDirectory = shellTranscriptDirectory,
    turnInputMetadataDirectory = turnInputMetadataDirectory,
    toolProvider = toolProvider,
    pluginRequestTimeoutMillis = pluginRequestTimeoutMillis,
    emptyPluginCatalogRetryDelaysMillis = emptyPluginCatalogRetryDelaysMillis,
    coroutineDispatcher = coroutineDispatcher,
    fileSystem = fileSystem?.asAgentFileStore(),
)

internal suspend fun CodexAgentClient.openConversation(
    conversationId: ConversationId? = null,
    settings: AgentConversationSettings = AgentConversationSettings(),
): ConversationId = openConversation(
    conversationId,
    settings,
    "/workspace",
    CodexRuntimeFeature.entries.toSet(),
)

internal suspend fun CodexAgentClient.sendTurn(
    conversationId: ConversationId,
    request: AgentTurnRequest,
): Unit {
    stateLock.withLock { openedConversations += conversationId }
    sendTurn(conversationId, request, "/workspace")
}
