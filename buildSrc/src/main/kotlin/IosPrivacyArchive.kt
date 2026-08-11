import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

internal enum class IosArMemberKind { INDEX, OBJECT }

internal data class IosArMember(
    val index: Int,
    val name: String,
    val kind: IosArMemberKind,
    val dataOffset: Long,
    val bytes: Long,
)

internal class IosStaticArchive(private val file: File) {
    val members: List<IosArMember> = parse()

    fun copyAndHash(member: IosArMember, output: OutputStream? = null): String =
        RandomAccessFile(file, "r").use { source ->
            source.seek(member.dataOffset)
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = member.bytes
            while (remaining > 0) {
                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                check(count > 0) { "Truncated ar member ${member.index}: ${file.path}" }
                digest.update(buffer, 0, count)
                output?.write(buffer, 0, count)
                remaining -= count
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

    private fun parse(): List<IosArMember> = RandomAccessFile(file, "r").use { source ->
        val magic = ByteArray(8)
        check(source.read(magic) == magic.size) { "Static framework is truncated: ${file.path}" }
        check(!magic.contentEquals("!<thin>\n".encodeToByteArray())) {
            "Thin archives are not valid XCFramework members: ${file.path}"
        }
        check(magic.contentEquals("!<arch>\n".encodeToByteArray())) {
            "Static framework is not an ar archive: ${file.path}"
        }
        val result = mutableListOf<IosArMember>()
        var longNames: LongRange? = null
        while (source.filePointer < source.length()) {
            val headerOffset = source.filePointer
            check(source.length() - headerOffset >= 60) {
                "Truncated ar header at byte $headerOffset: ${file.path}"
            }
            val header = ByteArray(60).also(source::readFully)
            check(header[58] == '`'.code.toByte() && header[59] == '\n'.code.toByte()) {
                "Invalid ar header at byte $headerOffset: ${file.path}"
            }
            val sizeText = header.decodeAscii(48, 58).trim()
            val declared = sizeText.toLongOrNull()
            check(declared != null && declared >= 0) {
                "Invalid ar member size at byte $headerOffset: ${file.path}"
            }
            val payloadOffset = source.filePointer
            check(declared <= source.length() - payloadOffset) {
                "Truncated ar member at byte $headerOffset: ${file.path}"
            }
            val field = header.decodeAscii(0, 16).trimEnd()
            var dataOffset = payloadOffset
            var dataSize = declared
            var name = field
            if (field.startsWith("#1/")) {
                val nameBytes = field.removePrefix("#1/").toLongOrNull()
                check(nameBytes != null && nameBytes in 1..declared && nameBytes <= Int.MAX_VALUE) {
                    "Invalid BSD ar filename at byte $headerOffset: ${file.path}"
                }
                val encoded = ByteArray(nameBytes.toInt()).also(source::readFully)
                name = encoded.dropLastWhile { it == 0.toByte() }.toByteArray().decodeUtf8()
                dataOffset += nameBytes
                dataSize -= nameBytes
            } else if (field.matches(Regex("/[0-9]+"))) {
                val table = longNames ?: error("Ar member refers to a missing filename table: ${file.path}")
                name = source.resolveGnuName(table, field.drop(1).toLong(), headerOffset)
            } else if (field.endsWith('/') && field !in INDEX_NAMES) {
                name = field.dropLast(1)
            }
            check(name.isNotEmpty()) { "Empty ar member name at byte $headerOffset: ${file.path}" }
            val kind = if (name in INDEX_NAMES || name == "//") IosArMemberKind.INDEX else IosArMemberKind.OBJECT
            result += IosArMember(result.size, name, kind, dataOffset, dataSize)
            if (name == "//") longNames = payloadOffset until (payloadOffset + declared)
            source.seek(payloadOffset + declared)
            if (declared % 2L != 0L) {
                check(source.filePointer < source.length() && source.read() == '\n'.code) {
                    "Missing ar alignment byte after byte $headerOffset: ${file.path}"
                }
            }
        }
        result
    }

    private fun RandomAccessFile.resolveGnuName(table: LongRange, offset: Long, headerOffset: Long): String {
        check(offset >= 0 && offset < table.last - table.first + 1) {
            "Invalid GNU ar filename offset at byte $headerOffset: ${file.path}"
        }
        val saved = filePointer
        seek(table.first + offset)
        val bytes = ArrayList<Byte>()
        while (filePointer <= table.last) {
            val value = read()
            if (value < 0 || value == 0) break
            if (value == '/'.code && filePointer <= table.last && read() == '\n'.code) break
            if (value == '/'.code) seek(filePointer - 1)
            bytes += value.toByte()
        }
        seek(saved)
        check(bytes.isNotEmpty()) { "Empty GNU ar filename at byte $headerOffset: ${file.path}" }
        return bytes.toByteArray().decodeUtf8()
    }

    private fun ByteArray.decodeAscii(start: Int, end: Int) =
        String(this, start, end - start, Charsets.US_ASCII)

    private fun ByteArray.decodeUtf8(): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()

    private companion object {
        val INDEX_NAMES = setOf("/", "//", "/SYM64/", "__.SYMDEF", "__.SYMDEF SORTED", "__.SYMDEF_64", "__.SYMDEF_64 SORTED")
    }
}
