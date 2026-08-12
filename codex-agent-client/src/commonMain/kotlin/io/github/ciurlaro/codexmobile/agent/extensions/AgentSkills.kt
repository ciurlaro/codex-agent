package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.client.AppServerEvent
import io.github.ciurlaro.codexmobile.appserver.client.AppServerRpcException
import io.github.ciurlaro.codexmobile.appserver.client.AppServerTimeoutException
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.*
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeFactory
import io.github.ciurlaro.codexmobile.agent.AgentClient
import io.github.ciurlaro.codexmobile.agent.AgentCatalogFreshness
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentConnector
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentConversation
import io.github.ciurlaro.codexmobile.agent.AgentConversationSummary
import io.github.ciurlaro.codexmobile.agent.AgentElicitationAction
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentFormValue
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentHook
import io.github.ciurlaro.codexmobile.agent.AgentHookActivity
import io.github.ciurlaro.codexmobile.agent.AgentHookCatalog
import io.github.ciurlaro.codexmobile.agent.AgentHookRunStatus
import io.github.ciurlaro.codexmobile.agent.AgentHookTrustStatus
import io.github.ciurlaro.codexmobile.agent.AgentMcpServer
import io.github.ciurlaro.codexmobile.agent.AgentMessage
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentModel
import io.github.ciurlaro.codexmobile.agent.AgentApprovalDecision
import io.github.ciurlaro.codexmobile.agent.AgentApprovalPreset
import io.github.ciurlaro.codexmobile.agent.AgentPluginAuthPolicy
import io.github.ciurlaro.codexmobile.agent.AgentPluginCatalog
import io.github.ciurlaro.codexmobile.agent.AgentPluginDetail
import io.github.ciurlaro.codexmobile.agent.AgentPluginInstallResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginReference
import io.github.ciurlaro.codexmobile.agent.AgentPluginRemovalResult
import io.github.ciurlaro.codexmobile.agent.AgentPluginUnavailableException
import io.github.ciurlaro.codexmobile.agent.AgentPlanProgress
import io.github.ciurlaro.codexmobile.agent.AgentPlanStep
import io.github.ciurlaro.codexmobile.agent.AgentPlanStepStatus
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentServiceTier
import io.github.ciurlaro.codexmobile.agent.AgentSkillCatalog
import io.github.ciurlaro.codexmobile.agent.AgentSkillChunk
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.AgentWorkActivity
import io.github.ciurlaro.codexmobile.agent.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import okio.Path.Companion.toPath
import okio.buffer
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


internal suspend fun CodexAgentClient.listModelsAction(): List<AgentModel> =
    requestAllPages(
        AppServerClientMethods.ModelList,
        params = { ModelListParams(cursor = it) },
        data = ModelListResponse::data,
        nextCursor = ModelListResponse::nextCursor,
    ) { item ->
    val serviceTiers = item.serviceTiers.orEmpty().map { tier ->
            AgentServiceTier(tier.id, tier.name, tier.description)
        }.distinctBy(AgentServiceTier::id)
    AgentModel(
        id = item.model,
        displayName = item.displayName,
        description = item.description,
        supportedEfforts = item.supportedReasoningEfforts.map { it.reasoningEffort },
        defaultEffort = item.defaultReasoningEffort,
        isDefault = item.isDefault,
        serviceTiers = serviceTiers,
        defaultServiceTier = item.defaultServiceTier,
    )
}

internal suspend fun CodexAgentClient.listSkillsAction(
    workingDirectory: String,
    forceReload: Boolean,
): AgentSkillCatalog {
    require(workingDirectory.startsWith('/')) { "Working directory must be absolute" }
    val result = connection.request(
        AppServerClientMethods.SkillsList,
        SkillsListParams(cwds = listOf(workingDirectory), forceReload = forceReload),
    )
    val entries = result.data
    return AgentSkillCatalog(
        skills = entries.flatMap { it.skills }.map(::parseSkill)
            .distinctBy { it.path },
        errors = entries.flatMap { it.errors }.map { "${it.path}: ${it.message}" },
    ).also { catalog ->
        knownSkillPaths.clear()
        catalog.skills.mapTo(knownSkillPaths, io.github.ciurlaro.codexmobile.agent.AgentSkill::path)
    }
}

internal suspend fun CodexAgentClient.readSkillAction(path: String, offset: Long): AgentSkillChunk = withContext(coroutineDispatcher) {
    require(path in knownSkillPaths) { "Skill was not returned by skills/list" }
    require(offset >= 0) { "Offset must not be negative" }
    val fileSystem = requireFileSystem()
    val file = path.toPath()
    val total = fileSystem.metadataOrNull(file)?.takeIf { it.isRegularFile }?.size
    require(total != null) { "Skill source is not readable" }
    val source = fileSystem.source(file).buffer()
    try {
        require(offset <= total) { "Offset exceeds skill source size" }
        source.skip(offset)
        val count = minOf(SKILL_CHUNK_BYTES.toLong(), total - offset).toInt()
        val bytes = source.readByteArray(count.toLong())
        val complete = if (offset + count < total) completeUtf8Length(bytes, count) else count
        val next = (offset + complete).takeIf { it < total }
        AgentSkillChunk(
            content = bytes.decodeToString(0, complete, throwOnInvalidSequence = true),
            nextOffset = next,
            totalBytes = total,
        )
    } finally {
        source.close()
    }
}

internal suspend fun CodexAgentClient.setSkillEnabledAction(path: String, enabled: Boolean) {
    require(path.startsWith('/')) { "Skill path must be absolute" }
    connection.request(
        AppServerClientMethods.SkillsConfigWrite,
        SkillsConfigWriteParams(path = path, enabled = enabled),
    )
}
