package io.github.codex_agent_labs.codexmobile.appserver.generator

import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal const val GENERATOR_VERSION = "3"
internal val JSON = Json { prettyPrint = true }

fun main(arguments: Array<String>) {
    require(arguments.size == 9) {
        "Expected schema source, common.rs, thread.rs, turn.rs, canonical schema, descriptor, " +
            "descriptor Kotlin, model Kotlin and provenance paths"
    }
    val schemaSource = File(arguments[0])
    val commonFile = File(arguments[1])
    val threadFile = File(arguments[2])
    val turnFile = File(arguments[3])
    val schemaFile = File(arguments[4])
    val descriptorFile = File(arguments[5])
    val kotlinFile = File(arguments[6])
    val modelsFile = File(arguments[7])
    val provenanceFile = File(arguments[8])
    writeBytes(schemaFile, schemaSource.readBytes())
    val schema = JSON.parseToJsonElement(schemaFile.readText()).jsonObject
    val definitions = addUsedExperimentalFields(
        schema.getValue("definitions").jsonObject,
        threadFile.readText(),
        turnFile.readText(),
    )
    val common = commonFile.readText()

    val clientSource = parseRequestMacro(common, "client_request_definitions")
    val serverSource = parseRequestMacro(common, "server_request_definitions")
    val client = schemaRoutes(definitions, "ClientRequest").map { route ->
        val source = checkNotNull(clientSource[route.method]) {
            "Client route ${route.method} is absent from pinned common.rs"
        }
        check(source.paramsType == route.paramsType) {
            "Client route ${route.method} params disagree: ${route.paramsType} != ${source.paramsType}"
        }
        route.copy(
            responseType = source.responseType,
            serialization = source.serialization,
            experimentalReason = source.experimentalReason,
            inspectParams = source.inspectParams,
        )
    }
    val server = schemaRoutes(definitions, "ServerRequest").map { route ->
        val source = checkNotNull(serverSource[route.method]) {
            "Server route ${route.method} is absent from pinned common.rs"
        }
        check(source.paramsType == route.paramsType) {
            "Server route ${route.method} params disagree: ${route.paramsType} != ${source.paramsType}"
        }
        route.copy(responseType = source.responseType, experimentalReason = source.experimentalReason)
    }
    val notifications = schemaRoutes(definitions, "ServerNotification")
    val clientNotifications = schemaRoutes(definitions, "ClientNotification")
    check(clientNotifications.map(Route::method) == parseClientNotifications(common).map(Route::method)) {
        "Client notification schema disagrees with pinned common.rs"
    }
    val descriptor = buildJsonObject {
        put("formatVersion", 1)
        put("generatorVersion", GENERATOR_VERSION)
        put("schemaSha256", schemaFile.sha256())
        putJsonArray("clientRequests") { client.forEach { add(it.json()) } }
        putJsonArray("serverRequests") { server.forEach { add(it.json()) } }
        putJsonArray("serverNotifications") { notifications.forEach { add(it.json()) } }
        putJsonArray("clientNotifications") { clientNotifications.forEach { add(it.json()) } }
    }
    val models = ProtocolModels(definitions)
    writeText(descriptorFile, JSON.encodeToString(JsonObject.serializer(), descriptor) + "\n")
    val generatedFiles = renderKotlin(
        client,
        server,
        notifications,
        clientNotifications,
        schemaFile.sha256(),
        models,
    ) + models.renderFiles()
    writeGeneratedSources(
        modelsFile.parentFile,
        generatedFiles,
    )
    updateProvenance(
        provenanceFile,
        schemaFile,
        threadFile,
        turnFile,
        listOf(descriptorFile) + generatedFiles.map { modelsFile.parentFile.resolve(it.name) },
    )
}
