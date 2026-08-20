@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.codex_agent_labs.codexmobile.agent

import kotlin.js.JsAny
import okio.Path

internal actual val systemAgentFileStore: AgentFileStore = NodeAgentFileStore

private object NodeAgentFileStore : AgentFileStore {
    override fun isRegularFile(path: Path): Boolean =
        runCatching { nodeStatSync(path.toString()).isFile() }.getOrDefault(false)

    override fun readUtf8(path: Path): String = nodeReadFileSync(path.toString(), "utf8")

    override fun size(path: Path): Long? = runCatching {
        nodeStatSync(path.toString()).size.toLong()
    }.getOrNull()

    override fun readBytes(path: Path, offset: Long, byteCount: Int): ByteArray {
        require(byteCount >= 0 && offset >= 0 && offset <= Int.MAX_VALUE.toLong() - byteCount)
        val buffer = nodeReadBufferSync(path.toString())
        check(offset <= nodeBufferLength(buffer).toLong() - byteCount)
        return ByteArray(byteCount) { index -> nodeBufferByte(buffer, offset.toInt() + index).toByte() }
    }

    override fun writeUtf8Atomically(path: Path, value: String) {
        val parent = checkNotNull(path.parent) { "A persisted file must have a parent directory" }
        val next = parent / ".${path.name}.next"
        nodeMkdirSync(parent.toString(), recursiveDirectoryOptions())
        try {
            nodeWriteFileSync(next.toString(), value, "utf8")
            nodeRenameSync(next.toString(), path.toString())
        } catch (error: Throwable) {
            runCatching { nodeRmSync(next.toString(), forceRemoveOptions()) }
            throw error
        }
    }

    override fun delete(path: Path) {
        nodeRmSync(path.toString(), forceRemoveOptions())
    }

    override fun clearDirectory(path: Path) {
        nodeRmSync(path.toString(), recursiveForceRemoveOptions())
    }
}

@JsFun("() => ({ recursive: true })")
private external fun recursiveDirectoryOptions(): JsAny

@JsFun("() => ({ force: true })")
private external fun forceRemoveOptions(): JsAny

@JsFun("() => ({ recursive: true, force: true })")
private external fun recursiveForceRemoveOptions(): JsAny

private fun nodeBufferLength(value: JsAny): Int = js("value.length")

private fun nodeBufferByte(value: JsAny, index: Int): Int = js("value[index]")
