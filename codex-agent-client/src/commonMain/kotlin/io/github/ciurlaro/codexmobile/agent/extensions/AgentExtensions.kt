package io.github.ciurlaro.codexmobile.agent

public data class AgentSkillCatalog(
    public val skills: List<AgentSkill>,
    public val errors: List<String> = emptyList(),
)

public data class AgentSkillChunk(
    public val content: String,
    public val nextOffset: Long?,
    public val totalBytes: Long,
)

public data class AgentSkill(
    public val name: String,
    public val displayName: String,
    public val description: String,
    public val path: String,
    public val scope: AgentSkillScope,
    public val isEnabled: Boolean,
    public val brandColor: String? = null,
    public val dependencies: List<String> = emptyList(),
    public val canUninstall: Boolean = false,
)

public enum class AgentSkillScope(public val displayName: String) {
    SYSTEM("Built in"),
    USER("User"),
    REPO("Workspace"),
    PLUGIN("Plugin"),
    ADMIN("Managed"),
}

public enum class AgentCatalogFreshness { LIVE, FRESH_CACHE, STALE_CACHE }

public data class AgentPluginReference(
    public val id: String,
    public val name: String,
    public val marketplaceName: String,
    public val marketplacePath: String? = null,
    public val remotePluginId: String? = null,
) {
    public val uri: String get() = "plugin://$name@$marketplaceName"
}

public data class AgentPluginCatalog(
    public val plugins: List<AgentPluginSummary>,
    public val errors: List<String> = emptyList(),
    public val freshness: AgentCatalogFreshness = AgentCatalogFreshness.LIVE,
)

public data class AgentPluginSummary(
    public val reference: AgentPluginReference,
    public val displayName: String,
    public val description: String,
    public val isInstalled: Boolean,
    public val isEnabled: Boolean,
    public val installPolicy: AgentPluginInstallPolicy,
    public val authPolicy: AgentPluginAuthPolicy,
    public val isAvailable: Boolean,
    public val capabilities: List<String> = emptyList(),
    public val brandColor: String? = null,
    public val privacyPolicyUrl: String? = null,
    public val termsOfServiceUrl: String? = null,
    public val websiteUrl: String? = null,
)

public enum class AgentPluginInstallPolicy { NOT_AVAILABLE, AVAILABLE, INSTALLED_BY_DEFAULT }

public enum class AgentPluginAuthPolicy { ON_INSTALL, ON_USE }

public data class AgentPluginDetail(
    public val summary: AgentPluginSummary,
    public val description: String,
    public val skills: List<AgentPluginSkill>,
    public val connectors: List<AgentConnector>,
    public val mcpServers: List<String>,
    public val hookCount: Int,
)

public data class AgentPluginSkill(
    public val name: String,
    public val description: String,
    public val isEnabled: Boolean,
    public val path: String? = null,
)

public data class AgentPluginInstallResult(
    public val authPolicy: AgentPluginAuthPolicy,
    public val connectorsNeedingAuthentication: List<AgentConnector>,
    public val message: String? = null,
)

internal class AgentPluginUnavailableException(
    val pluginId: String,
    pluginName: String,
    message: String = "$pluginName is temporarily unavailable",
) : IllegalStateException(message)

public data class AgentConnector(
    public val id: String,
    public val name: String,
    public val description: String = "",
    public val installUrl: String? = null,
    public val isAccessible: Boolean = false,
    public val isEnabled: Boolean = true,
    public val pluginNames: List<String> = emptyList(),
)

public data class AgentMcpServer(
    public val name: String,
    public val displayName: String,
    public val authStatus: AgentMcpAuthStatus,
)

public enum class AgentMcpAuthStatus { UNSUPPORTED, NOT_LOGGED_IN, BEARER_TOKEN, OAUTH }

public sealed interface AgentInvocation {
    public val name: String
    public val key: String

    public data class Skill(
        public override val name: String,
        public val path: String,
    ) : AgentInvocation {
        public override val key: String get() = "skill:$path"
    }

    public data class Plugin(
        public override val name: String,
        public val uri: String,
    ) : AgentInvocation {
        public override val key: String get() = "plugin:$uri"
    }
}

public data class AgentElicitation(
    public val requestId: String,
    public val serverName: String,
    public val conversationId: ConversationId,
    public val message: String,
    public val form: List<AgentFormField>? = null,
    public val url: String? = null,
)

