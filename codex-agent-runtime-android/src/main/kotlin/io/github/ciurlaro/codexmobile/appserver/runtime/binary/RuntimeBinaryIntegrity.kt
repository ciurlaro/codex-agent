package io.github.ciurlaro.codexmobile.appserver.runtime

import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.buffer

fun Path.isRegularFile(): Boolean =
    FileSystem.SYSTEM.metadataOrNull(this)?.isRegularFile == true

fun Path.sha256(): String {
    val hashingSource = HashingSource.sha256(FileSystem.SYSTEM.source(this))
    hashingSource.use { source -> source.buffer().use { it.readAll(blackholeSink()) } }
    return hashingSource.hash.hex()
}
