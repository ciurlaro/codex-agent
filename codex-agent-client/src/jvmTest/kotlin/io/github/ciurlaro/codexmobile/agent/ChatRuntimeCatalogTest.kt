package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.agent.*
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class ChatRuntimeCatalogTest {
    @Test
    fun discoversPagedModelsAndConversationHistoryFromAppServerProtocol(): Unit = runBlocking {
        val modelCursors = mutableListOf<String?>()
        val threadCursors = mutableListOf<String?>()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "model/list" -> {
                    val cursor = message.params.optionalString("cursor")
                    modelCursors += cursor
                    server.respond(
                        message.id,
                        page(
                            data = listOf(
                                if (cursor == null) model("catalog-a", "runtime-a", "Model A", "low", true)
                                else model("catalog-b", "runtime-b", "Model B", "xhigh", false),
                            ),
                            nextCursor = if (cursor == null) "models-2" else null,
                        ),
                    )
                }

                "thread/list" -> {
                    assertEquals("updated_at", message.params.requiredString("sortKey"))
                    assertEquals("desc", message.params.requiredString("sortDirection"))
                    val cursor = message.params.optionalString("cursor")
                    threadCursors += cursor
                    server.respond(
                        message.id,
                        page(
                            data = listOf(
                                if (cursor == null) thread("thread-a", "Pinned title", "ignored", 20)
                                else thread(
                                    "thread-b",
                                    null,
                                    "${AgentCapability.WEB_SEARCH.promptLabel}\n\nSecond title\nbody",
                                    10,
                                ),
                            ),
                            nextCursor = if (cursor == null) "threads-2" else null,
                        ),
                    )
                }

                "thread/read" -> {
                    assertEquals("thread-b", message.params.requiredString("threadId"))
                    assertTrue(message.params.requiredBoolean("includeTurns"))
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put(
                                "thread",
                                thread(
                                    id = "thread-b",
                                    name = null,
                                    preview = "${AgentCapability.WEB_SEARCH.promptLabel}\n\nQuestion",
                                    updatedAt = 10,
                                    turns = buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put(
                                                    "items",
                                                    buildJsonArray {
                                                        add(taggedUserMessage("user-1", "client-1", "Question"))
                                                        add(
                                                            buildJsonObject {
                                                                put("id", "reasoning-1")
                                                                put("type", "reasoning")
                                                                put("summary", buildJsonArray {
                                                                    add(JsonPrimitive("Checked the sources"))
                                                                })
                                                            },
                                                        )
                                                        add(
                                                            buildJsonObject {
                                                                put("id", "search-1")
                                                                put("type", "webSearch")
                                                                put("query", "Question")
                                                            },
                                                        )
                                                        add(
                                                            buildJsonObject {
                                                                put("id", "commentary-1")
                                                                put("type", "agentMessage")
                                                                put("phase", "commentary")
                                                                put("text", "Checking the result")
                                                            },
                                                        )
                                                        add(
                                                            buildJsonObject {
                                                                put("id", "codex-1")
                                                                put("type", "agentMessage")
                                                                put("phase", "final_answer")
                                                                put("text", "Answer")
                                                            },
                                                        )
                                                    },
                                                )
                                            },
                                        )
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val models = client.listModels()
            assertEquals(listOf(null, "models-2"), modelCursors)
            assertEquals(listOf("runtime-a", "runtime-b"), models.map { it.id })
            assertEquals(listOf("low", "medium"), models.first().supportedEfforts)
            assertEquals("low", models.first().defaultEffort)
            assertTrue(models.first().isDefault)

            val summaries = client.listSessions()
            assertEquals(listOf(null, "threads-2"), threadCursors)
            assertEquals(listOf("Pinned title", "Second title"), summaries.map { it.title })

            val conversation = client.readSession(SessionId("thread-b"))
            assertEquals("Question", conversation.summary.title)
            assertEquals(2, conversation.messages.size)
            val user = conversation.messages[0]
            assertEquals("user-1", user.id)
            assertEquals("client-1", user.clientId)
            assertEquals(AgentMessageRole.USER, user.role)
            assertEquals("Question", user.text)
            assertEquals(setOf(AgentCapability.WEB_SEARCH), user.capabilities)
            assertEquals(AgentMessageRole.CODEX, conversation.messages[1].role)
            assertEquals("Answer", conversation.messages[1].text)
            assertEquals("Checked the sources\n\nChecking the result", conversation.messages[1].reasoning)
        } finally {
            client.close()
        }
    }

}
