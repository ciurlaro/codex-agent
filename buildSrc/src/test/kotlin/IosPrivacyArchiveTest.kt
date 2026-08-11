import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosPrivacyArchiveTest {
    @Test
    fun `parses standard BSD GNU long and duplicate members by occurrence`() = withDirectory { root ->
        val archive = root.resolve("mixed.a")
        writeArchive(
            archive,
            standard("short.o", "one"),
            bsd("a-very-long-object-member-name.o", "two"),
            standard("//", "gnu-long-object-member-name.o/\n"),
            standard("/0", "three"),
            standard("short.o", "four"),
        )
        val parsed = IosStaticArchive(archive)
        assertEquals(
            listOf("short.o", "a-very-long-object-member-name.o", "//", "gnu-long-object-member-name.o", "short.o"),
            parsed.members.map { it.name },
        )
        assertEquals(listOf(0, 1, 2, 3, 4), parsed.members.map { it.index })
        assertEquals(IosArMemberKind.INDEX, parsed.members[2].kind)
        assertEquals(2, parsed.members.count { it.name == "short.o" })
    }

    @Test
    fun `rejects thin malformed truncated invalid padding and bad long-name references`() = withDirectory { root ->
        val cases = listOf(
            "thin.a" to "!<thin>\n".encodeToByteArray(),
            "magic.a" to "not-an-ar".encodeToByteArray(),
            "header.a" to "!<arch>\nshort".encodeToByteArray(),
            "trailer.a" to ("!<arch>\n".encodeToByteArray() + header("x.o/", 0).also { it[58] = 0 }),
            "size.a" to ("!<arch>\n".encodeToByteArray() + header("x.o/", 9) + byteArrayOf(1)),
            "padding.a" to archiveBytes(standard("x.o", "x")).also { it[it.lastIndex] = 0 },
            "gnu.a" to archiveBytes(standard("/3", "x")),
            "bsd.a" to archiveBytes(raw("#1/9", "x".encodeToByteArray())),
        )
        cases.forEach { (name, bytes) ->
            val file = root.resolve(name).apply { writeBytes(bytes) }
            assertFailsWith<IllegalStateException>(name) { IosStaticArchive(file) }
        }
    }

    @Test
    fun `hashes exact member bytes without loading the archive`() = withDirectory { root ->
        val archive = root.resolve("hash.a")
        writeArchive(archive, standard("x.o", "payload"))
        val parsed = IosStaticArchive(archive)
        val output = ByteArrayOutputStream()
        val digest = parsed.copyAndHash(parsed.members.single(), output)
        assertEquals("payload", output.toString(Charsets.UTF_8))
        assertEquals(root.resolve("payload").apply { writeText("payload") }.releaseDigest(), digest)
    }

    private fun withDirectory(block: (File) -> Unit) {
        val root = createTempDirectory("privacy-ar").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}

internal data class ArFixture(val headerName: String, val payload: ByteArray)
internal fun standard(name: String, content: String) = raw(if (name.startsWith('/')) name else "$name/", content.encodeToByteArray())
internal fun bsd(name: String, content: String): ArFixture {
    val encoded = name.encodeToByteArray()
    return raw("#1/${encoded.size}", encoded + content.encodeToByteArray())
}
internal fun raw(name: String, payload: ByteArray) = ArFixture(name, payload)
internal fun writeArchive(file: File, vararg members: ArFixture) = file.writeBytes(archiveBytes(*members))
internal fun archiveBytes(vararg members: ArFixture): ByteArray = ByteArrayOutputStream().apply {
    write("!<arch>\n".encodeToByteArray())
    members.forEach { member ->
        write(header(member.headerName, member.payload.size))
        write(member.payload)
        if (member.payload.size % 2 != 0) write('\n'.code)
    }
}.toByteArray()
internal fun header(name: String, size: Int): ByteArray =
    (name.padEnd(16) + "0".padEnd(12) + "0".padEnd(6) + "0".padEnd(6) + "100644".padEnd(8) + size.toString().padEnd(10) + "`\n")
        .toByteArray(StandardCharsets.US_ASCII)
