package io.github.ciurlaro.codexmobile.appserver.runtime.host

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val RUNTIME_MANIFEST_NAME = "codex-runtime-manifest.json"
internal const val RUNTIME_LICENSE_NAME = "openai-codex-LICENSE.txt"
internal const val RUNTIME_NOTICE_NAME = "openai-codex-NOTICE.txt"

internal data class RuntimeBundleManifest(
    val libraryVersion: String,
    val appServerVersion: String,
    val target: String,
    val classifier: String,
    val members: List<RuntimeBundleMember>,
)

internal data class RuntimeBundleMember(
    val name: String,
    val size: Long,
    val sha256: String,
    val executable: Boolean,
)

internal data class RuntimeZipMember(
    val name: String,
    val size: Long,
    val compression: Int,
    val compressedSize: Int,
    val dataOffset: Int,
)

internal fun parseRuntimeBundleManifest(bytes: ByteArray): RuntimeBundleManifest {
    require(bytes.size in 1..MAX_MANIFEST_BYTES) { "Runtime manifest size is invalid" }
    val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    require(root.keys == setOf(
        "schemaVersion", "libraryVersion", "appServerVersion", "target", "classifier", "members",
    )) { "Runtime manifest fields are invalid" }
    fun string(name: String): String = root.getValue(name).jsonPrimitive.content
    val schemaVersion = root.getValue("schemaVersion").jsonPrimitive
    require(!schemaVersion.isString && schemaVersion.content == "1") {
        "Unsupported runtime manifest schema"
    }
    val members = root.getValue("members").jsonArray.map { value ->
        val member = value.jsonObject
        require(member.keys == setOf("name", "size", "sha256", "executable")) {
            "Runtime manifest member fields are invalid"
        }
        val size = member.getValue("size").jsonPrimitive
        val executable = member.getValue("executable").jsonPrimitive
        require(!size.isString && !executable.isString) { "Runtime manifest member types are invalid" }
        RuntimeBundleMember(
            name = member.getValue("name").jsonPrimitive.content,
            size = size.content.toLong(),
            sha256 = member.getValue("sha256").jsonPrimitive.content,
            executable = executable.boolean,
        )
    }
    return RuntimeBundleManifest(
        libraryVersion = string("libraryVersion"),
        appServerVersion = string("appServerVersion"),
        target = string("target"),
        classifier = string("classifier"),
        members = members,
    )
}

internal fun inspectRuntimeZip(bytes: ByteArray): List<RuntimeZipMember> {
    require(bytes.size in MIN_ZIP_BYTES..MAX_ARCHIVE_BYTES) { "Runtime ZIP size is invalid" }
    val eocd = (bytes.size - MIN_EOCD_BYTES downTo maxOf(0, bytes.size - MAX_EOCD_BYTES))
        .firstOrNull { bytes.u32(it) == EOCD_SIGNATURE }
        ?: error("Runtime ZIP end record is missing")
    require(bytes.u16(eocd + 4) == 0 && bytes.u16(eocd + 6) == 0) {
        "Multi-disk runtime ZIPs are unsupported"
    }
    val entries = bytes.u16(eocd + 10)
    require(entries == bytes.u16(eocd + 8) && entries in 1..MAX_MEMBERS) {
        "Runtime ZIP entry count is invalid"
    }
    val centralSize = bytes.u32(eocd + 12).checkedInt("central directory size")
    val centralOffset = bytes.u32(eocd + 16).checkedInt("central directory offset")
    val commentSize = bytes.u16(eocd + 20)
    require(commentSize == 0 && eocd + MIN_EOCD_BYTES == bytes.size) { "Runtime ZIP comment is unsupported" }
    require(centralOffset + centralSize == eocd) { "Runtime ZIP central directory is invalid" }

    val names = mutableSetOf<String>()
    var cursor = centralOffset
    val result = buildList {
        repeat(entries) {
            require(bytes.u32(cursor) == CENTRAL_SIGNATURE) { "Runtime ZIP central entry is invalid" }
            val flags = bytes.u16(cursor + 8)
            val compression = bytes.u16(cursor + 10)
            val compressedSize = bytes.u32(cursor + 20)
            val size = bytes.u32(cursor + 24)
            val nameSize = bytes.u16(cursor + 28)
            val extraSize = bytes.u16(cursor + 30)
            val entryCommentSize = bytes.u16(cursor + 32)
            val diskStart = bytes.u16(cursor + 34)
            val externalAttributes = bytes.u32(cursor + 38)
            val localOffset = bytes.u32(cursor + 42).checkedInt("local entry offset")
            val end = cursor + CENTRAL_HEADER_BYTES + nameSize + extraSize + entryCommentSize
            require(end <= eocd && flags and ENCRYPTED_FLAG == 0 && compression in setOf(STORED, DEFLATED)) {
                "Runtime ZIP entry is unsupported"
            }
            require(diskStart == 0 && compressedSize <= MAX_ARCHIVE_BYTES.toLong() && size <= MAX_MEMBER_BYTES) {
                "Runtime ZIP entry size is invalid"
            }
            val name = bytes.ascii(cursor + CENTRAL_HEADER_BYTES, nameSize)
            require(name.isSafeRootMember() && names.add(name)) { "Runtime ZIP has an unsafe or duplicate member" }
            require((externalAttributes ushr 16).toInt() and FILE_TYPE_MASK != SYMLINK_TYPE) {
                "Runtime ZIP symbolic links are forbidden"
            }
            val dataOffset = validateLocalEntry(bytes, localOffset, name, compression, centralOffset)
            val compressedSizeInt = compressedSize.checkedInt("compressed member size")
            require(dataOffset + compressedSizeInt <= centralOffset) { "Runtime ZIP member data is invalid" }
            add(RuntimeZipMember(name, size, compression, compressedSizeInt, dataOffset))
            cursor = end
        }
    }
    require(cursor == eocd) { "Runtime ZIP central directory length is invalid" }
    return result
}

