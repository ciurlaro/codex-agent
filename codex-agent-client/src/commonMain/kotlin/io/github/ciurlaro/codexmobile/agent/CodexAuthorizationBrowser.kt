package io.github.ciurlaro.codexmobile.agent

enum class CodexAuthorizationPurpose {
    CHAT_GPT,
    EXTERNAL,
}

class CodexAuthorizationUrl private constructor(
    val value: String,
    val purpose: CodexAuthorizationPurpose,
) {
    companion object {
        fun chatGpt(value: String): CodexAuthorizationUrl =
            CodexAuthorizationUrl(validateAuthorizationUrl(value, chatGpt = true), CodexAuthorizationPurpose.CHAT_GPT)

        fun external(value: String): CodexAuthorizationUrl =
            CodexAuthorizationUrl(validateAuthorizationUrl(value, chatGpt = false), CodexAuthorizationPurpose.EXTERNAL)
    }

    override fun equals(other: Any?): Boolean =
        other is CodexAuthorizationUrl && value == other.value && purpose == other.purpose

    override fun hashCode(): Int = 31 * value.hashCode() + purpose.hashCode()

    override fun toString(): String = "CodexAuthorizationUrl(purpose=$purpose)"
}

fun interface CodexAuthorizationBrowser {
    fun open(url: CodexAuthorizationUrl): CodexAuthorizationPresentation
}

fun interface CodexAuthorizationPresentation : AutoCloseable {
    override fun close()

    companion object {
        val None: CodexAuthorizationPresentation = CodexAuthorizationPresentation {}
    }
}

internal fun requireSafeAuthUrl(value: String): String =
    CodexAuthorizationUrl.external(value).value

private fun validateAuthorizationUrl(value: String, chatGpt: Boolean): String {
    require(value.isNotBlank() && value.none { it.isWhitespace() || it.code < 0x20 } && '\\' !in value) {
        "Authorization URL is invalid"
    }
    val schemeEnd = value.indexOf("://")
    require(schemeEnd > 0) { "Authorization URL is invalid" }
    val scheme = value.substring(0, schemeEnd).lowercase()
    val authorityStart = schemeEnd + 3
    val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
        .takeIf { it >= 0 } ?: value.length
    val authority = value.substring(authorityStart, authorityEnd)
    require(authority.isNotEmpty() && '@' !in authority) { "Authorization URL is invalid" }
    val hostAndPort = parseHostAndPort(authority)
    val host = hostAndPort.first.lowercase()
    val port = hostAndPort.second
    val loopback = host in setOf("localhost", "127.0.0.1", "::1")
    require(scheme == "https" || scheme == "http" && loopback) {
        "Authorization URL is not HTTPS or loopback HTTP"
    }
    if (chatGpt) {
        require(scheme == "https" && (port == null || port == 443)) {
            "ChatGPT authorization URL must use HTTPS on the default port"
        }
        require(isOpenAiHost(host)) { "ChatGPT authorization URL uses an untrusted host" }
    }
    return value
}

private fun parseHostAndPort(authority: String): Pair<String, Int?> {
    val (host, portText) = if (authority.startsWith('[')) {
        val closing = authority.indexOf(']')
        require(closing > 1) { "Authorization URL host is invalid" }
        val suffix = authority.substring(closing + 1)
        require(suffix.isEmpty() || suffix.startsWith(':')) { "Authorization URL host is invalid" }
        authority.substring(1, closing) to if (suffix.isEmpty()) null else suffix.substring(1)
    } else {
        require(authority.count { it == ':' } <= 1) { "Authorization URL host is invalid" }
        val separator = authority.indexOf(':')
        if (separator < 0) authority to null
        else authority.substring(0, separator) to authority.substring(separator + 1)
    }
    require(host.isNotEmpty() && !host.startsWith('.') && !host.endsWith('.')) {
        "Authorization URL host is invalid"
    }
    require(host.all { it.isLetterOrDigit() || it in ".-:" }) { "Authorization URL host is invalid" }
    val port = portText?.toIntOrNull()
    require(portText == null || portText.isNotEmpty() && port != null && port in 1..65535) {
        "Authorization URL port is invalid"
    }
    return host to port
}

private fun isOpenAiHost(host: String): Boolean =
    listOf("openai.com", "chatgpt.com").any { host == it || host.endsWith(".$it") }
