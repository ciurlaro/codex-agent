package io.github.codex_agent_labs.codexmobile.app.runtime.bootstrap

import io.github.codex_agent_labs.codexmobile.appserver.runtime.ConnectProxyDecision
import io.github.codex_agent_labs.codexmobile.appserver.runtime.ConnectProxyPolicy
import io.github.codex_agent_labs.codexmobile.appserver.runtime.isPublicProxyAddress
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

internal class LoopbackConnectProxy(password: String) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val policy = ConnectProxyPolicy(password)
    private val server = ServerSocket()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val acceptor = Executors.newSingleThreadExecutor()
    private val workers = Executors.newFixedThreadPool(MAX_WORKERS)
    private val connectionPermits = Semaphore(MAX_CONNECTIONS)
    val url: String

    init {
        server.bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), CONNECTION_BACKLOG)
        url = "http://codex:$password@$LOOPBACK:${server.localPort}"
        acceptor.execute(::acceptConnections)
    }

    private fun acceptConnections() {
        while (!closed.get()) {
            val socket = runCatching { server.accept() }.getOrNull() ?: return
            sockets += socket
            if (!connectionPermits.tryAcquire()) {
                closePair(socket, null)
                continue
            }
            runCatching {
                workers.execute {
                    try {
                        handle(socket)
                    } finally {
                        connectionPermits.release()
                    }
                }
            }.onFailure {
                connectionPermits.release()
                closePair(socket, null)
            }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        var tunnelEstablished = false
        try {
            client.soTimeout = CONNECT_TIMEOUT_MILLIS
            when (val decision = policy.authorize(readHeaders(client.inputStream))) {
                is ConnectProxyDecision.Rejected -> {
                    respond(client, decision.status, decision.reason)
                    return
                }
                is ConnectProxyDecision.Allowed -> {
                    val request = decision.request
                    val addresses = runCatching {
                        InetAddress.getAllByName(request.host).toList()
                    }.getOrNull()
                    if (addresses.isNullOrEmpty() || addresses.any { !it.address.isPublicProxyAddress() }) {
                        respond(client, 403, "Forbidden")
                        return
                    }
                    upstream = Socket().apply {
                        connect(InetSocketAddress(addresses.first(), request.port), CONNECT_TIMEOUT_MILLIS)
                    }
                }
            }
            val connected = checkNotNull(upstream)
            sockets += connected
            client.soTimeout = 0
            respond(client, 200, "Connection Established")
            tunnelEstablished = true
            val reverse = workers.submit {
                try {
                    connected.inputStream.copyTo(client.outputStream)
                    client.outputStream.flush()
                    runCatching { client.shutdownOutput() }
                } catch (error: Exception) {
                    closePair(client, connected)
                    throw error
                }
            }
            try {
                client.inputStream.copyTo(connected.outputStream)
                connected.outputStream.flush()
                runCatching { connected.shutdownOutput() }
                reverse.get()
            } finally {
                reverse.cancel(true)
            }
        } catch (_: Exception) {
            if (!tunnelEstablished) runCatching { respond(client, 502, "Bad Gateway") }
        } finally {
            closePair(client, upstream)
        }
    }

    private fun readHeaders(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        var matched = 0
        while (bytes.size() < MAX_HEADER_BYTES) {
            val byte = input.read()
            check(byte >= 0) { "Proxy request ended before its headers" }
            bytes.write(byte)
            matched = when {
                byte == HEADER_END[matched].toInt() -> matched + 1
                byte == HEADER_END[0].toInt() -> 1
                else -> 0
            }
            if (matched == HEADER_END.size) {
                return bytes.toString(StandardCharsets.ISO_8859_1.name())
            }
        }
        error("Proxy request headers exceed the byte limit")
    }

    private fun respond(socket: Socket, status: Int, reason: String) {
        val authentication = if (status == 407) {
            "Proxy-Authenticate: Basic realm=\"Codex Mobile\"\r\n"
        } else {
            ""
        }
        socket.outputStream.write(
            "HTTP/1.1 $status $reason\r\n$authentication\r\n"
                .toByteArray(StandardCharsets.US_ASCII),
        )
        socket.outputStream.flush()
    }

    private fun closePair(first: Socket, second: Socket?) {
        sockets -= first
        second?.let { sockets -= it }
        runCatching { first.close() }
        runCatching { second?.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        sockets.toList().forEach { runCatching { it.close() } }
        acceptor.shutdownNow()
        workers.shutdownNow()
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MILLIS = 20_000
        const val MAX_HEADER_BYTES = 16 * 1024
        const val MAX_WORKERS = 8
        const val MAX_CONNECTIONS = 4
        const val CONNECTION_BACKLOG = 8
        val HEADER_END = byteArrayOf(
            '\r'.code.toByte(),
            '\n'.code.toByte(),
            '\r'.code.toByte(),
            '\n'.code.toByte(),
        )
    }
}
