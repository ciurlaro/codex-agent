package io.github.ciurlaro.codexmobile.appserver.generator

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

internal fun addUsedExperimentalFields(
    definitions: JsonObject,
    threadSource: String,
    turnSource: String,
): JsonObject {
    check(
        Regex("#\\[experimental\\(\"thread/start\\.dynamicTools\"\\)\\][\\s\\S]{0,400}pub dynamic_tools: Option<Vec<DynamicToolSpec>>")
            .containsMatchIn(threadSource),
    ) { "Pinned thread.rs no longer declares thread/start.dynamicTools as expected" }
    check(
        Regex("#\\[experimental\\(\"turn/steer\\.additionalContext\"\\)\\][\\s\\S]{0,250}pub additional_context: Option<HashMap<String, AdditionalContextEntry>>")
            .containsMatchIn(turnSource),
    ) { "Pinned turn.rs no longer declares turn/steer.additionalContext as expected" }
    check(
        Regex("#\\[experimental\\(\"turn/start\\.collaborationMode\"\\)\\][\\s\\S]{0,250}pub collaboration_mode: Option<CollaborationMode>")
            .containsMatchIn(turnSource),
    ) { "Pinned turn.rs no longer declares turn/start.collaborationMode as expected" }
    val v2 = definitions.getValue("v2").jsonObject
    val augmentedV2 = JsonObject(v2.toMutableMap().apply {
        put(
            "ThreadStartParams",
            getValue("ThreadStartParams").jsonObject.withOptionalProperty(
                "dynamicTools",
                buildJsonObject {
                    put("type", "array")
                    putJsonObject("items") { put("\$ref", "#/definitions/v2/DynamicToolSpec") }
                },
            ),
        )
        put(
            "TurnSteerParams",
            getValue("TurnSteerParams").jsonObject.withOptionalProperty(
                "additionalContext",
                buildJsonObject {
                    put("type", "object")
                    putJsonObject("additionalProperties") {
                        put("\$ref", "#/definitions/v2/AdditionalContextEntry")
                    }
                },
            ),
        )
        put(
            "TurnStartParams",
            getValue("TurnStartParams").jsonObject.withOptionalProperty(
                "collaborationMode",
                buildJsonObject { put("\$ref", "#/definitions/v2/CollaborationMode") },
            ),
        )
    })
    return JsonObject(definitions + ("v2" to augmentedV2))
}

internal fun JsonObject.withOptionalProperty(name: String, schema: JsonObject): JsonObject =
    JsonObject(toMutableMap().apply {
        val properties = getValue("properties").jsonObject
        check(name !in properties) { "$name is already present in the stable schema" }
        put("properties", JsonObject(properties + (name to schema)))
    })

internal data class Route(
    val method: String,
    val variant: String,
    val paramsType: String,
    val responseType: String? = null,
    val serialization: String? = null,
    val experimentalReason: String? = null,
    val inspectParams: Boolean = false,
) {
    fun json() = buildJsonObject {
        put("method", method)
        put("variant", variant)
        put("paramsType", paramsType)
        responseType?.let { put("responseType", it) }
        serialization?.let { put("serialization", it) }
        experimentalReason?.let { put("experimentalReason", it) }
        if (inspectParams) put("inspectParams", true)
    }
}

internal fun schemaRoutes(definitions: JsonObject, name: String): List<Route> {
    val union = definitions.getValue(name).jsonObject.getValue("oneOf").jsonArray
    return union.map { raw ->
        val definition = raw.jsonObject
        val properties = definition.getValue("properties").jsonObject
        val method = properties.getValue("method").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content
        val params = properties["params"]?.jsonObject?.schemaTypeName() ?: "Unit"
        Route(method, definition.getValue("title").jsonPrimitive.content, params)
    }.also { routes ->
        check(routes.map(Route::method).distinct().size == routes.size) { "$name contains duplicate methods" }
    }.sortedBy(Route::method)
}

internal data class RustRoute(
    val paramsType: String,
    val responseType: String,
    val serialization: String?,
    val experimentalReason: String?,
    val inspectParams: Boolean,
)

internal fun parseRequestMacro(source: String, name: String): Map<String, RustRoute> {
    val body = macroBody(source, name)
    val entries = mutableMapOf<String, RustRoute>()
    var cursor = 0
    while (cursor < body.length) {
        val open = body.indexOf('{', cursor)
        if (open < 0) break
        val headerStart = body.lastIndexOf(',', open - 1).let { if (it < cursor) cursor else it + 1 }
        val header = body.substring(headerStart, open)
        val match = Regex("([A-Za-z][A-Za-z0-9]*)(?:\\s*=>\\s*\"([^\"]+)\")?\\s*$")
            .find(header)
        if (match == null) {
            cursor = open + 1
            continue
        }
        val close = matchingBrace(body, open)
        val fields = body.substring(open + 1, close)
        val variant = match.groupValues[1]
        val method = match.groupValues[2].ifEmpty { variant.replaceFirstChar(Char::lowercase) }
        val params = checkNotNull(field(fields, "params")) { "$name.$variant params are missing" }.rustType()
        val response = checkNotNull(field(fields, "response")) { "$name.$variant response is missing" }.rustType()
        val experimental = Regex("#\\[experimental\\(\"([^\"]+)\"\\)\\]").find(header)?.groupValues?.get(1)
        check(entries.put(method, RustRoute(
            paramsType = params,
            responseType = response,
            serialization = field(fields, "serialization"),
            experimentalReason = experimental,
            inspectParams = field(fields, "inspect_params") == "true",
        )) == null) { "$name contains duplicate method $method" }
        cursor = close + 1
    }
    return entries
}

internal fun parseClientNotifications(source: String): List<Route> {
    val body = macroBody(source, "client_notification_definitions")
    return Regex("[A-Za-z][A-Za-z0-9]*").findAll(body).map { match ->
        val variant = match.value
        Route(variant.replaceFirstChar(Char::lowercase), variant, "Unit")
    }.toList()
}

internal fun macroBody(source: String, name: String): String {
    val marker = "$name!"
    val markerIndex = source.indexOf(marker)
    check(markerIndex >= 0) { "$marker is absent from pinned common.rs" }
    val open = source.indexOf('{', markerIndex + marker.length)
    check(open >= 0) { "$marker has no body" }
    return source.substring(open + 1, matchingBrace(source, open))
}

internal fun matchingBrace(source: String, open: Int): Int {
    var depth = 0
    var quoted = false
    var escaped = false
    for (index in open until source.length) {
        val char = source[index]
        if (quoted) {
            if (escaped) escaped = false
            else if (char == '\\') escaped = true
            else if (char == '"') quoted = false
            continue
        }
        if (char == '"') quoted = true
        else if (char == '{') depth++
        else if (char == '}' && --depth == 0) return index
    }
    error("Unclosed Rust macro body")
}

internal fun field(body: String, name: String): String? = Regex("(?m)^\\s*$name:\\s*(.+),\\s*$")
    .find(body)?.groupValues?.get(1)?.trim()

internal fun String.rustType(): String = replace(Regex("#\\[[^]]+]\\s*"), "")
    .removePrefix("v1::")
    .removePrefix("v2::")
    .let { if (it == "()" || it == "Option<()>") "Unit" else it }

internal fun JsonObject.schemaTypeName(): String = get("\$ref")?.jsonPrimitive?.content
    ?.removePrefix("#/definitions/")
    ?.substringAfterLast('/')
    ?.also { check(it.isNotBlank()) { "Empty schema reference" } }
    ?: if (get("type")?.jsonPrimitive?.content == "null") "Unit" else error("Route params have no named type")
