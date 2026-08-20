package io.github.codex_agent_labs.codexmobile.agent

public sealed interface CodexAuthenticationMethod {
    public data object ChatGptBrowser : CodexAuthenticationMethod

    public data object ChatGptDeviceCode : CodexAuthenticationMethod

    public class ApiKey(public val value: String) : CodexAuthenticationMethod {
        init {
            require(value.isNotBlank()) { "API key must not be blank" }
        }

        public override fun toString(): String = "ApiKey(**redacted**)"
    }
}
