package io.github.codex_agent_labs.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodexPublicApiAdoptionTest {
    @Test
    fun clientIdentityRejectsBlankAndControlCharacters() {
        assertFailsWith<IllegalArgumentException> { CodexClientInfo("", "App", "1") }
        assertFailsWith<IllegalArgumentException> { CodexClientInfo("app", "App\nName", "1") }
        assertFailsWith<IllegalArgumentException> { CodexClientInfo("app", "App", "1\u0000") }
    }

    @Test
    fun conversationCapabilitiesCoverEveryStatus() {
        AgentConversationStatus.entries.forEach { status ->
            val ready = status == AgentConversationStatus.READY
            val recoverable = status == AgentConversationStatus.FAILED
            val state = AgentConversationState(
                status = status,
                conversationId = ConversationId("thread-1"),
                failure = CodexFailure("test", "test", recoverable).takeIf { recoverable },
            )
            assertEquals(ready || recoverable, state.canStartTurn, status.name)
            assertEquals(ready || recoverable, state.canReload, status.name)
            assertEquals(
                status == AgentConversationStatus.STARTING_TURN || status == AgentConversationStatus.RUNNING_TURN,
                state.canCancelTurn,
                status.name,
            )
        }
        assertFalse(AgentConversationState(status = AgentConversationStatus.READY).canStartTurn)
        assertFalse(
            AgentConversationState(
                AgentConversationStatus.FAILED,
                ConversationId("thread-1"),
                failure = CodexFailure("test", "test", false),
            ).canStartTurn,
        )
    }

    @Test
    fun elicitationHelpersShareOneValidatorAndSnapshotResponses() {
        val defaultSelections = mutableListOf("a")
        val elicitation = AgentElicitation(
            requestId = "request-1",
            serverName = "server",
            conversationId = ConversationId("thread-1"),
            message = "Configure",
            form = listOf(
                AgentFormField(
                    "name",
                    "Name",
                    isRequired = true,
                    type = AgentFormFieldType.STRING,
                    minimumLength = 2,
                    maximumLength = 4,
                ),
                AgentFormField(
                    "email",
                    "Email",
                    type = AgentFormFieldType.STRING,
                    format = AgentFormStringFormat.EMAIL,
                ),
                AgentFormField(
                    "date",
                    "Date",
                    type = AgentFormFieldType.STRING,
                    format = AgentFormStringFormat.DATE,
                ),
                AgentFormField(
                    "timestamp",
                    "Timestamp",
                    type = AgentFormFieldType.STRING,
                    format = AgentFormStringFormat.DATE_TIME,
                ),
                AgentFormField(
                    "count",
                    "Count",
                    type = AgentFormFieldType.INTEGER,
                    defaultValue = AgentFormValue.Number(2.0),
                    minimum = 1.0,
                    maximum = 3.0,
                ),
                AgentFormField("ratio", "Ratio", type = AgentFormFieldType.NUMBER),
                AgentFormField("enabled", "Enabled", type = AgentFormFieldType.BOOLEAN),
                AgentFormField(
                    "choice",
                    "Choice",
                    type = AgentFormFieldType.SINGLE_SELECT,
                    options = listOf(AgentFormOption("a")),
                ),
                AgentFormField(
                    "many",
                    "Many",
                    type = AgentFormFieldType.MULTI_SELECT,
                    options = listOf(AgentFormOption("a"), AgentFormOption("b"), AgentFormOption("c")),
                    minimumSelections = 1,
                    maximumSelections = 2,
                ),
                AgentFormField(
                    "default_many",
                    "Default many",
                    type = AgentFormFieldType.MULTI_SELECT,
                    options = listOf(AgentFormOption("a"), AgentFormOption("b")),
                    defaultValue = AgentFormValue.TextList(defaultSelections),
                ),
            ),
        )

        val initial = elicitation.initialValues()
        defaultSelections += "b"
        assertEquals(listOf("a"), (initial.getValue("default_many") as AgentFormValue.TextList).value)
        assertEquals(
            listOf("a", "b"),
            (elicitation.initialValues().getValue("default_many") as AgentFormValue.TextList).value,
        )
        assertEquals(AgentFormValue.Number(2.0), initial.getValue("count"))
        assertEquals(
            AgentElicitationValidationReason.MISSING_REQUIRED,
            elicitation.validate(emptyMap()).issues.single().reason,
        )
        assertEquals(
            AgentElicitationValidationReason.UNKNOWN_FIELD,
            elicitation.validate(mapOf("name" to AgentFormValue.Text("ok"), "other" to AgentFormValue.Text("x")))
                .issues.single().reason,
        )

        val invalidValues = listOf(
            "name" to AgentFormValue.Number(1.0) to AgentElicitationValidationReason.INVALID_TYPE,
            "name" to AgentFormValue.Text("x") to AgentElicitationValidationReason.BELOW_MINIMUM,
            "name" to AgentFormValue.Text("abcde") to AgentElicitationValidationReason.ABOVE_MAXIMUM,
            "email" to AgentFormValue.Text("invalid") to AgentElicitationValidationReason.INVALID_FORMAT,
            "date" to AgentFormValue.Text("2026-02-31") to AgentElicitationValidationReason.INVALID_FORMAT,
            "timestamp" to AgentFormValue.Text("2026-01-01T12:00:00+garbage") to
                AgentElicitationValidationReason.INVALID_FORMAT,
            "ratio" to AgentFormValue.Text("one") to AgentElicitationValidationReason.INVALID_TYPE,
            "enabled" to AgentFormValue.Text("true") to AgentElicitationValidationReason.INVALID_TYPE,
            "choice" to AgentFormValue.Number(1.0) to AgentElicitationValidationReason.INVALID_TYPE,
            "many" to AgentFormValue.Text("a") to AgentElicitationValidationReason.INVALID_TYPE,
            "count" to AgentFormValue.Number(Double.NaN) to AgentElicitationValidationReason.NON_FINITE_NUMBER,
            "count" to AgentFormValue.Number(0.0) to AgentElicitationValidationReason.BELOW_MINIMUM,
            "count" to AgentFormValue.Number(4.0) to AgentElicitationValidationReason.ABOVE_MAXIMUM,
            "count" to AgentFormValue.Number(1.5) to AgentElicitationValidationReason.NON_INTEGER,
            "choice" to AgentFormValue.Text("z") to AgentElicitationValidationReason.INVALID_SELECTION,
            "many" to AgentFormValue.TextList(listOf("z")) to AgentElicitationValidationReason.INVALID_SELECTION,
            "many" to AgentFormValue.TextList(emptyList()) to AgentElicitationValidationReason.BELOW_MINIMUM,
            "many" to AgentFormValue.TextList(listOf("a", "b", "c")) to
                AgentElicitationValidationReason.ABOVE_MAXIMUM,
            "many" to AgentFormValue.TextList(listOf("a", "a")) to
                AgentElicitationValidationReason.DUPLICATE_SELECTION,
        )
        invalidValues.forEach { (entry, reason) ->
            val (name, value) = entry
            val content = mapOf("name" to AgentFormValue.Text("ok"), name to value)
            val validation = elicitation.validate(content)
            assertEquals(reason, validation.issues.single().reason)
            assertEquals(
                validation.isValid,
                elicitation.accepts(AgentElicitationResponse(AgentElicitationAction.ACCEPT, content)),
            )
        }

        val selected = mutableListOf("a")
        val content = mapOf(
            "name" to AgentFormValue.Text("ok"),
            "email" to AgentFormValue.Text("user@example.com"),
            "date" to AgentFormValue.Text("2024-02-29"),
            "timestamp" to AgentFormValue.Text("2026-01-01T12:00:00.123+01:00"),
            "count" to AgentFormValue.Number(2.0),
            "ratio" to AgentFormValue.Number(0.5),
            "enabled" to AgentFormValue.BooleanValue(true),
            "choice" to AgentFormValue.Text("a"),
            "many" to AgentFormValue.TextList(selected),
        )
        val accepted = elicitation.accept(content)
        selected += "b"
        assertTrue(elicitation.accepts(accepted))
        assertEquals(listOf("a"), (accepted.content.getValue("many") as AgentFormValue.TextList).value)
        assertEquals(AgentElicitationResponse(AgentElicitationAction.DECLINE), AgentElicitationResponse.decline())
        assertEquals(AgentElicitationResponse(AgentElicitationAction.CANCEL), AgentElicitationResponse.cancel())
        assertFailsWith<IllegalArgumentException> {
            elicitation.accept(mapOf("name" to AgentFormValue.Text("")))
        }

        val urlOnly = AgentElicitation(
            requestId = "request-url",
            serverName = "server",
            conversationId = ConversationId("thread-1"),
            message = "Authorize",
            url = "https://example.com",
        )
        assertTrue(urlOnly.validate(emptyMap()).isValid)
        assertTrue(urlOnly.accepts(AgentElicitationResponse(AgentElicitationAction.ACCEPT)))
        assertFalse(
            urlOnly.accepts(
                AgentElicitationResponse(
                    AgentElicitationAction.ACCEPT,
                    mapOf("unexpected" to AgentFormValue.Text("value")),
                ),
            ),
        )
    }
}
