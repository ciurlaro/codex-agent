package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.agent.*
import io.github.ciurlaro.codexmobile.agent.deriveConversationTitle
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class ChatRuntimeSnapshotTest {
    @Test
    fun resumesSettingsAndSnapshotsAStructuredWebSearchTurn(): Unit = runBlocking {
        var resumeParams: JsonObject? = null
        var turnParams: JsonObject? = null
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/resume" -> {
                    resumeParams = message.params
                    server.respond(
                        message.id,
                        buildJsonObject {
                            putJsonObject("thread") { put("id", "thread-1") }
                            put("model", "runtime-model")
                        },
                    )
                }

                "turn/start" -> {
                    turnParams = message.params
                    server.respond(
                        message.id,
                        buildJsonObject { putJsonObject("turn") { put("id", "turn-1") } },
                    )
                    server.notify(
                        "item/reasoning/summaryTextDelta",
                        buildJsonObject {
                            put("threadId", "thread-1")
                            put("turnId", "turn-1")
                            put("itemId", "reasoning-1")
                            put("summaryIndex", 0)
                            put("delta", "Inspecting")
                        },
                    )
                }
            }
        }
        val client = CodexAgentClient({ process }, requestTimeoutMillis = 1_000)
        try {
            val opened = async {
                withTimeout(1_000) { client.events.filterIsInstance<AgentEvent.SessionOpened>().first() }
            }
            client.openSession(SessionId("thread-1"))
            assertEquals(
                AgentEvent.SessionOpened(SessionId("thread-1"), "runtime-model", null),
                opened.await(),
            )

            val resume = checkNotNull(resumeParams)
            assertEquals("thread-1", resume.requiredString("threadId"))
            val config = resume["config"]!!.jsonObject
            assertEquals("live", config.requiredString("web_search"))
            assertTrue(
                config["tools"]!!.jsonObject["experimental_request_user_input"]!!
                    .jsonObject.requiredBoolean("enabled"),
            )
            val features = config["features"]!!.jsonObject
            assertFalse("web_search_request" in features)
            assertFalse("web_search_cached" in features)
            assertFalse(features.requiredBoolean("standalone_web_search"))

            val reasoning = async {
                withTimeout(1_000) {
                    client.events.filterIsInstance<AgentEvent.ReasoningSummaryDelta>().first()
                }
            }
            client.sendTurn(
                SessionId("thread-1"),
                AgentTurnRequest(
                    prompt = "Find the current answer",
                    clientMessageId = "client-message-1",
                    model = "runtime-model-next",
                    effort = "xhigh",
                    capabilities = setOf(AgentCapability.WEB_SEARCH),
                    workingDirectory = "/storage/emulated/0/Documents",
                    collaborationMode = AgentCollaborationMode.PLAN,
                ),
            )

            val turn = checkNotNull(turnParams)
            assertEquals("client-message-1", turn.requiredString("clientUserMessageId"))
            assertEquals("runtime-model-next", turn.requiredString("model"))
            assertEquals("xhigh", turn.requiredString("effort"))
            assertEquals("/storage/emulated/0/Documents", turn.requiredString("cwd"))
            assertEquals("auto", turn.requiredString("summary"))
            val collaborationMode = turn["collaborationMode"]!!.jsonObject
            assertEquals("plan", collaborationMode.requiredString("mode"))
            val modeSettings = collaborationMode["settings"]!!.jsonObject
            assertEquals("runtime-model-next", modeSettings.requiredString("model"))
            assertEquals("medium", modeSettings.requiredString("reasoning_effort"))
            assertFalse("developer_instructions" in modeSettings)
            assertEquals("Inspecting", reasoning.await().text)
            val input = turn["input"]!!.jsonArray.single().jsonObject
            val expectedText = "${AgentCapability.WEB_SEARCH.promptLabel}\n\nFind the current answer"
            assertEquals(expectedText, input.requiredString("text"))
            val element = input["text_elements"]!!.jsonArray.single().jsonObject
            assertEquals(AgentCapability.WEB_SEARCH.displayLabel, element.requiredString("placeholder"))
            val range = element["byteRange"]!!.jsonObject
            assertEquals(0, range.requiredInt("start"))
            assertEquals(
                AgentCapability.WEB_SEARCH.promptLabel.toByteArray(StandardCharsets.UTF_8).size,
                range.requiredInt("end"),
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun derivesOneBoundedTitleFromExplicitNameOrFirstUserLine() {
        assertEquals("Named", deriveConversationTitle("  Named  ", "ignored"))
        assertEquals("First line", deriveConversationTitle(null, "\n First line \nsecond"))
        assertEquals("New chat", deriveConversationTitle(null, " \n "))
        assertEquals("abcd", deriveConversationTitle(null, "abcdef", maxLength = 4))
    }
}
