package io.github.ciurlaro.codexmobile.agent

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal fun readUtf8JsonLines(
    input: InputStream,
    maxBytes: Int = 4 * 1024 * 1024,
    onLine: (String) -> Unit,
) {
    require(maxBytes > 0)
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        for (index in 0 until count) {
            val byte = buffer[index]
            if (byte == '\n'.code.toByte()) {
                val line = bytes.toByteArray().dropTrailingCarriageReturn().decodeUtf8()
                bytes.reset()
                if (line.isNotBlank()) onLine(line)
            } else {
                check(bytes.size() < maxBytes) { "JSON-RPC frame exceeds $maxBytes bytes" }
                bytes.write(byte.toInt())
            }
        }
    }
    if (bytes.size() > 0) onLine(bytes.toByteArray().dropTrailingCarriageReturn().decodeUtf8())
}

private fun ByteArray.dropTrailingCarriageReturn(): ByteArray =
    if (lastOrNull() == '\r'.code.toByte()) copyOf(size - 1) else this

private fun ByteArray.decodeUtf8(): String = StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(this))
    .toString()
