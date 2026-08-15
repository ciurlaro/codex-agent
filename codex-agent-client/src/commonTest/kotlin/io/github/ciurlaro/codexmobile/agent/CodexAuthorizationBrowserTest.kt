package io.github.ciurlaro.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CodexAuthorizationBrowserTest {
    @Test
    fun chatGptUrlsRequireTrustedHttpsHostsAndDefaultPort() {
        assertEquals(
            "https://auth.openai.com/authorize?client=codex",
            CodexAuthorizationUrl.chatGpt("https://auth.openai.com/authorize?client=codex").value,
        )
        CodexAuthorizationUrl.chatGpt("https://chatgpt.com/")
        CodexAuthorizationUrl.chatGpt("https://login.chatgpt.com:443/")

        listOf(
            "http://auth.openai.com/",
            "https://openai.com.evil.example/",
            "https://evilopenai.com/",
            "https://user@openai.com/",
            "https://openai.com:444/",
            "https://openai.com:/",
            "https://openai.com./",
        ).forEach { assertFailsWith<IllegalArgumentException> { CodexAuthorizationUrl.chatGpt(it) } }
    }

    @Test
    fun externalUrlsAllowHttpsAndOnlyLoopbackHttp() {
        CodexAuthorizationUrl.external("https://accounts.example.com/oauth")
        CodexAuthorizationUrl.external("http://localhost:8787/callback")
        CodexAuthorizationUrl.external("http://127.0.0.1:8787/callback")
        CodexAuthorizationUrl.external("http://[::1]:8787/callback")

        listOf(
            "http://192.168.1.2/login",
            "ftp://accounts.example.com/login",
            "https://user@accounts.example.com/login",
            "https://accounts.example.com:0/login",
            "https://accounts.example.com:65536/login",
            "https://accounts.example.com:/login",
            "https://accounts.example.com\\@evil.example/login",
            "https://accounts.example.com/space here",
        ).forEach { assertFailsWith<IllegalArgumentException> { CodexAuthorizationUrl.external(it) } }
    }

    @Test
    fun authorizationUrlsDoNotLeakTheirValueFromToString() {
        val url = CodexAuthorizationUrl.external("https://accounts.example.com/oauth?secret=value")
        assertEquals("CodexAuthorizationUrl(purpose=EXTERNAL)", url.toString())
    }
}
