package io.github.ciurlaro.codexmobile.agent

import kotlin.time.Clock
import okio.FileSystem
import okio.Path
import okio.ByteString.Companion.encodeUtf8

internal fun Path.isRegularFile(fileSystem: FileSystem): Boolean =
    fileSystem.metadataOrNull(this)?.isRegularFile == true

internal fun Path.readUtf8(fileSystem: FileSystem): String =
    fileSystem.read(this) {
        readByteArray().decodeToString(throwOnInvalidSequence = true)
    }

internal fun Path.writeUtf8Atomically(fileSystem: FileSystem, value: String) {
    val parent = checkNotNull(parent) { "A persisted file must have a parent directory" }
    fileSystem.createDirectories(parent)
    val next = parent / ".$name.next"
    try {
        fileSystem.write(next) { writeUtf8(value) }
        fileSystem.atomicMove(next, this)
    } catch (error: Throwable) {
        runCatching { fileSystem.delete(next) }
        throw error
    }
}

internal fun Path.deleteIfPresent(fileSystem: FileSystem) {
    fileSystem.delete(this, mustExist = false)
}

internal fun String.sha256Hex(): String = encodeUtf8().sha256().hex()

internal fun ByteArray.toHex(): String = joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

internal fun currentEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

internal fun CodexAgentClient.requireFileSystem(): FileSystem =
    checkNotNull(fileSystem) { "This agent operation requires a host file system" }

internal fun <K, V : Any> MutableMap<K, V>.putIfMissing(key: K, value: V): V? {
    val existing = this[key]
    if (existing == null) this[key] = value
    return existing
}
