package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.io.encoding.Base64

internal data class ConnectProxyRequest(
    val host: String,
    val port: Int,
)

internal sealed interface ConnectProxyDecision {
    data class Allowed(val request: ConnectProxyRequest) : ConnectProxyDecision
    data class Rejected(val status: Int, val reason: String) : ConnectProxyDecision
}

internal class ConnectProxyPolicy(password: String) {
    private val authorization =
        "Basic " + Base64.Default.encode("codex:$password".encodeToByteArray())

    init {
        require(password.isNotBlank() && password.none(Char::isWhitespace))
    }

    fun authorize(headers: String): ConnectProxyDecision {
        val lines = headers.removeSuffix("\r\n\r\n").split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: emptyList()
        if (requestLine.size != 3 || requestLine[2] !in HTTP_VERSIONS) {
            return ConnectProxyDecision.Rejected(400, "Bad Request")
        }
        if (requestLine[0] != "CONNECT") {
            return ConnectProxyDecision.Rejected(405, "Method Not Allowed")
        }
        val parsedHeaders = lines.drop(1).mapNotNull { line ->
            if (line.isEmpty() || line.first().isWhitespace() || ':' !in line) return@mapNotNull null
            line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
        }
        if (parsedHeaders.size != lines.size - 1) {
            return ConnectProxyDecision.Rejected(400, "Bad Request")
        }
        val supplied = parsedHeaders
            .filter { it.first == "proxy-authorization" }
            .map { it.second }
            .singleOrNull()
        if (supplied != authorization) {
            return ConnectProxyDecision.Rejected(407, "Proxy Authentication Required")
        }
        val (host, port) = parseAuthority(requestLine[1])
            ?: return ConnectProxyDecision.Rejected(403, "Forbidden")
        if (port != TLS_PORT) return ConnectProxyDecision.Rejected(403, "Forbidden")
        return ConnectProxyDecision.Allowed(ConnectProxyRequest(host, port))
    }

    private fun parseAuthority(authority: String): Pair<String, Int>? {
        val separator = authority.lastIndexOf(':')
        if (separator <= 0 || separator == authority.lastIndex) return null
        val rawHost = authority.substring(0, separator)
        val port = authority.substring(separator + 1).toIntOrNull() ?: return null
        val host = when {
            rawHost.startsWith('[') && rawHost.endsWith(']') ->
                rawHost.substring(1, rawHost.lastIndex)
            ':' !in rawHost -> rawHost
            else -> return null
        }
        if (host.isBlank() || host.any { it.isWhitespace() || it in "/@?#" }) return null
        return host to port
    }

    private companion object {
        const val TLS_PORT = 443
        val HTTP_VERSIONS = setOf("HTTP/1.0", "HTTP/1.1")
    }
}

internal fun ByteArray.isPublicProxyAddress(): Boolean = when (size) {
    4 -> isPublicIpv4()
    16 -> when {
        take(10).all { it == 0.toByte() } &&
            this[10] == 0xff.toByte() &&
            this[11] == 0xff.toByte() -> copyOfRange(12, 16).isPublicIpv4()
        all { it == 0.toByte() } ||
            take(15).all { it == 0.toByte() } && last() == 1.toByte() -> false
        this[0].toInt() and 0xfe == 0xfc -> false
        this[0] == 0xfe.toByte() && this[1].toInt() and 0xc0 == 0x80 -> false
        this[0] == 0xff.toByte() -> false
        take(4).map { it.toInt() and 0xff } == listOf(0x20, 0x01, 0x0d, 0xb8) -> false
        else -> true
    }
    else -> false
}

private fun ByteArray.isPublicIpv4(): Boolean {
    val octets = map { it.toInt() and 0xff }
    return when {
        octets[0] == 0 || octets[0] == 10 || octets[0] == 127 || octets[0] >= 224 -> false
        octets[0] == 100 && octets[1] in 64..127 -> false
        octets[0] == 169 && octets[1] == 254 -> false
        octets[0] == 172 && octets[1] in 16..31 -> false
        octets[0] == 192 && octets[1] == 168 -> false
        octets[0] == 192 && octets[1] == 0 && octets[2] in setOf(0, 2) -> false
        octets[0] == 198 && octets[1] in 18..19 -> false
        octets[0] == 198 && octets[1] == 51 && octets[2] == 100 -> false
        octets[0] == 203 && octets[1] == 0 && octets[2] == 113 -> false
        else -> true
    }
}
