package io.github.ciurlaro.codexmobile.agent

data class AgentSkillCatalog(
    val skills: List<AgentSkill>,
    val errors: List<String> = emptyList(),
)

data class AgentSkillChunk(
    val content: String,
    val nextOffset: Long?,
    val totalBytes: Long,
)

data class AgentSkill(
    val name: String,
    val displayName: String,
    val description: String,
    val path: String,
    val scope: AgentSkillScope,
    val enabled: Boolean,
    val brandColor: String? = null,
    val dependencies: List<String> = emptyList(),
    val canUninstall: Boolean = false,
)

enum class AgentSkillScope(val displayName: String) {
    SYSTEM("Built in"),
    USER("User"),
    REPO("Workspace"),
    PLUGIN("Plugin"),
    ADMIN("Managed"),
}

data class AgentSkillPackageCatalog(
    val skills: List<AgentSkillPackage>,
    val freshness: AgentCatalogFreshness = AgentCatalogFreshness.LIVE,
    val errors: List<String> = emptyList(),
)

data class AgentSkillPackage(
    val id: String,
    val name: String,
    val displayName: String,
    val description: String,
    val source: AgentSkillPackageSource,
    val sourceUrl: String,
)

enum class AgentSkillPackageSource(val displayName: String) {
    OPENAI("OpenAI curated"),
    CODEX_MOBILE("Codex Mobile"),
    GITHUB("GitHub"),
}

enum class AgentCatalogFreshness { LIVE, FRESH_CACHE, STALE_CACHE }

data class AgentPluginReference(
    val id: String,
    val name: String,
    val marketplaceName: String,
    val marketplacePath: String? = null,
    val remotePluginId: String? = null,
) {
    val uri: String get() = "plugin://$name@$marketplaceName"
}

data class AgentPluginCatalog(
    val plugins: List<AgentPluginSummary>,
    val errors: List<String> = emptyList(),
    val freshness: AgentCatalogFreshness = AgentCatalogFreshness.LIVE,
)

data class AgentPluginSummary(
    val reference: AgentPluginReference,
    val displayName: String,
    val description: String,
    val installed: Boolean,
    val enabled: Boolean,
    val installPolicy: AgentPluginInstallPolicy,
    val authPolicy: AgentPluginAuthPolicy,
    val available: Boolean,
    val capabilities: List<String> = emptyList(),
    val brandColor: String? = null,
    val privacyPolicyUrl: String? = null,
    val termsOfServiceUrl: String? = null,
    val websiteUrl: String? = null,
)

enum class AgentPluginInstallPolicy { NOT_AVAILABLE, AVAILABLE, INSTALLED_BY_DEFAULT }

enum class AgentPluginAuthPolicy { ON_INSTALL, ON_USE }

data class AgentPluginDetail(
    val summary: AgentPluginSummary,
    val description: String,
    val skills: List<AgentPluginSkill>,
    val connectors: List<AgentConnector>,
    val mcpServers: List<String>,
    val hookCount: Int,
)

data class AgentPluginSkill(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val path: String? = null,
)

data class AgentPluginInstallResult(
    val authPolicy: AgentPluginAuthPolicy,
    val connectorsNeedingAuthentication: List<AgentConnector>,
    val message: String? = null,
)

data class AgentPluginRemovalResult(
    val completed: Boolean,
    val message: String? = null,
)

class AgentPluginUnavailableException(
    val pluginId: String,
    pluginName: String,
    message: String = "$pluginName is temporarily unavailable",
) : IllegalStateException(message)

data class AgentConnector(
    val id: String,
    val name: String,
    val description: String = "",
    val installUrl: String? = null,
    val isAccessible: Boolean = false,
    val isEnabled: Boolean = true,
    val pluginNames: List<String> = emptyList(),
)

data class AgentMcpServer(
    val name: String,
    val displayName: String,
    val authStatus: AgentMcpAuthStatus,
)

enum class AgentMcpAuthStatus { UNSUPPORTED, NOT_LOGGED_IN, BEARER_TOKEN, OAUTH }

sealed interface AgentInvocation {
    val name: String
    val key: String

    data class Skill(
        override val name: String,
        val path: String,
    ) : AgentInvocation {
        override val key: String get() = "skill:$path"
    }

    data class Plugin(
        override val name: String,
        val uri: String,
    ) : AgentInvocation {
        override val key: String get() = "plugin:$uri"
    }
}

data class AgentElicitation(
    val requestId: String,
    val serverName: String,
    val sessionId: SessionId,
    val message: String,
    val form: List<AgentFormField>? = null,
    val url: String? = null,
)

data class AgentFormField(
    val name: String,
    val title: String,
    val description: String? = null,
    val required: Boolean = false,
    val type: AgentFormFieldType,
    val options: List<AgentFormOption> = emptyList(),
    val defaultValue: AgentFormValue? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val allowOther: Boolean = false,
    val secret: Boolean = false,
)

enum class AgentFormFieldType { STRING, NUMBER, INTEGER, BOOLEAN, SINGLE_SELECT, MULTI_SELECT }

data class AgentFormOption(
    val value: String,
    val title: String = value,
    val description: String? = null,
)

sealed interface AgentFormValue {
    data class Text(val value: String) : AgentFormValue
    data class Number(val value: Double) : AgentFormValue
    data class BooleanValue(val value: Boolean) : AgentFormValue
    data class TextList(val value: List<String>) : AgentFormValue
}

data class AgentElicitationResponse(
    val action: AgentElicitationAction,
    val content: Map<String, AgentFormValue> = emptyMap(),
)

enum class AgentElicitationAction { ACCEPT, DECLINE, CANCEL }
