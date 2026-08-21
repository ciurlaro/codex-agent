package io.github.codex_agent_labs.codexmobile.appserver.generator

internal fun renderKotlin(
    client: List<Route>,
    server: List<Route>,
    notifications: List<Route>,
    clientNotifications: List<Route>,
    schemaSha256: String,
    models: ProtocolModels,
): List<GeneratedFile> = listOf(
    GeneratedFile(
        "GeneratedProtocolDescriptors.kt",
        generatedHeader(
            "import kotlinx.serialization.KSerializer\nimport kotlinx.serialization.builtins.serializer",
        ) + buildString {
            appendLine("internal data class AppServerRequestDescriptor(")
            appendLine("    public val method: String,")
            appendLine("    public val paramsType: String,")
            appendLine("    public val responseType: String,")
            appendLine("    public val serialization: String? = null,")
            appendLine("    public val experimentalReason: String? = null,")
            appendLine("    public val inspectParams: Boolean = false,")
            appendLine(")")
            appendLine()
            appendLine("internal data class AppServerNotificationDescriptor(")
            appendLine("    public val method: String,")
            appendLine("    public val paramsType: String,")
            appendLine(")")
            appendLine()
            appendLine("internal interface AppServerMethod<P, R> {")
            appendLine("    public val descriptor: AppServerRequestDescriptor")
            appendLine("    public val paramsSerializer: KSerializer<P>")
            appendLine("    public val responseSerializer: KSerializer<R>")
            appendLine("}")
            appendLine()
            appendLine("internal object AppServerProtocolDescriptors {")
            appendLine("    public const val SCHEMA_SHA256: String = \"$schemaSha256\"")
            appendLine("    public val clientRequests: Map<String, AppServerRequestDescriptor> = generatedClientRequests")
            appendLine("    public val serverRequests: Map<String, AppServerRequestDescriptor> = generatedServerRequests")
            appendLine("    public val serverNotifications: Map<String, AppServerNotificationDescriptor> = generatedServerNotifications")
            appendLine("    public val clientNotifications: Map<String, AppServerNotificationDescriptor> = generatedClientNotifications")
            appendLine("}")
            appendLine()
            appendMethodObject("AppServerServerMethods", "serverRequests", server, models)
            appendLine()
            appendRequestMap("generatedServerRequests", server)
            appendLine()
            appendNotificationMap("generatedServerNotifications", notifications)
            appendLine()
            appendNotificationMap("generatedClientNotifications", clientNotifications)
        },
    ),
    GeneratedFile(
        "GeneratedProtocolClientRoutes.kt",
        generatedHeader() + buildString { appendRequestMap("generatedClientRequests", client) },
    ),
    GeneratedFile(
        "GeneratedProtocolClientMethods.kt",
        generatedHeader(
            "import kotlinx.serialization.KSerializer\nimport kotlinx.serialization.builtins.serializer",
        ) + buildString { appendMethodObject("AppServerClientMethods", "clientRequests", client, models) },
    ),
)

internal fun StringBuilder.appendRequestMap(name: String, routes: List<Route>) {
    appendLine("internal val $name: Map<String, AppServerRequestDescriptor> = listOf(")
    routes.forEach { route ->
        append("        AppServerRequestDescriptor(\"").append(route.method.escape()).append("\", \"")
            .append(route.paramsType.escape()).appendLine("\",")
        append("            \"").append(checkNotNull(route.responseType).escape()).append('"')
        route.serialization?.let { append(", serialization = \"").append(it.escape()).append('"') }
        route.experimentalReason?.let { append(", experimentalReason = \"").append(it.escape()).append('"') }
        if (route.inspectParams) append(", inspectParams = true")
        appendLine("),")
    }
    appendLine("    ).associateBy(AppServerRequestDescriptor::method)")
}

internal fun StringBuilder.appendNotificationMap(name: String, routes: List<Route>) {
    appendLine("internal val $name: Map<String, AppServerNotificationDescriptor> = listOf(")
    routes.forEach { route ->
        appendLine("        AppServerNotificationDescriptor(\"${route.method.escape()}\", \"${route.paramsType.escape()}\"),")
    }
    appendLine("    ).associateBy(AppServerNotificationDescriptor::method)")
}

internal fun StringBuilder.appendMethodObject(
    objectName: String,
    descriptorMap: String,
    routes: List<Route>,
    models: ProtocolModels,
) {
    appendLine("internal object $objectName {")
    routes.forEach { route ->
        val response = checkNotNull(route.responseType)
        appendLine("    public data object ${route.method.kotlinTypeName()} : AppServerMethod<${route.paramsType}, $response> {")
        append("        override val descriptor = AppServerProtocolDescriptors.$descriptorMap.getValue(\"${route.method.escape()}\"); ")
        append("override val paramsSerializer = ${models.serializer(route.paramsType)}; ")
        appendLine("override val responseSerializer = ${models.serializer(response)} }")
        appendLine()
    }
    appendLine("}")
}
