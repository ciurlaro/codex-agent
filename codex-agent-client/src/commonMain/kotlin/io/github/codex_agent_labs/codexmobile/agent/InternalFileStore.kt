package io.github.codex_agent_labs.codexmobile.agent

import okio.FileSystem
import okio.Path

internal interface AgentFileStore {
    fun isRegularFile(path: Path): Boolean

    fun readUtf8(path: Path): String

    fun size(path: Path): Long?

    fun readBytes(path: Path, offset: Long, byteCount: Int): ByteArray

    fun writeUtf8Atomically(path: Path, value: String)

    fun delete(path: Path)

    fun clearDirectory(path: Path)
}

internal expect val systemAgentFileStore: AgentFileStore

internal fun FileSystem.asAgentFileStore(): AgentFileStore = OkioAgentFileStore(this)

private class OkioAgentFileStore(
    private val fileSystem: FileSystem,
) : AgentFileStore {
    override fun isRegularFile(path: Path): Boolean =
        fileSystem.metadataOrNull(path)?.isRegularFile == true

    override fun readUtf8(path: Path): String = fileSystem.read(path) {
        readByteArray().decodeToString(throwOnInvalidSequence = true)
    }

    override fun size(path: Path): Long? =
        fileSystem.metadataOrNull(path)?.takeIf { it.isRegularFile }?.size

    override fun readBytes(path: Path, offset: Long, byteCount: Int): ByteArray =
        fileSystem.read(path) {
            skip(offset)
            readByteArray(byteCount.toLong())
        }

    override fun writeUtf8Atomically(path: Path, value: String) {
        val parent = checkNotNull(path.parent) { "A persisted file must have a parent directory" }
        fileSystem.createDirectories(parent)
        val next = parent / ".${path.name}.next"
        try {
            fileSystem.write(next) { writeUtf8(value) }
            fileSystem.atomicMove(next, path)
        } catch (error: Throwable) {
            runCatching { fileSystem.delete(next) }
            throw error
        }
    }

    override fun delete(path: Path) {
        fileSystem.delete(path, mustExist = false)
    }

    override fun clearDirectory(path: Path) {
        fileSystem.listOrNull(path).orEmpty().forEach { fileSystem.delete(it, mustExist = false) }
    }
}
