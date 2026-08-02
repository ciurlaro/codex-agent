package io.github.ciurlaro.codexmobile.agent

import okio.FileSystem
import okio.Path
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class TurnInputMetadata(
    val clientMessageId: String,
    val invocations: List<AgentInvocation>,
)

internal class TurnInputMetadataStore(
    private val directory: Path?,
    private val fileSystem: FileSystem?,
) {
    private val lock = Mutex()

    suspend fun read(threadId: String): Map<String, List<AgentInvocation>> =
        lock.withLock { readUnlocked(threadId) }

    suspend fun upsert(threadId: String, metadata: TurnInputMetadata) = lock.withLock {
        if (metadata.invocations.isEmpty()) return@withLock
        val destination = file(threadId) ?: return@withLock
        val entries = readUnlocked(threadId).toMutableMap().apply {
            put(metadata.clientMessageId, metadata.invocations.distinctBy(AgentInvocation::key))
        }
        destination.writeUtf8Atomically(checkNotNull(fileSystem), buildJsonArray {
            entries.forEach { (clientMessageId, invocations) ->
                add(buildJsonObject {
                    put("clientMessageId", clientMessageId)
                    put("invocations", buildJsonArray {
                        invocations.forEach { invocation ->
                            add(buildJsonObject {
                                when (invocation) {
                                    is AgentInvocation.Skill -> {
                                        put("type", "skill")
                                        put("name", invocation.name)
                                        put("path", invocation.path)
                                    }
                                    is AgentInvocation.Plugin -> {
                                        put("type", "mention")
                                        put("name", invocation.name)
                                        put("path", invocation.uri)
                                    }
                                }
                            })
                        }
                    })
                })
            }
        }.toString())
    }

    suspend fun delete(threadId: String) = lock.withLock {
        file(threadId)?.deleteIfPresent(checkNotNull(fileSystem))
    }

    private fun readUnlocked(threadId: String): Map<String, List<AgentInvocation>> {
        val file = file(threadId) ?: return emptyMap()
        val fileSystem = checkNotNull(fileSystem)
        if (!file.isRegularFile(fileSystem)) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(file.readUtf8(fileSystem)).jsonArray.associate { raw ->
                val item = raw.jsonObject
                item.getValue("clientMessageId").jsonPrimitive.content to
                    item.getValue("invocations").jsonArray.mapNotNull { parseInvocation(it.jsonObject) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun file(threadId: String): Path? =
        directory?.let { it / "${threadId.sha256Hex()}.json" }
}
