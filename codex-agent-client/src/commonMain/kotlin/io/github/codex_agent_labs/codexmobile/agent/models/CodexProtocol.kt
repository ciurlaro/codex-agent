package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.Thread
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.ByteRange
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.TextElement
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.UserInput
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.UserInputTextUserInput
import io.github.codex_agent_labs.codexmobile.agent.AgentCapability
import io.github.codex_agent_labs.codexmobile.agent.AgentCollaborationMode
import io.github.codex_agent_labs.codexmobile.agent.AgentConversationSummary
import io.github.codex_agent_labs.codexmobile.agent.AgentMessage
import io.github.codex_agent_labs.codexmobile.agent.AgentMessageRole
import io.github.codex_agent_labs.codexmobile.agent.AgentInvocation
import io.github.codex_agent_labs.codexmobile.agent.AgentTurnRequest
import io.github.codex_agent_labs.codexmobile.agent.PLAN_CLIENT_MESSAGE_PREFIX
import io.github.codex_agent_labs.codexmobile.agent.LEGACY_PLAN_CLIENT_MESSAGE_PREFIX
import io.github.codex_agent_labs.codexmobile.agent.ConversationId
import io.github.codex_agent_labs.codexmobile.agent.deriveConversationTitle
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun compactDescription(value: JsonElement): String = value.toString().let {
    if (it.length <= 2_000) it else it.take(2_000) + "…"
}

internal fun conversationSummary(thread: JsonObject): AgentConversationSummary {
    val preview = cleanTaggedPreview(thread.requiredText("preview"))
    return AgentConversationSummary(
        conversationId = ConversationId(thread.requiredString("id")),
        title = deriveConversationTitle(thread.optionalString("name"), preview),
        updatedAtEpochSeconds = thread.requiredLong("updatedAt"),
    )
}

internal fun conversationSummary(thread: Thread, fallbackPreview: String? = null): AgentConversationSummary {
    val preview = cleanTaggedPreview(thread.preview).ifBlank { fallbackPreview.orEmpty() }
    return AgentConversationSummary(
        conversationId = ConversationId(thread.id),
        title = deriveConversationTitle(thread.name, preview),
        updatedAtEpochSeconds = thread.updatedAt,
    )
}

internal fun conversationMessages(
    rawItems: List<JsonElement>,
    recordedInvocations: Map<String, List<AgentInvocation>> = emptyMap(),
): List<AgentMessage> {
    val messages = mutableListOf<AgentMessage>()
    val reasoning = mutableListOf<String>()
    var reasoningId: String? = null
    rawItems.forEach { rawItem ->
        val item = rawItem.jsonObject
        if (item.requiredString("type") == "reasoning") {
            val parts = item.optionalArray("summary")
                .mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
            if (parts.isNotEmpty()) {
                reasoningId = item.requiredString("id")
                reasoning += parts
            }
            return@forEach
        }
        if (
            item.requiredString("type") == "agentMessage" &&
            item.optionalString("phase") == "commentary"
        ) {
            item.requiredText("text").trim().takeIf(String::isNotEmpty)?.let {
                reasoningId = item.requiredString("id")
                reasoning += it
            }
            return@forEach
        }
        val message = conversationMessage(rawItem, recordedInvocations) ?: return@forEach
        if (message.role == AgentMessageRole.ASSISTANT && reasoning.isNotEmpty()) {
            messages += message.copy(reasoning = reasoning.joinToString("\n\n"))
            reasoning.clear()
            reasoningId = null
        } else {
            messages += message
        }
    }
    if (reasoning.isNotEmpty()) {
        messages += AgentMessage(
            id = reasoningId ?: "reasoning",
            clientMessageId = null,
            role = AgentMessageRole.ASSISTANT,
            text = "",
            reasoning = reasoning.joinToString("\n\n"),
        )
    }
    return messages
}

internal fun conversationMessage(
    rawItem: JsonElement,
    recordedInvocations: Map<String, List<AgentInvocation>> = emptyMap(),
): AgentMessage? {
    val item = rawItem.jsonObject
    return when (item.requiredString("type")) {
        "userMessage" -> {
            val wireClientMessageId = item.optionalString("clientId")
            if (wireClientMessageId?.startsWith("codex-mobile:plugin-availability:") == true) {
                return null
            }
            val content = item.requiredArray("content").map(JsonElement::jsonObject)
            val persistedInvocations = content.mapNotNull(::parseInvocation).distinctBy(AgentInvocation::key)
            val invocations = wireClientMessageId?.let(recordedInvocations::get) ?: persistedInvocations
            val prompts = content.mapNotNull { input ->
                input.takeIf { it.optionalString("type") == "text" }
                    ?.let { parsePrompt(it, invocations) }
            }
            if (prompts.isEmpty() && invocations.isEmpty()) return null
            AgentMessage(
                id = item.requiredString("id"),
                clientMessageId = wireClientMessageId?.logicalClientMessageId(),
                role = AgentMessageRole.USER,
                text = prompts.joinToString("\n", transform = ParsedPrompt::text),
                collaborationMode = if (wireClientMessageId.isPlanClientMessageId()) {
                    AgentCollaborationMode.PLAN
                } else {
                    AgentCollaborationMode.DEFAULT
                },
                capabilities = prompts.flatMap(ParsedPrompt::capabilities).toSet(),
                invocations = invocations,
            )
        }

        "agentMessage" -> AgentMessage(
            id = item.requiredString("id"),
            clientMessageId = null,
            role = AgentMessageRole.ASSISTANT,
            text = item.requiredText("text"),
        )

        "plan" -> AgentMessage(
            id = item.requiredString("id"),
            clientMessageId = null,
            role = AgentMessageRole.ASSISTANT,
            text = "",
            plan = item.requiredText("text"),
        )

        else -> null
    }
}

