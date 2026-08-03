import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class PrepareCodexIosSourceTaskTest {
    @Test
    fun `validates patches and stages the exact source revision`() {
        val project = fixture()
        try {
            val revision = "1".repeat(40)
            val archive = project.resolve("codex.tar.gz")
            writeTarGz(
                archive,
                mapOf("codex-$revision/marker.txt" to "before\n".encodeToByteArray()),
            )
            project.resolve("change.patch").writeText(
                """
                diff --git a/marker.txt b/marker.txt
                --- a/marker.txt
                +++ b/marker.txt
                @@ -1 +1 @@
                -before
                +after
                """.trimIndent() + "\n",
            )
            project.resolve("bridge").mkdir()
            project.resolve("bridge/Cargo.toml").writeText("[package]\nname = \"bridge\"\n")
            task(project, revision, archive.sha256()).prepare()

            assertEquals("after\n", project.resolve("build/codex-source/marker.txt").readText())
            assertTrue(project.resolve("build/codex-source/codex-rs/ios-bridge/Cargo.toml").isFile)
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `rejects an archive hash before replacing output`() {
        val project = fixture()
        try {
            val revision = "2".repeat(40)
            val archive = project.resolve("codex.tar.gz")
            writeTarGz(
                archive,
                mapOf("codex-$revision/marker.txt" to "source\n".encodeToByteArray()),
            )
            project.resolve("bridge").mkdir()
            project.resolve("bridge/Cargo.toml").writeText("[package]\nname = \"bridge\"\n")
            project.resolve("change.patch").writeText("")
            val failure = assertFailsWith<IllegalStateException> {
                task(project, revision, "0".repeat(64)).prepare()
            }

            assertTrue(failure.message.orEmpty().contains("Codex iOS source archive SHA-256 mismatch"))
            assertFalse(project.resolve("build/codex-source").exists())
        } finally {
            project.deleteRecursively()
        }
    }

    private fun fixture() = createTempDirectory("codex-ios-source-task").toFile()

    private fun task(projectDirectory: File, revision: String, hash: String): PrepareCodexIosSourceTask {
        val project = ProjectBuilder.builder().withProjectDir(projectDirectory).build()
        return project.tasks.register("prepareCodexIosSource", PrepareCodexIosSourceTask::class.java).get().apply {
            this.revision.set(revision)
            archiveSha256.set(hash)
            localArchive.set(project.layout.projectDirectory.file("codex.tar.gz"))
            patches.from(project.layout.projectDirectory.file("change.patch"))
            bridgeSource.set(project.layout.projectDirectory.dir("bridge"))
            outputDirectory.set(project.layout.buildDirectory.dir("codex-source"))
        }
    }

    private fun writeTarGz(target: File, entries: Map<String, ByteArray>) {
        GZIPOutputStream(target.outputStream()).use { output ->
            entries.forEach { (name, contents) ->
                val header = ByteArray(512)
                name.toByteArray().copyInto(header)
                octal(header, 100, 8, 493)
                octal(header, 108, 8, 0)
                octal(header, 116, 8, 0)
                octal(header, 124, 12, contents.size.toLong())
                octal(header, 136, 12, 0)
                repeat(8) { header[148 + it] = ' '.code.toByte() }
                header[156] = '0'.code.toByte()
                "ustar\u0000".toByteArray().copyInto(header, 257)
                "00".toByteArray().copyInto(header, 263)
                val checksum = header.sumOf { it.toInt() and 0xff }
                "%06o\u0000 ".format(checksum).toByteArray().copyInto(header, 148)
                output.write(header)
                output.write(contents)
                repeat((512 - contents.size % 512) % 512) { output.write(0) }
            }
            output.write(ByteArray(1024))
        }
    }

    private fun octal(target: ByteArray, offset: Int, length: Int, value: Long) {
        ("%0${length - 1}o\u0000".format(value)).toByteArray().copyInto(target, offset)
    }

    private fun File.sha256() = MessageDigest.getInstance("SHA-256").digest(readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
