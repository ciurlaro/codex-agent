package io.github.ciurlaro.codexmobile.agent

import okio.Path

internal actual val systemAgentFileStore: AgentFileStore = NodeAgentFileStore

private object NodeAgentFileStore : AgentFileStore {
    private val fs: dynamic = js("require('node:fs')")

    override fun isRegularFile(path: Path): Boolean =
        runCatching { fs.statSync(path.toString()).isFile() as Boolean }.getOrDefault(false)

    override fun readUtf8(path: Path): String =
        fs.readFileSync(path.toString(), "utf8") as String

    override fun size(path: Path): Long? = runCatching {
        (fs.statSync(path.toString()).size as Number).toLong()
    }.getOrNull()

    override fun readBytes(path: Path, offset: Long, byteCount: Int): ByteArray {
        require(byteCount >= 0 && offset >= 0 && offset <= Int.MAX_VALUE.toLong() - byteCount)
        val bytes = fs.readFileSync(path.toString())
        return ByteArray(byteCount) { index ->
            (bytes[offset.toInt() + index] as Number).toByte()
        }
    }

    override fun writeUtf8Atomically(path: Path, value: String) {
        val parent = checkNotNull(path.parent) { "A persisted file must have a parent directory" }
        val next = parent / ".${path.name}.next"
        fs.mkdirSync(parent.toString(), js("({ recursive: true })"))
        try {
            fs.writeFileSync(next.toString(), value, "utf8")
            fs.renameSync(next.toString(), path.toString())
        } catch (error: Throwable) {
            runCatching { fs.rmSync(next.toString(), js("({ force: true })")) }
            throw error
        }
    }

    override fun delete(path: Path) {
        fs.rmSync(path.toString(), js("({ force: true })"))
    }

    override fun clearDirectory(path: Path) {
        fs.rmSync(path.toString(), js("({ recursive: true, force: true })"))
    }
}
