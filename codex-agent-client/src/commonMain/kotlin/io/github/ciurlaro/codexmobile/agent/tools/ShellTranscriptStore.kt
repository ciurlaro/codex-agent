package io.github.ciurlaro.codexmobile.agent

import okio.FileSystem
import okio.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal data class ShellTranscript(
    val turnId: String,
    val itemId: String,
    val command: String,
    val output: String,
    val exitCode: Int?,
)

internal class ShellTranscriptStore(
    private val directory: Path?,
    private val fileSystem: FileSystem?,
) {
    private val lock = Mutex()

    suspend fun read(threadId: String): List<ShellTranscript> =
        lock.withLock { readUnlocked(threadId) }

    suspend fun upsert(threadId: String, transcript: ShellTranscript) = lock.withLock {
        val destination = file(threadId) ?: return@withLock
        val transcripts = readUnlocked(threadId).filterNot { it.itemId == transcript.itemId } + transcript
        destination.writeUtf8Atomically(checkNotNull(fileSystem), buildJsonArray {
            transcripts.forEach { item ->
                add(buildJsonObject {
                    put("turnId", item.turnId)
                    put("itemId", item.itemId)
                    put("command", item.command)
                    put("output", item.output)
                    put("exitCode", item.exitCode?.let(::JsonPrimitive) ?: JsonNull)
                })
            }
        }.toString())
    }

    suspend fun delete(threadId: String) = lock.withLock {
        file(threadId)?.deleteIfPresent(checkNotNull(fileSystem))
    }

    private fun readUnlocked(threadId: String): List<ShellTranscript> {
        val file = file(threadId) ?: return emptyList()
        val fileSystem = checkNotNull(fileSystem)
        if (!file.isRegularFile(fileSystem)) return emptyList()
        return runCatching {
            Json.parseToJsonElement(file.readUtf8(fileSystem)).jsonArray.map { raw ->
                val item = raw.jsonObject
                ShellTranscript(
                    turnId = item.getValue("turnId").jsonPrimitive.content,
                    itemId = item.getValue("itemId").jsonPrimitive.content,
                    command = item.getValue("command").jsonPrimitive.content,
                    output = item.getValue("output").jsonPrimitive.content,
                    exitCode = item["exitCode"]?.jsonPrimitive?.longOrNull?.toInt(),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun file(threadId: String): Path? =
        directory?.let { it / "${threadId.sha256Hex()}.json" }
}
