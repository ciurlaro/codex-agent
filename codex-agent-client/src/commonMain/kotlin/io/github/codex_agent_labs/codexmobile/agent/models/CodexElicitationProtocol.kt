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
import io.github.codex_agent_labs.codexmobile.agent.AgentFormStringFormat
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal fun parseElicitation(
    requestId: String,
    params: McpServerElicitationRequestParams,
): AgentElicitation {
    return when (params) {
        is McpServerElicitationRequestParamsForm -> AgentElicitation(
            requestId = requestId,
            serverName = params.serverName,
            conversationId = ConversationId(params.threadId),
            message = params.message,
            form = parseForm(params.requestedSchema),
        )
        is McpServerElicitationRequestParamsUrl -> AgentElicitation(
            requestId = requestId,
            serverName = params.serverName,
            conversationId = ConversationId(params.threadId),
            message = params.message,
            url = params.url.also(::requireSafeAuthUrl),
        )
        else -> error("Unsupported MCP elicitation mode")
    }
}

internal fun parseUserInputRequest(
    requestId: String,
    params: ToolRequestUserInputParams,
) = AgentElicitation(
    requestId = requestId,
    serverName = "Plan",
    conversationId = ConversationId(params.threadId),
    message = "Codex needs your input to continue planning.",
    form = params.questions.map { question ->
        val options = question.options.orEmpty()
        AgentFormField(
            name = question.id,
            title = question.header,
            description = question.question,
            isRequired = true,
            type = if (options.isEmpty()) AgentFormFieldType.STRING else AgentFormFieldType.SINGLE_SELECT,
            options = options.map { option ->
                AgentFormOption(option.label, option.label, option.description)
            },
            allowsOther = question.isOther == true,
            isSecret = question.isSecret == true,
        )
    },
)

internal fun parseForm(schema: McpElicitationSchema): List<AgentFormField> {
    val required = schema.required.orEmpty().toSet()
    return schema.properties.map { (name, raw) ->
        val field = raw.jsonObject
        val type = field.requiredString("type")
        val options = when {
            field["enum"] is JsonArray -> field.requiredArray("enum").map {
                AgentFormOption(it.jsonPrimitive.content)
            }
            field["oneOf"] is JsonArray -> field.requiredArray("oneOf").map {
                it.jsonObject.let { option ->
                    AgentFormOption(option.requiredString("const"), option.requiredString("title"))
                }
            }
            field.optionalObject("items")?.get("enum") is JsonArray ->
                field.requiredObject("items").requiredArray("enum").map {
                    AgentFormOption(it.jsonPrimitive.content)
                }
            field.optionalObject("items")?.get("anyOf") is JsonArray ->
                field.requiredObject("items").requiredArray("anyOf").map {
                    it.jsonObject.let { option ->
                        AgentFormOption(option.requiredString("const"), option.requiredString("title"))
                    }
                }
            else -> emptyList()
        }
        val fieldType = when {
            type == "array" && options.isNotEmpty() -> AgentFormFieldType.MULTI_SELECT
            type == "string" && options.isNotEmpty() -> AgentFormFieldType.SINGLE_SELECT
            type == "string" -> AgentFormFieldType.STRING
            type == "number" -> AgentFormFieldType.NUMBER
            type == "integer" -> AgentFormFieldType.INTEGER
            type == "boolean" -> AgentFormFieldType.BOOLEAN
            else -> error("Unsupported form field type")
        }
        AgentFormField(
            name = name,
            title = field.optionalString("title") ?: name.replace('_', ' ').replaceFirstChar(Char::uppercase),
            description = field.optionalString("description"),
            isRequired = name in required,
            type = fieldType,
            options = options,
            defaultValue = parseDefault(field, fieldType),
            minimum = field["minimum"]?.jsonPrimitive?.doubleOrNull,
            maximum = field["maximum"]?.jsonPrimitive?.doubleOrNull,
            format = field.optionalString("format")?.let { value ->
                when (value) {
                    "email" -> AgentFormStringFormat.EMAIL
                    "uri" -> AgentFormStringFormat.URI
                    "date" -> AgentFormStringFormat.DATE
                    "date-time" -> AgentFormStringFormat.DATE_TIME
                    else -> error("Unsupported string format")
                }
            },
            minimumLength = field["minLength"]?.jsonPrimitive?.longOrNull,
            maximumLength = field["maxLength"]?.jsonPrimitive?.longOrNull,
            minimumSelections = field["minItems"]?.jsonPrimitive?.longOrNull,
            maximumSelections = field["maxItems"]?.jsonPrimitive?.longOrNull,
        )
    }
}

internal fun parseDefault(field: JsonObject, type: AgentFormFieldType): AgentFormValue? {
    val value = field["default"]?.takeUnless { it is JsonNull } ?: return null
    return when (type) {
        AgentFormFieldType.STRING, AgentFormFieldType.SINGLE_SELECT ->
            value.jsonPrimitive.contentOrNull?.let(AgentFormValue::Text)
        AgentFormFieldType.NUMBER, AgentFormFieldType.INTEGER ->
            value.jsonPrimitive.doubleOrNull?.let(AgentFormValue::Number)
        AgentFormFieldType.BOOLEAN ->
            value.jsonPrimitive.booleanOrNull?.let(AgentFormValue::BooleanValue)
        AgentFormFieldType.MULTI_SELECT ->
            (value as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.let(AgentFormValue::TextList)
    }
}
