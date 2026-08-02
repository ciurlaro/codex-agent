package io.github.ciurlaro.codexmobile.appserver

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerProtocolDescriptors
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerClientMethods
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerServerMethods
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AdditionalContextEntry
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AdditionalContextKind
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.DynamicToolCallParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.LoginAccountParamsChatgpt
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.McpServerElicitationRequestParamsUrl
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.PluginListParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ThreadStartParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.TurnSteerParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppServerProtocolIdentityTest {
    @Test
    fun clientIdentityIsBoundToThePinnedRuntimeRevision() {
        assertEquals("0.145.0", AppServerProtocolIdentity.APP_SERVER_VERSION)
        assertEquals(40, AppServerProtocolIdentity.UPSTREAM_REVISION.length)
        assertEquals(64, AppServerProtocolIdentity.SCHEMA_SHA256.length)
        assertEquals(AppServerProtocolIdentity.COMPLETE_SCHEMA_SHA256, AppServerProtocolDescriptors.SCHEMA_SHA256)
    }

    @Test
    fun generatedDescriptorsCoverEveryPinnedWireDirection() {
        assertEquals(89, AppServerProtocolDescriptors.clientRequests.size)
        assertEquals(10, AppServerProtocolDescriptors.serverRequests.size)
        assertEquals(70, AppServerProtocolDescriptors.serverNotifications.size)
        assertEquals(setOf("initialized"), AppServerProtocolDescriptors.clientNotifications.keys)
        assertEquals(
            "DynamicToolCallResponse",
            AppServerProtocolDescriptors.serverRequests.getValue("item/tool/call").responseType,
        )
    }

    @Test
    fun typedMethodSerializersPreservePinnedWireNamesAndUnions() {
        val json = Json { explicitNulls = false }
        val pluginParams = json.encodeToJsonElement(
            AppServerClientMethods.PluginList.paramsSerializer,
            PluginListParams(cwds = listOf("/workspace")),
        ).jsonObject
        assertEquals("/workspace", pluginParams.getValue("cwds").jsonArray.single().jsonPrimitive.content)

        val dynamic = json.decodeFromString(
            AppServerServerMethods.ItemToolCall.paramsSerializer,
            """{"threadId":"thread","turnId":"turn","callId":"call","tool":"calendar_read","arguments":{}}""",
        )
        assertIs<DynamicToolCallParams>(dynamic)
        assertEquals("calendar_read", dynamic.tool)

        val login = json.decodeFromString(
            AppServerClientMethods.AccountLoginStart.paramsSerializer,
            """{"type":"chatgpt"}""",
        )
        assertIs<LoginAccountParamsChatgpt>(login)

        val elicitation = json.decodeFromString(
            AppServerServerMethods.McpServerElicitationRequest.paramsSerializer,
            """{"serverName":"github","threadId":"thread","mode":"url","elicitationId":"id","message":"Sign in","url":"https://example.com"}""",
        )
        assertEquals("github", assertIs<McpServerElicitationRequestParamsUrl>(elicitation).serverName)

        val start = ThreadStartParams(dynamicTools = emptyList())
        assertEquals(0, json.decodeFromString(
            AppServerClientMethods.ThreadStart.paramsSerializer,
            json.encodeToString(AppServerClientMethods.ThreadStart.paramsSerializer, start),
        ).dynamicTools?.size)

        val steer = TurnSteerParams(
            expectedTurnId = "turn",
            input = emptyList(),
            threadId = "thread",
            additionalContext = mapOf(
                "runtime-availability" to AdditionalContextEntry(
                    AdditionalContextKind.APPLICATION,
                    "calendar=unavailable",
                ),
            ),
        )
        assertEquals(
            "calendar=unavailable",
            json.decodeFromString(
                AppServerClientMethods.TurnSteer.paramsSerializer,
                json.encodeToString(AppServerClientMethods.TurnSteer.paramsSerializer, steer),
            ).additionalContext?.get("runtime-availability")?.value,
        )
    }
}