public data class AgentFormField(
    public val name: String,
    public val title: String,
    public val description: String? = null,
    public val isRequired: Boolean = false,
    public val type: AgentFormFieldType,
    public val options: List<AgentFormOption> = emptyList(),
    public val defaultValue: AgentFormValue? = null,
    public val minimum: Double? = null,
    public val maximum: Double? = null,
    public val format: AgentFormStringFormat? = null,
    public val minimumLength: Long? = null,
    public val maximumLength: Long? = null,
    public val minimumSelections: Long? = null,
    public val maximumSelections: Long? = null,
    public val allowsOther: Boolean = false,
    public val isSecret: Boolean = false,
) {
    init {
        require(minimumLength == null || minimumLength >= 0) { "Minimum length must not be negative" }
        require(maximumLength == null || maximumLength >= 0) { "Maximum length must not be negative" }
        require(minimumSelections == null || minimumSelections >= 0) { "Minimum selections must not be negative" }
        require(maximumSelections == null || maximumSelections >= 0) { "Maximum selections must not be negative" }
        require(minimumLength == null || maximumLength == null || minimumLength <= maximumLength) {
            "Minimum length must not exceed maximum length"
        }
        require(minimumSelections == null || maximumSelections == null || minimumSelections <= maximumSelections) {
            "Minimum selections must not exceed maximum selections"
        }
    }
}

public fun AgentFormField.accepts(value: AgentFormValue?): Boolean = validationReason(value) == null

public enum class AgentFormFieldType { STRING, NUMBER, INTEGER, BOOLEAN, SINGLE_SELECT, MULTI_SELECT }

public enum class AgentFormStringFormat { EMAIL, URI, DATE, DATE_TIME }

public data class AgentFormOption(
    public val value: String,
    public val title: String = value,
    public val description: String? = null,
)

public sealed interface AgentFormValue {
    public data class Text(public val value: String) : AgentFormValue
    public data class Number(public val value: Double) : AgentFormValue
    public data class BooleanValue(public val value: Boolean) : AgentFormValue
    public data class TextList(public val value: List<String>) : AgentFormValue
}

public data class AgentElicitationResponse(
    public val action: AgentElicitationAction,
    public val content: Map<String, AgentFormValue> = emptyMap(),
) {
    public companion object {
        public fun decline(): AgentElicitationResponse = AgentElicitationResponse(AgentElicitationAction.DECLINE)

        public fun cancel(): AgentElicitationResponse = AgentElicitationResponse(AgentElicitationAction.CANCEL)
    }
}

internal fun AgentElicitationResponse.snapshot(): AgentElicitationResponse = copy(
    content = content.mapValues { (_, value) ->
        if (value is AgentFormValue.TextList) value.copy(value = value.value.toList()) else value
    },
)

public enum class AgentElicitationAction { ACCEPT, DECLINE, CANCEL }

public enum class AgentElicitationValidationReason {
    MISSING_REQUIRED,
    UNKNOWN_FIELD,
    INVALID_TYPE,
    NON_FINITE_NUMBER,
    BELOW_MINIMUM,
    ABOVE_MAXIMUM,
    NON_INTEGER,
    INVALID_FORMAT,
    INVALID_SELECTION,
    DUPLICATE_SELECTION,
}

public data class AgentElicitationValidationIssue(
    public val fieldName: String,
    public val reason: AgentElicitationValidationReason,
)

public data class AgentElicitationValidation(
    public val issues: List<AgentElicitationValidationIssue>,
) {
    public val isValid: Boolean get() = issues.isEmpty()
}

public fun AgentElicitation.initialValues(): Map<String, AgentFormValue> =
    form.orEmpty().mapNotNull { field ->
        field.defaultValue?.let { field.name to it.snapshot() }
    }.toMap()

public fun AgentElicitation.validate(
    content: Map<String, AgentFormValue>,
): AgentElicitationValidation {
    val fields = form.orEmpty()
    val fieldsByName = fields.associateBy(AgentFormField::name)
    val issues = content.keys.filterNot(fieldsByName::containsKey).map { name ->
        AgentElicitationValidationIssue(name, AgentElicitationValidationReason.UNKNOWN_FIELD)
    } + fields.mapNotNull { field ->
        field.validationReason(content[field.name])?.let { reason ->
            AgentElicitationValidationIssue(field.name, reason)
        }
    }
    return AgentElicitationValidation(issues)
}

public fun AgentElicitation.accept(
    content: Map<String, AgentFormValue>,
): AgentElicitationResponse {
    require(validate(content).isValid) { "Elicitation content is invalid" }
    return AgentElicitationResponse(
        AgentElicitationAction.ACCEPT,
        content.mapValues { (_, value) -> value.snapshot() },
    )
}

public fun AgentElicitation.accepts(response: AgentElicitationResponse): Boolean = when (response.action) {
    AgentElicitationAction.DECLINE,
    AgentElicitationAction.CANCEL,
    -> response.content.isEmpty()
    AgentElicitationAction.ACCEPT -> validate(response.content).isValid
}

