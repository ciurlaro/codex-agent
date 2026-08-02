package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.agent.AgentCapability
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.*

internal val ClientMessage.params: JsonObject
    get() = objectValue["params"]!!.jsonObject

internal fun model(
    catalogId: String,
    runtimeId: String,
    displayName: String,
    defaultEffort: String,
    isDefault: Boolean,
) = buildJsonObject {
    put("id", catalogId)
    put("model", runtimeId)
    put("displayName", displayName)
    put("description", "$displayName description")
    put(
        "supportedReasoningEfforts",
        buildJsonArray {
            add(buildJsonObject { put("reasoningEffort", "low"); put("description", "Low") })
            add(buildJsonObject { put("reasoningEffort", "medium"); put("description", "Medium") })
        },
    )
    put("defaultReasoningEffort", defaultEffort)
    put("isDefault", isDefault)
}

internal fun page(data: List<JsonObject>, nextCursor: String?) = buildJsonObject {
    put("data", buildJsonArray { data.forEach { add(it) } })
    if (nextCursor == null) put("nextCursor", JsonNull) else put("nextCursor", nextCursor)
}

internal fun thread(
    id: String,
    name: String?,
    preview: String,
    updatedAt: Long,
    turns: kotlinx.serialization.json.JsonArray = buildJsonArray {},
) = buildJsonObject {
    put("id", id)
    if (name == null) put("name", JsonNull) else put("name", name)
    put("preview", preview)
    put("updatedAt", updatedAt)
    put("turns", turns)
}

internal fun taggedUserMessage(id: String, clientId: String, prompt: String): JsonObject {
    val capability = AgentCapability.WEB_SEARCH
    return buildJsonObject {
        put("id", id)
        put("clientId", clientId)
        put("type", "userMessage")
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "${capability.promptLabel}\n\n$prompt")
                        put(
                            "text_elements",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        putJsonObject("byteRange") {
                                            put("start", 0)
                                            put(
                                                "end",
                                                capability.promptLabel
                                                    .toByteArray(StandardCharsets.UTF_8)
                                                    .size,
                                            )
                                        }
                                        put("placeholder", capability.displayLabel)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }
}

internal fun plainUserMessage(id: String, clientId: String, text: String) = buildJsonObject {
    put("id", id)
    put("clientId", clientId)
    put("type", "userMessage")
    put("content", buildJsonArray {
        add(buildJsonObject {
            put("type", "text")
            put("text", text)
        })
    })
}

internal fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.content ?: error("Missing $name")

internal fun JsonObject.optionalString(name: String): String? =
    this[name]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

internal fun JsonObject.requiredBoolean(name: String): Boolean =
    requiredString(name).toBooleanStrict()

internal fun JsonObject.requiredInt(name: String): Int = requiredString(name).toInt()
