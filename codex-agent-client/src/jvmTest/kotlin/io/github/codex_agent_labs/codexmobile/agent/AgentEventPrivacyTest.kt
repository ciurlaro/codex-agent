package io.github.codex_agent_labs.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals

class AgentEventPrivacyTest {
    @Test
    fun authenticationRequiredDoesNotRenderCredentials() {
        val event = AgentEvent.AuthenticationRequired(
            signInUrl = "https://example.invalid/do-not-render",
        )

        assertEquals("AuthenticationRequired", event.toString())
    }
}