private fun AgentFormField.validationReason(value: AgentFormValue?): AgentElicitationValidationReason? {
    if (value == null) {
        return AgentElicitationValidationReason.MISSING_REQUIRED.takeIf { isRequired }
    }
    return when (type) {
        AgentFormFieldType.STRING -> when {
            value !is AgentFormValue.Text -> AgentElicitationValidationReason.INVALID_TYPE
            isRequired && value.value.isBlank() -> AgentElicitationValidationReason.MISSING_REQUIRED
            minimumLength != null && value.value.length < minimumLength ->
                AgentElicitationValidationReason.BELOW_MINIMUM
            maximumLength != null && value.value.length > maximumLength ->
                AgentElicitationValidationReason.ABOVE_MAXIMUM
            format != null && !value.value.matches(format) -> AgentElicitationValidationReason.INVALID_FORMAT
            else -> null
        }
        AgentFormFieldType.NUMBER,
        AgentFormFieldType.INTEGER,
        -> when {
            value !is AgentFormValue.Number -> AgentElicitationValidationReason.INVALID_TYPE
            !value.value.isFinite() -> AgentElicitationValidationReason.NON_FINITE_NUMBER
            type == AgentFormFieldType.INTEGER && value.value % 1.0 != 0.0 ->
                AgentElicitationValidationReason.NON_INTEGER
            minimum != null && value.value < minimum -> AgentElicitationValidationReason.BELOW_MINIMUM
            maximum != null && value.value > maximum -> AgentElicitationValidationReason.ABOVE_MAXIMUM
            else -> null
        }
        AgentFormFieldType.BOOLEAN ->
            AgentElicitationValidationReason.INVALID_TYPE.takeUnless { value is AgentFormValue.BooleanValue }
        AgentFormFieldType.SINGLE_SELECT -> when {
            value !is AgentFormValue.Text -> AgentElicitationValidationReason.INVALID_TYPE
            options.none { it.value == value.value } && !(allowsOther && value.value.isNotBlank()) ->
                AgentElicitationValidationReason.INVALID_SELECTION
            else -> null
        }
        AgentFormFieldType.MULTI_SELECT -> when {
            value !is AgentFormValue.TextList -> AgentElicitationValidationReason.INVALID_TYPE
            isRequired && value.value.isEmpty() -> AgentElicitationValidationReason.MISSING_REQUIRED
            value.value.distinct().size != value.value.size -> AgentElicitationValidationReason.DUPLICATE_SELECTION
            minimumSelections != null && value.value.size < minimumSelections ->
                AgentElicitationValidationReason.BELOW_MINIMUM
            maximumSelections != null && value.value.size > maximumSelections ->
                AgentElicitationValidationReason.ABOVE_MAXIMUM
            value.value.any { selected ->
                options.none { it.value == selected } && !(allowsOther && selected.isNotBlank())
            } -> AgentElicitationValidationReason.INVALID_SELECTION
            else -> null
        }
    }
}

private fun AgentFormValue.snapshot(): AgentFormValue =
    if (this is AgentFormValue.TextList) copy(value = value.toList()) else this

private fun String.matches(format: AgentFormStringFormat): Boolean = when (format) {
    AgentFormStringFormat.EMAIL -> {
        val separator = indexOf('@')
        separator > 0 && separator == lastIndexOf('@') && separator < lastIndex && none(Char::isWhitespace)
    }
    AgentFormStringFormat.URI -> {
        val separator = indexOf(':')
        separator > 0 && this[0].isLetter() && take(separator).all { it.isLetterOrDigit() || it in "+-." }
    }
    AgentFormStringFormat.DATE -> matchesDate()
    AgentFormStringFormat.DATE_TIME -> {
        val separator = indexOf('T')
        separator == 10 && take(separator).matchesDate() && drop(separator + 1).matchesTime()
    }
}

private fun String.matchesDate(): Boolean {
    if (length != 10 || this[4] != '-' || this[7] != '-') return false
    val year = take(4).toIntOrNull() ?: return false
    val month = substring(5, 7).toIntOrNull() ?: return false
    val day = substring(8, 10).toIntOrNull() ?: return false
    val maximumDay = when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28
        else -> return false
    }
    return day in 1..maximumDay
}

private fun String.matchesTime(): Boolean {
    if (length < 9 || this[2] != ':' || this[5] != ':') return false
    val hour = take(2).toIntOrNull() ?: return false
    val minute = substring(3, 5).toIntOrNull() ?: return false
    val second = substring(6, 8).toIntOrNull() ?: return false
    var zoneIndex = 8
    if (getOrNull(zoneIndex) == '.') {
        zoneIndex += 1
        val fractionStart = zoneIndex
        while (getOrNull(zoneIndex)?.isDigit() == true) zoneIndex += 1
        if (zoneIndex == fractionStart) return false
    }
    val zone = drop(zoneIndex)
    val validZone = zone == "Z" ||
        zone.length == 6 && zone[0] in "+-" && zone[3] == ':' &&
        zone.substring(1, 3).toIntOrNull()?.let { it in 0..23 } == true &&
        zone.substring(4, 6).toIntOrNull()?.let { it in 0..59 } == true
    return hour in 0..23 && minute in 0..59 && second in 0..60 && validZone
}
