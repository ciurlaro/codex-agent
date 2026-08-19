package io.github.ciurlaro.codexmobile.agent

import kotlin.time.Clock
import okio.Path
import okio.ByteString.Companion.encodeUtf8

internal fun Path.isRegularFile(fileSystem: AgentFileStore): Boolean = fileSystem.isRegularFile(this)

internal fun Path.readUtf8(fileSystem: AgentFileStore): String = fileSystem.readUtf8(this)

internal fun Path.writeUtf8Atomically(fileSystem: AgentFileStore, value: String) =
    fileSystem.writeUtf8Atomically(this, value)

internal fun Path.deleteIfPresent(fileSystem: AgentFileStore) = fileSystem.delete(this)

internal fun String.sha256Hex(): String = encodeUtf8().sha256().hex()

internal fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

internal fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

internal fun CodexAgentClient.requireFileSystem(): AgentFileStore =
    checkNotNull(fileSystem) { "This agent operation requires a host file system" }

internal fun <K, V : Any> MutableMap<K, V>.putIfMissing(key: K, value: V): V? {
    val existing = this[key]
    if (existing == null) this[key] = value
    return existing
}