private fun validateLocalEntry(
    bytes: ByteArray,
    offset: Int,
    expectedName: String,
    expectedCompression: Int,
    centralOffset: Int,
): Int {
    require(offset >= 0 && offset + LOCAL_HEADER_BYTES <= centralOffset && bytes.u32(offset) == LOCAL_SIGNATURE) {
        "Runtime ZIP local entry is invalid"
    }
    val flags = bytes.u16(offset + 6)
    val compression = bytes.u16(offset + 8)
    val nameSize = bytes.u16(offset + 26)
    val extraSize = bytes.u16(offset + 28)
    require(flags and ENCRYPTED_FLAG == 0 && compression == expectedCompression) {
        "Runtime ZIP local entry does not match its central entry"
    }
    val end = offset + LOCAL_HEADER_BYTES + nameSize + extraSize
    require(end <= centralOffset && bytes.ascii(offset + LOCAL_HEADER_BYTES, nameSize) == expectedName) {
        "Runtime ZIP local member name is invalid"
    }
    return end
}

private fun String.isSafeRootMember(): Boolean =
    isNotBlank() && this != "." && this != ".." && '/' !in this && '\\' !in this && ':' !in this

private fun ByteArray.ascii(offset: Int, size: Int): String {
    require(offset >= 0 && size > 0 && offset + size <= this.size) { "Runtime ZIP member name is invalid" }
    val value = copyOfRange(offset, offset + size)
    require(value.all { (it.toInt() and 0xff) in 0x21..0x7e }) { "Runtime ZIP member name is invalid" }
    return value.decodeToString()
}

private fun ByteArray.u16(offset: Int): Int {
    require(offset >= 0 && offset + 2 <= size) { "Runtime ZIP is truncated" }
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

private fun Long.checkedInt(name: String): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "Runtime ZIP $name is invalid" }
    return toInt()
}

private const val MIN_ZIP_BYTES = 22
private const val MIN_EOCD_BYTES = 22
private const val MAX_EOCD_BYTES = 65_557
private const val CENTRAL_HEADER_BYTES = 46
private const val LOCAL_HEADER_BYTES = 30
private const val MAX_MANIFEST_BYTES = 64 * 1024
private const val MAX_ARCHIVE_BYTES = 512 * 1024 * 1024
internal const val MAX_RUNTIME_ARCHIVE_BYTES = MAX_ARCHIVE_BYTES.toLong()
private const val MAX_MEMBER_BYTES = 384L * 1024 * 1024
private const val MAX_MEMBERS = 16
private const val EOCD_SIGNATURE = 0x06054b50L
private const val CENTRAL_SIGNATURE = 0x02014b50L
private const val LOCAL_SIGNATURE = 0x04034b50L
private const val ENCRYPTED_FLAG = 1
internal const val RUNTIME_ZIP_STORED = 0
internal const val RUNTIME_ZIP_DEFLATED = 8
private const val STORED = RUNTIME_ZIP_STORED
private const val DEFLATED = RUNTIME_ZIP_DEFLATED
private const val FILE_TYPE_MASK = 0xf000
private const val SYMLINK_TYPE = 0xa000
