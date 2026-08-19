import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class PrepareCodexRuntimeTaskTest {
    @Test
    fun `extracts validates and reuses an exact runtime archive`() {
        val project = fixture()
        try {
            val binary = "runtime-binary".toByteArray()
            val archive = project.resolve("runtime.tar.gz")
            writeTarGz(archive, mapOf(ASSET to binary))
            writeBuild(project, archive.sha256(), binary.sha256())

            val first = run(project)
            assertEquals(TaskOutcome.SUCCESS, first.task(":prepareCodexRuntime")?.outcome)
            val output = project.resolve("build/generated/codex-runtime/main/arm64-v8a/libcodex_app_server.so")
            assertTrue(output.canExecute())
            assertTrue(output.readBytes().contentEquals(binary))

            val second = run(project)
            assertEquals(TaskOutcome.UP_TO_DATE, second.task(":prepareCodexRuntime")?.outcome)
            assertTrue(second.output.contains("Reusing configuration cache."))
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `rejects archive and binary hash failures without temporary residue`() {
        val project = fixture()
        try {
            val archive = project.resolve("runtime.tar.gz")
            writeTarGz(archive, mapOf(ASSET to "runtime".toByteArray()))
            writeBuild(project, "0".repeat(64), "1".repeat(64))
            val failure = runAndFail(project)
            assertTrue(failure.output.contains("archive SHA-256 mismatch"))
            assertFalse(project.resolve("build/generated/codex-runtime/main/arm64-v8a/libcodex_app_server.so").exists())
            assertTrue(project.resolve("build/tmp/prepareCodexRuntime").walkTopDown().none { it.name.endsWith(".tmp") })
        } finally {
            project.deleteRecursively()
        }
    }

    @Test
    fun `rejects ambiguous archives`() {
        val project = fixture()
        try {
            val binary = "runtime".toByteArray()
            val archive = project.resolve("runtime.tar.gz")
            writeTarGz(archive, mapOf(ASSET to binary, "unexpected" to byteArrayOf(1)))
            writeBuild(project, archive.sha256(), binary.sha256())
            assertTrue(runAndFail(project).output.contains("exactly the root executable"))
        } finally {
            project.deleteRecursively()
        }
    }

    private fun fixture() = createTempDirectory("codex-runtime-task").toFile().apply {
        resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
    }

    private fun writeBuild(project: File, archiveHash: String, binaryHash: String) {
        project.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("codexagent.codex-runtime")
            }
            """.trimIndent(),
        )
        project.resolve("gradle.properties").writeText(
            """
            codexAgent.codexVersion=1.2.3
            codexAgent.codexArchiveSha256=$archiveHash
            codexAgent.codexBinarySha256=$binaryHash
            codexAgent.codexArchiveFile=runtime.tar.gz
            """.trimIndent(),
        )
    }

    private fun run(project: File) = runner(project).build()
    private fun runAndFail(project: File) = runner(project).buildAndFail()
    private fun runner(project: File) = GradleRunner.create().withProjectDir(project).withPluginClasspath()
        .withArguments("prepareCodexRuntime", "--configuration-cache", "--stacktrace")

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

    private fun File.sha256() = readBytes().sha256()
    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val ASSET = "codex-app-server-aarch64-unknown-linux-musl"
    }
}
