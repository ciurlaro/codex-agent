package io.github.ciurlaro.codexmobile.agent

sealed interface CodexAuthenticationMethod {
    data object ChatGptBrowser : CodexAuthenticationMethod

    data object ChatGptDeviceCode : CodexAuthenticationMethod

    class ApiKey(val value: String) : CodexAuthenticationMethod {
        init {
            require(value.isNotBlank()) { "API key must not be blank" }
        }

        override fun toString(): String = "ApiKey(**redacted**)"
    }
}