private fun String?.isPlanClientMessageId(): Boolean = this != null &&
    (startsWith(PLAN_CLIENT_MESSAGE_PREFIX) || startsWith(LEGACY_PLAN_CLIENT_MESSAGE_PREFIX))

private fun String.logicalClientMessageId(): String = when {
    startsWith(PLAN_CLIENT_MESSAGE_PREFIX) -> removePrefix(PLAN_CLIENT_MESSAGE_PREFIX)
    startsWith(LEGACY_PLAN_CLIENT_MESSAGE_PREFIX) -> removePrefix(LEGACY_PLAN_CLIENT_MESSAGE_PREFIX)
    else -> this
}

internal fun turnInput(request: AgentTurnRequest): List<UserInput> {
    val capabilities = request.capabilities.sortedBy(AgentCapability::id)
    val invocations = request.invocations.distinctBy(AgentInvocation::key)
    val tagBlock = buildList {
        addAll(capabilities.map(AgentCapability::promptLabel))
        addAll(invocations.map {
            when (it) {
                is AgentInvocation.Skill -> "\$${it.name}"
                is AgentInvocation.Plugin -> "@${it.name}"
            }
        })
    }.joinToString("\n")
    val text = when {
        tagBlock.isEmpty() -> request.prompt
        request.prompt.isBlank() -> tagBlock
        else -> "$tagBlock\n\n${request.prompt}"
    }
    var start = 0L
    val elements = capabilities.map { capability ->
        val end = start + capability.promptLabel.encodeToByteArray().size
        TextElement(ByteRange(start = start, end = end), capability.displayLabel).also { start = end + 1 }
    }.takeIf { it.isNotEmpty() }
    return listOf(UserInputTextUserInput(text, elements)) + invocations.map(::invocationInput)
}

internal fun parsePrompt(
    input: JsonObject,
    invocations: List<AgentInvocation> = emptyList(),
): ParsedPrompt {
    val text = input.requiredText("text")
    val bytes = text.encodeToByteArray()
    val capabilities = input.optionalArray("text_elements").mapNotNull { rawElement ->
        runCatching {
            val element = rawElement.jsonObject
            val capability = AgentCapability.entries.singleOrNull {
                it.displayLabel == element.optionalString("placeholder")
            } ?: return@runCatching null
            val range = element.requiredObject("byteRange")
            val start = range.requiredLong("start").toInt()
            val end = range.requiredLong("end").toInt()
            capability.takeIf {
                start >= 0 && end in start..bytes.size &&
                    bytes.decodeToString(start, end, throwOnInvalidSequence = true) == it.promptLabel
            }
        }.getOrNull()
    }.toSet()
    val tagBlock = buildList {
        addAll(capabilities.sortedBy(AgentCapability::id).map(AgentCapability::promptLabel))
        addAll(invocations.map {
            when (it) {
                is AgentInvocation.Skill -> "\$${it.name}"
                is AgentInvocation.Plugin -> "@${it.name}"
            }
        })
    }.joinToString("\n")
    val visibleText = when {
        tagBlock.isEmpty() -> text
        text == tagBlock -> ""
        text.startsWith("$tagBlock\n\n") -> text.removePrefix("$tagBlock\n\n")
        else -> text
    }
    return ParsedPrompt(visibleText, capabilities)
}

internal fun cleanTaggedPreview(preview: String): String {
    val labels = AgentCapability.entries.map(AgentCapability::promptLabel).toSet()
    val lines = preview.lines()
    val firstVisible = lines.indexOfFirst { it !in labels && it.isNotEmpty() }
    if (firstVisible <= 0 || lines.take(firstVisible).none { it in labels }) return preview
    return lines.drop(firstVisible).joinToString("\n")
}

internal fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)
        ?: error("Missing $name")

internal fun JsonObject.requiredText(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: error("Missing $name")

internal fun JsonObject.optionalString(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.requiredLong(name: String): Long =
    this[name]?.jsonPrimitive?.longOrNull ?: error("Missing $name")

internal fun JsonObject.requiredBoolean(name: String): Boolean =
    this[name]?.jsonPrimitive?.booleanOrNull ?: error("Missing $name")

internal fun JsonObject.requiredArray(name: String): JsonArray =
    this[name]?.jsonArray ?: error("Missing $name")

internal fun JsonObject.optionalArray(name: String): JsonArray =
    this[name]?.let { if (it is JsonNull) null else it.jsonArray } ?: JsonArray(emptyList())

internal fun JsonObject.requiredObject(name: String): JsonObject =
    this[name] as? JsonObject ?: error("Missing $name")

internal data class ParsedPrompt(
    val text: String,
    val capabilities: Set<AgentCapability>,
)
