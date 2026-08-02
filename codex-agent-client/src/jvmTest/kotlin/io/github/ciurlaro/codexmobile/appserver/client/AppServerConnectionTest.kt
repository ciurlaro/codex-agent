package io.github.ciurlaro.codexmobile.appserver.client

import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerClientMethods
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.AppServerServerMethods
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ClientInfo
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.DynamicToolCallResponse
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeCapabilities
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeParams
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ServerNotificationSkillsChangedNotification
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ServerRequestItemToolCallRequest
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ThreadLoadedListParams
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppServerConnectionTest {
    @Test
    fun initializesOnceAndExecutesATypedRequest() = runBlocking {
        val runtime = FakeRuntime()
        val connection = connection(runtime)
        initialize(connection, runtime)

        val request = async {
            connection.request(AppServerClientMethods.ThreadLoadedList, ThreadLoadedListParams())
        }
        val line = runtime.sent.receive().jsonObject()
        assertEquals("thread/loaded/list", line.getValue("method").jsonPrimitive.content)
        runtime.receive(
            """{"id":${line.getValue("id")},"result":{"data":[]}}""",
        )
        assertTrue(request.await().data.isEmpty())

        connection.shutdown()
        assertEquals(1, runtime.closeCount)
        assertEquals(AppServerConnectionState.Closed, connection.state.value)
    }

    @Test
    fun decodesServerEventsAndWritesTypedResponses() = runBlocking {
        val runtime = FakeRuntime()
        val connection = connection(runtime)
        initialize(connection, runtime)

        val toolEvent = async { connection.events.first() }
        runtime.receive(
            """{"id":77,"method":"item/tool/call","params":{"threadId":"t","turnId":"u","callId":"c","tool":"calendar_read","arguments":{}}}""",
        )
        val request = assertIs<AppServerEvent.Request>(toolEvent.await())
        assertEquals("item/tool/call", request.descriptor.method)
        assertIs<ServerRequestItemToolCallRequest>(request.value)
        connection.respond(
            request.value.id,
            AppServerServerMethods.ItemToolCall,
            DynamicToolCallResponse(emptyList(), true),
        )
        assertTrue(runtime.sent.receive().jsonObject().getValue("result").jsonObject
            .getValue("success").jsonPrimitive.content.toBoolean())

        val notificationEvent = async { connection.events.first() }
        runtime.receive("""{"method":"skills/changed","params":{}}""")
        val notification = assertIs<AppServerEvent.Notification>(notificationEvent.await())
        assertIs<ServerNotificationSkillsChangedNotification>(notification.value)
        connection.shutdown()
    }

    @Test
    fun requestTimeoutRemovesCorrelationAndALaterRequestStillWorks() = runBlocking {
        supervisorScope {
            val runtime = FakeRuntime()
            val connection = connection(runtime)
            initialize(connection, runtime)

            val timedOut = async {
                connection.request(
                    AppServerClientMethods.ThreadLoadedList,
                    ThreadLoadedListParams(),
                    timeoutMillis = 25,
                )
            }
            val abandoned = runtime.sent.receive().jsonObject()
            val timeout = assertFailsWith<AppServerTimeoutException> { timedOut.await() }
            assertTrue(timeout.message.orEmpty().contains("thread/loaded/list"))
            runtime.receive("""{"id":${abandoned.getValue("id")},"result":{"data":[]}}""")

            val next = async {
                connection.request(AppServerClientMethods.ThreadLoadedList, ThreadLoadedListParams())
            }
            val live = runtime.sent.receive().jsonObject()
            runtime.receive("""{"id":${live.getValue("id")},"result":{"data":["thread"]}}""")
            assertEquals(listOf("thread"), next.await().data)
            connection.shutdown()
        }
    }

    @Test
    fun callerCancellationRemainsCancellationAndRemovesCorrelation() = runBlocking {
        val runtime = FakeRuntime()
        val connection = connection(runtime)
        initialize(connection, runtime)

        val cancelled = async {
            connection.request(AppServerClientMethods.ThreadLoadedList, ThreadLoadedListParams())
        }
        val abandoned = runtime.sent.receive().jsonObject()
        cancelled.cancel()
        assertFailsWith<CancellationException> { cancelled.await() }
        runtime.receive("""{"id":${abandoned.getValue("id")},"result":{"data":[]}}""")

        val next = async {
            connection.request(AppServerClientMethods.ThreadLoadedList, ThreadLoadedListParams())
        }
        val live = runtime.sent.receive().jsonObject()
        runtime.receive("""{"id":${live.getValue("id")},"result":{"data":["thread"]}}""")
        assertEquals(listOf("thread"), next.await().data)
        connection.shutdown()
    }

    @Test
    fun malformedOutputFailsPendingWorkAndReportsATypedFailure() = runBlocking {
        val runtime = FakeRuntime()
        val connection = connection(runtime)
        initialize(connection, runtime)
        val failure = async { connection.events.first() }

        runtime.receive("{")

        val event = assertIs<AppServerEvent.Failure>(failure.await())
        assertEquals("protocol_failure", event.code)
        assertIs<AppServerConnectionState.Failed>(connection.state.value)
        connection.shutdown()
    }

    @Test
    fun fullEventBufferFailsExplicitlyWithoutBlockingTheRuntimeReader() = runBlocking {
        val runtime = FakeRuntime()
        val connection = connection(runtime, eventCapacity = 1)
        initialize(connection, runtime)

        runtime.receive("""{"method":"skills/changed","params":{}}""")
        runtime.receive("""{"method":"skills/changed","params":{}}""")

        val failure = withTimeout(2_000) {
            connection.state.first { it is AppServerConnectionState.Failed }
        }
        assertEquals("event_delivery_overflow", assertIs<AppServerConnectionState.Failed>(failure).code)
        assertEquals(1, runtime.closeCount)
        assertFailsWith<AppServerDeliveryException> { connection.events.toList() }
        assertFailsWith<AppServerRuntimeException> { connection.ensureStarted() }
        connection.shutdown()
        assertEquals(1, runtime.closeCount)
    }

    private suspend fun initialize(connection: AppServerConnection, runtime: FakeRuntime) = coroutineScope {
        val start = async { connection.ensureStarted() }
        val initialize = runtime.sent.receive().jsonObject()
        assertEquals("initialize", initialize.getValue("method").jsonPrimitive.content)
        runtime.receive(
            """{"id":${initialize.getValue("id")},"result":{"codexHome":"/tmp/codex","platformFamily":"unix","platformOs":"android","userAgent":"test"}}""",
        )
        assertEquals("android", start.await().platformOs)
        assertEquals("initialized", runtime.sent.receive().jsonObject().getValue("method").jsonPrimitive.content)
    }

    private fun connection(runtime: FakeRuntime, eventCapacity: Int = 16) = AppServerConnection(
        runtimeFactory = { runtime },
        initializeParams = InitializeParams(
            clientInfo = ClientInfo("codex_mobile", "test", "Codex Mobile"),
            capabilities = InitializeCapabilities(
                experimentalApi = true,
                mcpServerOpenaiFormElicitation = false,
            ),
        ),
        requestTimeoutMillis = 2_000,
        eventCapacity = eventCapacity,
    )

    private class FakeRuntime : CodexRuntime {
        private val incoming = Channel<CodexRuntimeEvent>(Channel.UNLIMITED)
        val sent = Channel<CodexJsonLine>(Channel.UNLIMITED)
        var closeCount = 0
            private set

        override val events: Flow<CodexRuntimeEvent> = incoming.receiveAsFlow()

        override suspend fun start() = Unit

        override suspend fun send(line: CodexJsonLine) {
            sent.send(line)
        }

        suspend fun receive(line: String) {
            incoming.send(CodexRuntimeEvent.Received(CodexJsonLine(line)))
        }

        override fun close() {
            closeCount++
            incoming.close()
        }
    }

    private fun CodexJsonLine.jsonObject() = JSON.parseToJsonElement(value).jsonObject

    private companion object {
        val JSON = Json { explicitNulls = false }
    }
}
