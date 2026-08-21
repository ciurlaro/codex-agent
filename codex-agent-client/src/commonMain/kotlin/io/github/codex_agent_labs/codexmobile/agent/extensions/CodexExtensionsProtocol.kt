package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.AppInfo
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.AppSummary
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.McpAuthStatus
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.McpElicitationSchema
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.McpServerStatus
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.McpServerElicitationRequestParams
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.McpServerElicitationRequestParamsForm
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.McpServerElicitationRequestParamsUrl
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.PluginDetail
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.PluginInstallPolicy
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.PluginMarketplaceEntry
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.PluginSummary
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.SkillMetadata
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.SkillScope
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.ToolRequestUserInputParams
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.UserInput
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.UserInputMentionUserInput
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.UserInputSkillUserInput
import io.github.codex_agent_labs.codexmobile.agent.AgentConnector
import io.github.codex_agent_labs.codexmobile.agent.AgentElicitation
import io.github.codex_agent_labs.codexmobile.agent.AgentFormField
import io.github.codex_agent_labs.codexmobile.agent.AgentFormFieldType
import io.github.codex_agent_labs.codexmobile.agent.AgentFormOption
import io.github.codex_agent_labs.codexmobile.agent.AgentFormValue
import io.github.codex_agent_labs.codexmobile.agent.AgentInvocation
import io.github.codex_agent_labs.codexmobile.agent.AgentMcpAuthStatus
import io.github.codex_agent_labs.codexmobile.agent.AgentMcpServer
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginAuthPolicy
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginDetail
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginInstallPolicy
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginReference
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginSkill
import io.github.codex_agent_labs.codexmobile.agent.AgentPluginSummary
import io.github.codex_agent_labs.codexmobile.agent.AgentSkill
import io.github.codex_agent_labs.codexmobile.agent.AgentSkillScope
import io.github.codex_agent_labs.codexmobile.agent.ConversationId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun parseSkill(item: SkillMetadata): AgentSkill {
    val interfaceInfo = item.interface_
    val path = item.path
    return AgentSkill(
        name = item.name,
        displayName = interfaceInfo?.displayName
            ?: item.name.replace('-', ' ').replaceFirstChar(Char::uppercase),
        description = interfaceInfo?.shortDescription
            ?: item.shortDescription
            ?: item.description,
        path = path,
        scope = if ("/plugins/" in path) AgentSkillScope.PLUGIN else when (item.scope) {
            SkillScope.SYSTEM -> AgentSkillScope.SYSTEM
            SkillScope.USER -> AgentSkillScope.USER
            SkillScope.REPO -> AgentSkillScope.REPO
            SkillScope.ADMIN -> AgentSkillScope.ADMIN
        },
        isEnabled = item.enabled,
        brandColor = interfaceInfo?.brandColor,
        dependencies = item.dependencies?.tools.orEmpty().map { it.value },
    )
}

internal fun parsePluginSummary(
    item: PluginSummary,
    marketplaceName: String,
    marketplacePath: String?,
): AgentPluginSummary {
    val interfaceInfo = item.interface_
    val name = item.name
    return AgentPluginSummary(
        reference = AgentPluginReference(
            id = item.id,
            name = name,
            marketplaceName = marketplaceName,
            marketplacePath = marketplacePath,
            remotePluginId = item.remotePluginId,
        ),
        displayName = interfaceInfo?.displayName
            ?: name.replace('-', ' ').replaceFirstChar(Char::uppercase),
        description = interfaceInfo?.shortDescription ?: interfaceInfo?.longDescription.orEmpty(),
        isInstalled = item.installed,
        isEnabled = item.enabled,
        installPolicy = enumValueOf(item.installPolicy.name),
        authPolicy = enumValueOf(item.authPolicy.name),
        isAvailable = (item.availability as? JsonPrimitive)?.contentOrNull != "DISABLED_BY_ADMIN" &&
            item.installPolicy != PluginInstallPolicy.NOT_AVAILABLE,
        capabilities = interfaceInfo?.capabilities.orEmpty(),
        brandColor = interfaceInfo?.brandColor,
        privacyPolicyUrl = interfaceInfo?.privacyPolicyUrl,
        termsOfServiceUrl = interfaceInfo?.termsOfServiceUrl,
        websiteUrl = interfaceInfo?.websiteUrl,
    )
}

internal fun parsePluginMarketplaces(marketplaces: List<PluginMarketplaceEntry>): List<AgentPluginSummary> =
    marketplaces.flatMap { marketplace ->
        marketplace.plugins.map {
            parsePluginSummary(it, marketplace.name, marketplace.path)
        }
    }

internal fun parsePluginDetail(plugin: PluginDetail): AgentPluginDetail {
    val summary = parsePluginSummary(
        plugin.summary,
        plugin.marketplaceName,
        plugin.marketplacePath,
    )
    return AgentPluginDetail(
        summary = summary,
        description = plugin.description ?: summary.description,
        skills = plugin.skills.map { skill ->
            AgentPluginSkill(
                name = skill.name,
                description = skill.description,
                isEnabled = skill.enabled,
                path = skill.path,
            )
        },
        connectors = plugin.apps.map(::parseConnector),
        mcpServers = plugin.mcpServers,
        hookCount = plugin.hooks.size,
    )
}

internal fun parseConnector(item: AppInfo) = AgentConnector(
    id = item.id,
    name = item.name,
    description = item.description.orEmpty(),
    installUrl = item.installUrl?.also(::requireSafeAuthUrl),
    isAccessible = item.isAccessible ?: false,
    isEnabled = item.isEnabled ?: true,
    pluginNames = item.pluginDisplayNames.orEmpty(),
)

internal fun parseConnector(item: AppSummary) = AgentConnector(
    id = item.id,
    name = item.name,
    description = item.description.orEmpty(),
    installUrl = item.installUrl?.also(::requireSafeAuthUrl),
    isAccessible = false,
    isEnabled = true,
    pluginNames = emptyList(),
)

internal fun parseMcpServer(item: McpServerStatus): AgentMcpServer {
    val name = item.name
    return AgentMcpServer(
        name = name,
        displayName = item.serverInfo?.title ?: name,
        authStatus = when (item.authStatus) {
            McpAuthStatus.UNSUPPORTED -> AgentMcpAuthStatus.UNSUPPORTED
            McpAuthStatus.NOT_LOGGED_IN -> AgentMcpAuthStatus.NOT_LOGGED_IN
            McpAuthStatus.BEARER_TOKEN -> AgentMcpAuthStatus.BEARER_TOKEN
            McpAuthStatus.O_AUTH -> AgentMcpAuthStatus.OAUTH
        },
    )
}

internal fun invocationInput(invocation: AgentInvocation): UserInput = when (invocation) {
    is AgentInvocation.Skill -> UserInputSkillUserInput(invocation.name, invocation.path)
    is AgentInvocation.Plugin -> UserInputMentionUserInput(invocation.name, invocation.uri)
}

internal fun parseInvocation(item: JsonObject): AgentInvocation? = when (item.optionalString("type")) {
    "skill" -> AgentInvocation.Skill(item.requiredString("name"), item.requiredString("path"))
    "mention" -> item.requiredString("path").takeIf { it.startsWith("plugin://") }
        ?.let { AgentInvocation.Plugin(item.requiredString("name"), it) }
    else -> null
}

internal fun JsonObject.optionalObject(name: String): JsonObject? =
    this[name] as? JsonObject

internal fun JsonObject.optionalBoolean(name: String): Boolean? =
    this[name]?.jsonPrimitive?.booleanOrNull
