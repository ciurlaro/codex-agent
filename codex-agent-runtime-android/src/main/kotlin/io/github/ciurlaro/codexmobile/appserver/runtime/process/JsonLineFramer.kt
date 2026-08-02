package io.github.ciurlaro.codexmobile.appserver.runtime

import okio.Buffer

class JsonLineFramer(
    private val maxBytes: Int = MAX_RECEIVED_MESSAGE_BYTES,
) {
    private val pending = Buffer()
    private var failed = false

    init {
        require(maxBytes > 0)
    }

    suspend fun accept(
        bytes: ByteArray,
        count: Int = bytes.size,
        onLine: suspend (String) -> Unit,
    ) {
        if (failed) return
        require(count in 0..bytes.size)
        try {
            for (index in 0 until count) {
                if (bytes[index] == '\n'.code.toByte()) {
                    emit(onLine)
                } else {
                    check(pending.size < maxBytes) {
                        "JSON-RPC frame exceeds $maxBytes bytes"
                    }
                    pending.writeByte(bytes[index].toInt())
                }
            }
        } catch (error: Throwable) {
            failed = true
            pending.clear()
            throw error
        }
    }

    suspend fun finish(onLine: suspend (String) -> Unit) {
        if (failed || pending.size == 0L) return
        try {
            emit(onLine)
        } catch (error: Throwable) {
            failed = true
            pending.clear()
            throw error
        }
    }

    private suspend fun emit(onLine: suspend (String) -> Unit) {
        val frame = pending.readByteArray().let { bytes ->
            if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.copyOf(bytes.size - 1) else bytes
        }
        if (frame.isNotEmpty()) onLine(frame.decodeToString(throwOnInvalidSequence = true))
    }
}
