package io.github.codex_agent_labs.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentElicitationValidationTest {
    @Test
    fun validatesActionsAndCompleteFormShape() {
        val required = field(
            name = "required",
            type = AgentFormFieldType.STRING,
            isRequired = true,
            defaultValue = AgentFormValue.Text("default"),
        )
        val form = elicitation(form = listOf(required))
        val url = elicitation(url = "https://example.com/authorize")
        val content = mapOf("required" to AgentFormValue.Text("answer"))

        assertTrue(form.accepts(AgentElicitationResponse(AgentElicitationAction.ACCEPT, content)))
        assertFalse(form.accepts(AgentElicitationResponse(AgentElicitationAction.ACCEPT)))
        assertFalse(
            form.accepts(
                AgentElicitationResponse(
                    AgentElicitationAction.ACCEPT,
                    content + ("unknown" to AgentFormValue.Text("value")),
                ),
            ),
        )
        assertTrue(url.accepts(AgentElicitationResponse(AgentElicitationAction.ACCEPT)))
        assertFalse(url.accepts(AgentElicitationResponse(AgentElicitationAction.ACCEPT, content)))

        listOf(AgentElicitationAction.DECLINE, AgentElicitationAction.CANCEL).forEach { action ->
            assertTrue(form.accepts(AgentElicitationResponse(action)))
            assertFalse(form.accepts(AgentElicitationResponse(action, content)))
        }
    }

    @Test
    fun validatesPrimitiveFieldTypesAndNumericBounds() {
        val string = field("string", AgentFormFieldType.STRING, isRequired = true)
        assertAccepted(string, AgentFormValue.Text("value"))
        assertRejected(string, null, AgentFormValue.Text("  "), AgentFormValue.BooleanValue(true))

        val number = field("number", AgentFormFieldType.NUMBER, minimum = 1.0, maximum = 3.0)
        listOf(1.0, 2.5, 3.0).forEach { assertAccepted(number, AgentFormValue.Number(it)) }
        listOf(0.9, 3.1, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertRejected(number, AgentFormValue.Number(it))
        }

        val integer = field("integer", AgentFormFieldType.INTEGER, minimum = -2.0, maximum = 2.0)
        listOf(-2.0, 0.0, 2.0).forEach { assertAccepted(integer, AgentFormValue.Number(it)) }
        listOf(-3.0, 1.5, 3.0).forEach { assertRejected(integer, AgentFormValue.Number(it)) }

        val boolean = field("boolean", AgentFormFieldType.BOOLEAN)
        assertAccepted(boolean, AgentFormValue.BooleanValue(false))
        assertRejected(boolean, AgentFormValue.Text("false"))
    }

    @Test
    fun validatesSelectFieldsAndOtherValues() {
        val options = listOf(AgentFormOption("alpha"), AgentFormOption("beta"))
        val single = field("single", AgentFormFieldType.SINGLE_SELECT, options = options)
        assertAccepted(single, AgentFormValue.Text("alpha"))
        assertRejected(single, AgentFormValue.Text("other"))

        val singleWithOther = single.copy(allowsOther = true)
        assertAccepted(singleWithOther, AgentFormValue.Text("other"))
        assertRejected(singleWithOther, AgentFormValue.Text(" "))

        val multi = field(
            "multi",
            AgentFormFieldType.MULTI_SELECT,
            isRequired = true,
            options = options,
        )
        assertAccepted(multi, AgentFormValue.TextList(listOf("alpha", "beta")))
        assertRejected(
            multi,
            AgentFormValue.TextList(emptyList()),
            AgentFormValue.TextList(listOf("alpha", "alpha")),
            AgentFormValue.TextList(listOf("other")),
        )

        val multiWithOther = multi.copy(allowsOther = true)
        assertAccepted(multiWithOther, AgentFormValue.TextList(listOf("alpha", "other")))
        assertRejected(multiWithOther, AgentFormValue.TextList(listOf("alpha", " ")))
    }

    @Test
    fun snapshotsCallerOwnedResponseCollections() {
        val selected = mutableListOf("alpha")
        val content = mutableMapOf<String, AgentFormValue>("choices" to AgentFormValue.TextList(selected))

        val snapshot = AgentElicitationResponse(AgentElicitationAction.ACCEPT, content).snapshot()
        selected += "beta"
        content.clear()

        assertEquals(
            mapOf("choices" to AgentFormValue.TextList(listOf("alpha"))),
            snapshot.content,
        )
    }
}

private fun elicitation(
    form: List<AgentFormField>? = null,
    url: String? = null,
): AgentElicitation = AgentElicitation(
    requestId = "request",
    serverName = "server",
    conversationId = ConversationId("conversation"),
    message = "Provide input",
    form = form,
    url = url,
)

private fun field(
    name: String,
    type: AgentFormFieldType,
    isRequired: Boolean = false,
    options: List<AgentFormOption> = emptyList(),
    defaultValue: AgentFormValue? = null,
    minimum: Double? = null,
    maximum: Double? = null,
): AgentFormField = AgentFormField(
    name = name,
    title = name,
    type = type,
    isRequired = isRequired,
    options = options,
    defaultValue = defaultValue,
    minimum = minimum,
    maximum = maximum,
)

private fun assertAccepted(field: AgentFormField, vararg values: AgentFormValue?) {
    values.forEach { value -> assertTrue(field.accepts(value), "$field should accept $value") }
}

private fun assertRejected(field: AgentFormField, vararg values: AgentFormValue?) {
    values.forEach { value -> assertFalse(field.accepts(value), "$field should reject $value") }
}
