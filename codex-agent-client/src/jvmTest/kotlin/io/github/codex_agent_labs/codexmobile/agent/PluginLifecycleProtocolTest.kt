package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.*
import io.github.codex_agent_labs.codexmobile.agent.*
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

class PluginLifecycleProtocolTest : SkillsPluginsProtocolTestBase() {
    @Test
    fun encodesOrderedDeduplicatedSkillAndPluginInvocations() {
        val skill = AgentInvocation.Skill("review", "/skills/review/SKILL.md")
        val plugin = AgentInvocation.Plugin("drive", "plugin://drive@openai-curated")
        val input = turnInput(
            AgentTurnRequest(
                prompt = "Check this",
                invocations = listOf(skill, plugin, skill),
            ),
        )

        assertEquals("\$review\n@drive\n\nCheck this", assertIs<UserInputTextUserInput>(input[0]).text)
        assertEquals("/skills/review/SKILL.md", assertIs<UserInputSkillUserInput>(input[1]).path)
        assertEquals("plugin://drive@openai-curated", assertIs<UserInputMentionUserInput>(input[2]).path)
    }

    @Test
    fun decodesSupportedElicitationFormsAndRejectsUnsafeUrls() {
        val elicitation = parseElicitation(
            "7",
            Json.decodeFromJsonElement(McpServerElicitationRequestParams.serializer(), buildJsonObject {
                put("serverName", "drive")
                put("threadId", "thread-1")
                put("message", "Choose")
                put("mode", "form")
                putJsonObject("requestedSchema") {
                    put("type", "object")
                    putJsonArray("required") { add(JsonPrimitive("folder")) }
                    putJsonObject("properties") {
                        putJsonObject("folder") {
                            put("type", "string")
                            put("title", "Folder")
                            put("minLength", 2)
                            put("maxLength", 10)
                        }
                        putJsonObject("email") {
                            put("type", "string")
                            put("format", "email")
                        }
                        putJsonObject("format") {
                            put("type", "string")
                            putJsonArray("enum") { add(JsonPrimitive("pdf")); add(JsonPrimitive("docx")) }
                        }
                        putJsonObject("notify") { put("type", "boolean") }
                        putJsonObject("tags") {
                            put("type", "array")
                            put("minItems", 1)
                            put("maxItems", 2)
                            putJsonObject("items") {
                                put("type", "string")
                                putJsonArray("enum") { add("a"); add("b") }
                            }
                        }
                    }
                }
            }),
        )

        val form = requireNotNull(elicitation.form)
        assertEquals(listOf(
            AgentFormFieldType.STRING,
            AgentFormFieldType.STRING,
            AgentFormFieldType.SINGLE_SELECT,
            AgentFormFieldType.BOOLEAN,
            AgentFormFieldType.MULTI_SELECT,
        ),
            form.map { it.type })
        assertTrue(form.first().isRequired)
        assertEquals(2L, form.first().minimumLength)
        assertEquals(10L, form.first().maximumLength)
        assertEquals(AgentFormStringFormat.EMAIL, form[1].format)
        assertEquals(1L, form.last().minimumSelections)
        assertEquals(2L, form.last().maximumSelections)
        assertFailsWith<IllegalArgumentException> { requireSafeAuthUrl("http://192.168.1.2/login") }
        assertEquals("http://127.0.0.1:9876/callback", requireSafeAuthUrl("http://127.0.0.1:9876/callback"))
    }

    @Test
    fun mapsPlanQuestionsToSelectableMobileFormFields() {
        val elicitation = parseUserInputRequest(
            "9",
            ToolRequestUserInputParams(
                itemId = "item-1",
                threadId = "thread-1",
                turnId = "turn-1",
                questions = listOf(
                    ToolRequestUserInputQuestion(
                        header = "Dates",
                        id = "dates",
                        question = "Are your dates flexible?",
                        isOther = true,
                        options = listOf(ToolRequestUserInputOption("Any week works", "Flexible")),
                    ),
                ),
            ),
        )

        val field = elicitation.form!!.single()
        assertEquals(AgentFormFieldType.SINGLE_SELECT, field.type)
        assertEquals("Any week works", field.options.single().description)
        assertTrue(field.allowsOther)
    }

}
