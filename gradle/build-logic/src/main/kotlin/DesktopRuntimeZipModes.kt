import java.io.File
import java.io.RandomAccessFile

internal fun patchDesktopRuntimeUnixModes(target: File, executables: Set<String>) {
    desktopRuntimeUnixModes(target) { name ->
        if (name in executables) EXECUTABLE_MODE else FILE_MODE
    }
}

internal fun readDesktopRuntimeUnixModes(target: File): Map<String, Int> =
    desktopRuntimeUnixModes(target)

private fun desktopRuntimeUnixModes(
    target: File,
    replacement: ((String) -> Int)? = null,
): Map<String, Int> = RandomAccessFile(target, if (replacement == null) "r" else "rw").use { archive ->
    fun readU16(): Int = archive.readUnsignedByte() or (archive.readUnsignedByte() shl 8)
    fun readU32(): Long = (0 until 4).fold(0L) { value, shift ->
        value or (archive.readUnsignedByte().toLong() shl (shift * 8))
    }
    fun writeU32(value: Long) {
        repeat(4) { shift -> archive.write(((value shr (shift * 8)) and 0xff).toInt()) }
    }

    val searchStart = maxOf(0L, archive.length() - MAX_EOCD_BYTES)
    var eocd = archive.length() - MIN_EOCD_BYTES
    while (eocd >= searchStart) {
        archive.seek(eocd)
        if (readU32() == EOCD_SIGNATURE) break
        eocd--
    }
    check(eocd >= searchStart) { "Desktop runtime ZIP end record is missing" }
    archive.seek(eocd + 10)
    val entryCount = readU16()
    archive.seek(eocd + 16)
    var cursor = readU32()
    buildMap {
        repeat(entryCount) {
            archive.seek(cursor)
            check(readU32() == CENTRAL_ENTRY_SIGNATURE) {
                "Desktop runtime ZIP central directory is invalid"
            }
            archive.seek(cursor + 28)
            val nameBytes = ByteArray(readU16())
            val extraBytes = readU16()
            val commentBytes = readU16()
            archive.seek(cursor + 46)
            archive.readFully(nameBytes)
            val name = nameBytes.decodeToString()
            val mode = if (replacement != null) {
                replacement(name).also { value ->
                    archive.seek(cursor + 4)
                    archive.write(20)
                    archive.write(3)
                    archive.seek(cursor + 38)
                    writeU32(value.toLong() shl 16)
                }
            } else {
                archive.seek(cursor + 38)
                (readU32() ushr 16).toInt()
            }
            put(name, mode)
            cursor += 46 + nameBytes.size + extraBytes + commentBytes
        }
    }
}

private const val EXECUTABLE_MODE = 0x81ed
private const val FILE_MODE = 0x81a4
private const val EOCD_SIGNATURE = 0x06054b50L
private const val CENTRAL_ENTRY_SIGNATURE = 0x02014b50L
private const val MIN_EOCD_BYTES = 22L
private const val MAX_EOCD_BYTES = MIN_EOCD_BYTES + 65_535L
