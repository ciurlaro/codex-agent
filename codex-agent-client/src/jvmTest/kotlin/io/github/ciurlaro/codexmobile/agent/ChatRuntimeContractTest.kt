package io.github.ciurlaro.codexmobile.agent

import okio.FileSystem
import io.github.ciurlaro.codexmobile.agent.AgentCapability
import io.github.ciurlaro.codexmobile.agent.AgentCollaborationMode
import io.github.ciurlaro.codexmobile.agent.AgentElicitationAction
import io.github.ciurlaro.codexmobile.agent.AgentElicitationResponse
import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.AgentFormValue
import io.github.ciurlaro.codexmobile.agent.AgentHookTrustStatus
import io.github.ciurlaro.codexmobile.agent.AgentInvocation
import io.github.ciurlaro.codexmobile.agent.AgentMessageRole
import io.github.ciurlaro.codexmobile.agent.AgentRuntimeSettings
import io.github.ciurlaro.codexmobile.agent.AgentTurnRequest
import io.github.ciurlaro.codexmobile.agent.SessionId
import io.github.ciurlaro.codexmobile.agent.deriveConversationTitle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
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

class ChatRuntimeContractTest {
    @Test
    fun restoresOnlyInvocationsRecordedByTheOriginalStructuredMessage(): Unit = runBlocking {
        val directory = Files.createTempDirectory("turn-inputs").toFile()
        val plugin = AgentInvocation.Plugin(
            name = "google-contacts",
            uri = "plugin://google-contacts@openai-curated",
        )
        val store = TurnInputMetadataStore(directory.absolutePath.toPath(), FileSystem.SYSTEM)
        store.upsert("thread-1", TurnInputMetadata("client-chip", listOf(plugin)))

        val messages = conversationMessages(
            listOf(
                plainUserMessage("user-chip", "client-chip", "@google-contacts\n\nFind a contact"),
                plainUserMessage("user-text", "client-text", "@someone\n\nThis is regular text"),
                plainUserMessage("user-plan", "codex-mobile:plan:client-plan", "Plan a trip"),
            ),
            store.read("thread-1"),
        )

        assertEquals("Find a contact", messages[0].text)
        assertEquals(listOf(plugin), messages[0].invocations)
        assertEquals("@someone\n\nThis is regular text", messages[1].text)
        assertTrue(messages[1].invocations.isEmpty())
        assertEquals(AgentCollaborationMode.PLAN, messages[2].collaborationMode)
    }

    @Test
    fun planInputRequestsUseTheExistingElicitationFlow(): Unit = runBlocking {
        val answer = CompletableDeferred<JsonObject>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/resume" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-1") }
                })
                null -> if (message.id == 91L) {
                    answer.complete(message.objectValue.getValue("result").jsonObject)
                }
            }
        }
        CodexAgentClient({ process }, requestTimeoutMillis = 1_000).use { client ->
            client.openSession(SessionId("thread-1"))
            val requested = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.ElicitationRequested>().first() }
            }
            process.request(91, "item/tool/requestUserInput", buildJsonObject {
                put("itemId", "item-1")
                put("threadId", "thread-1")
                put("turnId", "turn-1")
                putJsonArray("questions") {
                    add(buildJsonObject {
                        put("header", "Dates")
                        put("id", "dates")
                        put("question", "Are your travel dates flexible?")
                        put("isOther", true)
                        putJsonArray("options") {
                            add(buildJsonObject {
                                put("label", "Flexible")
                                put("description", "Any week works")
                            })
                            add(buildJsonObject {
                                put("label", "Fixed")
                                put("description", "Use exact dates")
                            })
                        }
                    })
                }
            })

            val elicitation = requested.await()
            assertEquals("Plan", elicitation.elicitation.serverName)
            assertTrue(elicitation.elicitation.form!!.single().allowOther)
            client.resolveElicitation(
                elicitation.elicitation.requestId,
                AgentElicitationResponse(
                    AgentElicitationAction.ACCEPT,
                    mapOf("dates" to AgentFormValue.Text("Flexible")),
                ),
            )
            assertEquals(
                "Flexible",
                answer.await()["answers"]!!.jsonObject["dates"]!!.jsonObject["answers"]!!
                    .jsonArray.single().jsonPrimitive.content,
            )
        }
    }

    @Test
    fun listsHooksAndWritesOnlyTheSelectedHookState(): Unit = runBlocking {
        val writes = mutableListOf<JsonObject>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "hooks/list" -> server.respond(
                    message.id,
                    buildJsonObject {
                        put("data", buildJsonArray {
                            add(buildJsonObject {
                                put("cwd", "/workspace")
                                put("warnings", buildJsonArray {})
                                put("errors", buildJsonArray {})
                                put("hooks", buildJsonArray {
                                    add(buildJsonObject {
                                        put("currentHash", "sha256:current")
                                        put("displayOrder", 0)
                                        put("enabled", false)
                                        put("eventName", "preToolUse")
                                        put("handlerType", "command")
                                        put("isManaged", false)
                                        put("key", "project-hook")
                                        put("source", "project")
                                        put("sourcePath", "/workspace/.codex/hooks.json")
                                        put("timeoutSec", 10)
                                        put("trustStatus", "untrusted")
                                        put("command", "./check")
                                    })
                                })
                            })
                        })
                    },
                )
                "config/batchWrite" -> {
                    writes += message.params
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("filePath", "/data/user/0/app/files/.codex/config.toml")
                            put("status", "ok")
                            put("version", "1")
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val hook = client.listHooks("/workspace").hooks.single()
            assertEquals(AgentHookTrustStatus.UNTRUSTED, hook.trustStatus)
            assertEquals("./check", hook.command)

            client.trustHook(hook.key, hook.currentHash)
            client.setHookEnabled(hook.key, true)

            assertEquals(2, writes.size)
            writes.forEach { assertEquals("hooks.state", it["edits"]!!.jsonArray.single().jsonObject.requiredString("keyPath")) }
            assertEquals(
                "sha256:current",
                writes[0]["edits"]!!.jsonArray.single().jsonObject["value"]!!.jsonObject
                    .getValue("project-hook").jsonObject.requiredString("trusted_hash"),
            )
            assertTrue(
                writes[1]["edits"]!!.jsonArray.single().jsonObject["value"]!!.jsonObject
                    .getValue("project-hook").jsonObject.requiredBoolean("enabled"),
            )
        } finally {
            client.close()
        }
    }

}
