package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexJsonLine
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntime
import io.github.codex_agent_labs.codexmobile.appserver.runtime.CodexRuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class FakeCodexRuntime(
    private val handler: (ClientMessage, FakeCodexRuntime) -> Unit,
) : CodexRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clientMessages = Channel<ClientMessage>(Channel.UNLIMITED)
    private val eventChannel = Channel<CodexRuntimeEvent>(64)
    private val running = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val closedLatch = CountDownLatch(1)
    private val requestMethods = ConcurrentHashMap<Long, String>()

    override val events: Flow<CodexRuntimeEvent> = eventChannel.receiveAsFlow()

    val isAlive: Boolean get() = running.get() && !closed.get()

    override suspend fun start() {
        check(!closed.get()) { "runtime is closed" }
        check(running.compareAndSet(false, true)) { "runtime already started" }
        scope.launch {
            for (message in clientMessages) handler(message, this@FakeCodexRuntime)
        }
    }

    override suspend fun send(line: CodexJsonLine) {
        check(isAlive) { "runtime is not running" }
        val value = kotlinx.serialization.json.Json.parseToJsonElement(line.value).jsonObject
        val message = ClientMessage(value)
        message.id?.let { id -> message.method?.let { requestMethods[id] = it } }
        clientMessages.send(message)
    }

    fun respond(id: Long?, result: JsonObject) {
        requireNotNull(id)
        val method = requestMethods.remove(id)
        val response = normalizeResponse(method, result)
        sendRaw(buildJsonObject { put("id", id); put("result", response) }.toString())
    }

    fun notify(method: String, params: JsonObject) {
        sendRaw(
            buildJsonObject {
                put("method", method)
                put("params", normalizeNotification(method, params))
            }.toString(),
        )
    }

    fun request(id: Long, method: String, params: JsonObject) {
        sendRaw(
            buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params)
            }.toString(),
        )
    }

    fun sendStderr(@Suppress("UNUSED_PARAMETER") value: String) = Unit

    fun exit(code: Int) {
        if (running.compareAndSet(true, false)) {
            runBlocking { eventChannel.send(CodexRuntimeEvent.Exited(code)) }
            check(closedLatch.await(1, TimeUnit.SECONDS)) { "client did not close exited runtime" }
        }
    }

    fun closeStdout() {
        if (running.compareAndSet(true, false)) runBlocking {
            eventChannel.send(CodexRuntimeEvent.EndOfFile)
        }
    }

    fun allClientStreamsClosed(): Boolean = closed.get()

    fun sendRaw(value: String) {
        try {
            runBlocking { eventChannel.send(CodexRuntimeEvent.Received(CodexJsonLine(value))) }
        } catch (error: ClosedSendChannelException) {
            if (!closed.get()) throw error
        }
    }

    override fun close() {
        closed.set(true)
        running.set(false)
        closedLatch.countDown()
        clientMessages.close()
        eventChannel.close()
        scope.cancel()
    }

    private fun normalizeResponse(method: String?, result: JsonObject): JsonObject = when (method) {
        "initialize" -> if (result.isEmpty()) {
            result.withDefaults(
                "codexHome" to JsonPrimitive("/tmp/codex"),
                "platformFamily" to JsonPrimitive("unix"),
                "platformOs" to JsonPrimitive("android"),
                "userAgent" to JsonPrimitive("test"),
            )
        } else {
            result
        }
        "account/read" -> {
            val account = (result["account"] as? JsonObject)?.let { value ->
                if (value["type"]?.jsonPrimitive?.content == "chatgpt") {
                    value.withDefaults("planType" to JsonPrimitive("unknown"))
                } else {
                    value
                }
            }
            result.withDefaults("requiresOpenaiAuth" to JsonPrimitive(false)).replace("account", account)
        }
        "config/value/write" -> result.withDefaults(
            "filePath" to JsonPrimitive("/tmp/config.toml"),
            "status" to JsonPrimitive("ok"),
            "version" to JsonPrimitive("test"),
        )
        "model/list" -> result.replaceArray("data", ::normalizeModel)
        "thread/list" -> result.replaceArray("data", ::normalizeThread)
        "thread/read" -> result.replaceObject("thread", ::normalizeThread)
        "thread/start", "thread/resume" -> result.replaceObject("thread", ::normalizeThread).withDefaults(
            "approvalPolicy" to JsonPrimitive("on-request"),
            "approvalsReviewer" to JsonPrimitive("user"),
            "cwd" to JsonPrimitive("/workspace"),
            "model" to JsonPrimitive("test"),
            "modelProvider" to JsonPrimitive("openai"),
            "sandbox" to buildJsonObject { put("type", "dangerFullAccess") },
        )
        "turn/start" -> result.replaceObject("turn", ::normalizeTurn)
        "plugin/list", "plugin/installed" -> result
            .replaceArray("marketplaceLoadErrors") {
                it.withDefaults("marketplacePath" to JsonPrimitive(""))
            }
            .replaceArray("marketplaces", ::normalizeMarketplace)
        "mcpServerStatus/list" -> result.replaceArray("data") {
            it.withDefaults(
                "resourceTemplates" to JsonArray(emptyList()),
                "resources" to JsonArray(emptyList()),
                "tools" to buildJsonObject {},
            )
        }
        else -> result
    }

    private fun normalizeNotification(method: String, params: JsonObject): JsonObject = when (method) {
        "item/started" -> params
            .withDefaults("startedAtMs" to JsonPrimitive(0))
            .replaceObject("item", ::normalizeThreadItem)
        "item/completed" -> params
            .withDefaults("completedAtMs" to JsonPrimitive(0))
            .replaceObject("item", ::normalizeThreadItem)
        "turn/completed" -> params.replaceObject("turn", ::normalizeTurn)
        else -> params
    }

    private fun normalizeModel(model: JsonObject): JsonObject = model.withDefaults(
        "hidden" to JsonPrimitive(false),
        "id" to (model["model"] ?: JsonPrimitive("test")),
    )

    private fun normalizeMarketplace(marketplace: JsonObject): JsonObject =
        marketplace.replaceArray("plugins") {
            val plugin = it.withDefaults(
                "source" to buildJsonObject { put("type", "remote") },
            )
            plugin.replaceObject("interface") { value ->
                value.withDefaults(
                    "screenshotUrls" to JsonArray(emptyList()),
                    "screenshots" to JsonArray(emptyList()),
                )
            }
        }

    private fun normalizeThreadItem(item: JsonObject): JsonObject = when (
        item["type"]?.jsonPrimitive?.content
    ) {
        "commandExecution" -> item.withDefaults(
            "command" to JsonPrimitive(""),
            "commandActions" to JsonArray(emptyList()),
            "cwd" to JsonPrimitive("/workspace"),
        )
        "fileChange" -> item.withDefaults("changes" to JsonArray(emptyList()))
        else -> item
    }

    private fun normalizeThread(thread: JsonObject): JsonObject {
        val id = thread["id"] ?: JsonPrimitive("thread")
        return thread.replaceArray("turns", ::normalizeTurn).withDefaults(
            "cliVersion" to JsonPrimitive("0.145.0"),
            "createdAt" to JsonPrimitive(0),
            "cwd" to JsonPrimitive("/workspace"),
            "ephemeral" to JsonPrimitive(false),
            "modelProvider" to JsonPrimitive("openai"),
            "preview" to JsonPrimitive(""),
            "conversationId" to id,
            "sessionId" to id,
            "source" to JsonPrimitive("cli"),
            "status" to buildJsonObject { put("type", "idle") },
            "turns" to JsonArray(emptyList()),
            "updatedAt" to JsonPrimitive(0),
        )
    }

    private fun normalizeTurn(turn: JsonObject): JsonObject = turn.withDefaults(
        "id" to JsonPrimitive("turn"),
        "items" to JsonArray(emptyList()),
        "status" to JsonPrimitive("inProgress"),
    )

    private fun JsonObject.withDefaults(vararg defaults: Pair<String, JsonElement>): JsonObject =
        JsonObject(defaults.toMap() + this)

    private fun JsonObject.replace(name: String, value: JsonObject?): JsonObject =
        if (value == null) this else JsonObject(this + (name to value))

    private fun JsonObject.replaceObject(name: String, transform: (JsonObject) -> JsonObject): JsonObject =
        replace(name, (get(name) as? JsonObject)?.let(transform))

    private fun JsonObject.replaceArray(name: String, transform: (JsonObject) -> JsonObject): JsonObject {
        val values = get(name) as? JsonArray ?: return this
        return JsonObject(this + (name to JsonArray(values.map { transform(it.jsonObject) })))
    }
}

internal data class ClientMessage(val objectValue: JsonObject) {
    val id: Long? = objectValue["id"]?.jsonPrimitive?.content?.toLongOrNull()
    val method: String? = objectValue["method"]?.jsonPrimitive?.content
}
