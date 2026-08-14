import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class DesktopSupervisorPackagingTest {
    @Test
    fun `generated distribution metadata is common and includes supervisors`() {
        val root = createTempDirectory("desktop-distribution-source").toFile()
        try {
            val manifest = root.resolve("manifest.json").apply { writeText(testManifest()) }
            val output = root.resolve("generated")
            ProjectBuilder.builder().withProjectDir(root).build().tasks
                .register("generate", GenerateDesktopDistributionSourceTask::class.java).get().apply {
                    manifestFile.set(manifest)
                    outputDirectory.set(output)
                    generate()
                }

            val source = output.walkTopDown().single { it.isFile }.readText()
            assertTrue("desktopCodexDistribution(target: String)" in source)
            assertTrue("supervisorExecutableName" in source)
            assertFalse("kotlin.native" in source)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `desktop package contains runtime supervisor license and notice`() {
        val root = createTempDirectory("desktop-supervisor-package").toFile()
        try {
            val runtime = root.resolve("codex-app-server").apply { writeText("runtime") }
            val supervisor = root.resolve("codex-process-supervisor").apply { writeText("supervisor") }
            val license = root.resolve("license.txt").apply { writeText("license") }
            val notice = root.resolve("notice.txt").apply { writeText("notice") }
            val upstream = root.resolve("upstream.zip").apply { zip(mapOf(runtime.name to runtime)) }
            val packaged = root.resolve("runtime.zip")
            ProjectBuilder.builder().withProjectDir(root).build().tasks
                .register("package", PackageDesktopCodexRuntimeTask::class.java).get().apply {
                    releaseTag.set("rust-v0.145.0")
                    asset.set(upstream.name)
                    archiveSha256.set(upstream.sha256())
                    archiveEntry.set(runtime.name)
                    binarySha256.set(runtime.sha256())
                    executableName.set(runtime.name)
                    supervisorExecutableName.set(supervisor.name)
                    supervisorExecutable.set(supervisor)
                    localArchive.set(upstream)
                    licenseFile.set(license)
                    noticeFile.set(notice)
                    outputFile.set(packaged)
                    packageRuntime()
                }

            ZipFile(packaged).use { archive ->
                assertEquals(
                    setOf(runtime.name, supervisor.name, "openai-codex-LICENSE.txt", "openai-codex-NOTICE.txt"),
                    archive.entries().asSequence().filterNot(ZipEntry::isDirectory).map(ZipEntry::getName).toSet(),
                )
            }
            assertEquals(0x81ed, packaged.unixModes().getValue(runtime.name))
            assertEquals(0x81ed, packaged.unixModes().getValue(supervisor.name))
            assertEquals(0x81a4, packaged.unixModes().getValue("openai-codex-LICENSE.txt"))
            assertEquals(0x81a4, packaged.unixModes().getValue("openai-codex-NOTICE.txt"))

            val imported = root.resolve("imported.zip")
            ProjectBuilder.builder().withProjectDir(root).build().tasks
                .register("importPackage", PackageDesktopCodexRuntimeTask::class.java).get().apply {
                    releaseTag.set("rust-v0.145.0"); asset.set(upstream.name)
                    archiveSha256.set(upstream.sha256()); archiveEntry.set(runtime.name)
                    binarySha256.set(runtime.sha256()); executableName.set(runtime.name)
                    supervisorExecutableName.set(supervisor.name); prebuiltPackage.set(packaged)
                    licenseFile.set(license); noticeFile.set(notice); outputFile.set(imported)
                    packageRuntime()
                }
            assertTrue(packaged.readBytes().contentEquals(imported.readBytes()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `POSIX supervisor contains descendants on exit and shutdown`() {
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) return
        val root = createTempDirectory("desktop-supervisor-process").toFile()
        try {
            val source = listOf(
                File("../../codex-agent-runtime-desktop/native/supervisor/codex_process_supervisor.c"),
                File("../codex-agent-runtime-desktop/native/supervisor/codex_process_supervisor.c"),
                File("codex-agent-runtime-desktop/native/supervisor/codex_process_supervisor.c"),
            ).map(File::getCanonicalFile).first(File::isFile)
            val supervisor = root.resolve("codex-process-supervisor")
            val compilerLog = root.resolve("compiler.log")
            val compiler = ProcessBuilder(
                "cc", "-std=c11", "-D_POSIX_C_SOURCE=200809L", "-Wall", "-Wextra", "-Werror",
                source.absolutePath, "-o", supervisor.absolutePath,
            ).redirectErrorStream(true).redirectOutput(compilerLog).start()
            assertTrue(compiler.waitFor(30, TimeUnit.SECONDS), "Supervisor compilation timed out")
            assertEquals(0, compiler.exitValue(), compilerLog.readText())

            runContainmentScenario(root, supervisor, signalSupervisor = false)
            runContainmentScenario(root, supervisor, signalSupervisor = true)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testManifest() = buildString {
        append("{\"version\":\"0.145.0\",\"releaseTag\":\"rust-v0.145.0\",\"distributions\":[")
        listOf("macosArm64", "macosX64", "linuxArm64", "linuxX64", "mingwX64").forEachIndexed { index, target ->
            if (index > 0) append(',')
            val executable = if (target == "mingwX64") "codex-app-server.exe" else "codex-app-server"
            val supervisor = if (target == "mingwX64") "codex-process-supervisor.exe" else "codex-process-supervisor"
            append("{\"target\":\"$target\",\"classifier\":\"$target\",\"asset\":\"asset\",")
            append("\"archiveSha256\":\"${"a".repeat(64)}\",\"archiveEntry\":\"$executable\",")
            append("\"binarySha256\":\"${"b".repeat(64)}\",\"executableName\":\"$executable\",")
            append("\"supervisorExecutableName\":\"$supervisor\"}")
        }
        append("]}")
    }

    private fun File.zip(members: Map<String, File>) = ZipOutputStream(outputStream()).use { output ->
        members.forEach { (name, file) ->
            output.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(output) }
            output.closeEntry()
        }
    }

    private fun File.sha256(): String = MessageDigest.getInstance("SHA-256").digest(readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun runContainmentScenario(root: File, supervisor: File, signalSupervisor: Boolean) {
        val suffix = if (signalSupervisor) "signal" else "exit"
        val pidFile = root.resolve("$suffix.pid")
        val script = root.resolve("$suffix.sh").apply {
            writeText(buildString {
                append("#!/bin/sh\n")
                append("sleep 30 &\n")
                append("echo $! > '").append(pidFile.absolutePath).append("'\n")
                if (signalSupervisor) append("wait\n") else append("exit 0\n")
            })
            assertTrue(setExecutable(true, false))
        }
        val process = ProcessBuilder(supervisor.absolutePath, script.absolutePath)
            .directory(root).redirectErrorStream(true).start()
        var descendant: ProcessHandle? = null
        try {
            repeat(200) {
                if (pidFile.isFile) return@repeat
                Thread.sleep(10)
            }
            assertTrue(pidFile.isFile, "Descendant PID was not recorded")
            descendant = ProcessHandle.of(pidFile.readText().trim().toLong()).orElse(null)
            if (signalSupervisor) process.destroy()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Supervisor did not stop")
            repeat(200) {
                if (descendant?.isAlive != true) return@repeat
                Thread.sleep(10)
            }
            assertFalse(descendant?.isAlive == true, "Supervisor left a descendant running")
        } finally {
            if (process.isAlive) process.destroyForcibly()
            if (descendant?.isAlive == true) descendant.destroyForcibly()
        }
    }

    private fun File.unixModes(): Map<String, Int> = RandomAccessFile(this, "r").use { archive ->
        fun readUnsignedShortLittleEndian(): Int = archive.readUnsignedByte() or (archive.readUnsignedByte() shl 8)
        fun readUnsignedIntLittleEndian(): Long = (0 until 4).fold(0L) { value, shift ->
            value or (archive.readUnsignedByte().toLong() shl (shift * 8))
        }
        val searchStart = maxOf(0L, archive.length() - 65_557L)
        var eocd = archive.length() - 22L
        while (eocd >= searchStart) {
            archive.seek(eocd)
            if (readUnsignedIntLittleEndian() == 0x06054b50L) break
            eocd--
        }
        assertTrue(eocd >= searchStart)
        archive.seek(eocd + 10)
        val count = readUnsignedShortLittleEndian()
        archive.seek(eocd + 16)
        var cursor = readUnsignedIntLittleEndian()
        buildMap {
            repeat(count) {
                archive.seek(cursor)
                assertEquals(0x02014b50L, readUnsignedIntLittleEndian())
                archive.seek(cursor + 28)
                val nameBytes = ByteArray(readUnsignedShortLittleEndian())
                val extraBytes = readUnsignedShortLittleEndian()
                val commentBytes = readUnsignedShortLittleEndian()
                archive.seek(cursor + 38)
                val mode = (readUnsignedIntLittleEndian() ushr 16).toInt()
                archive.seek(cursor + 46)
                archive.readFully(nameBytes)
                put(nameBytes.decodeToString(), mode)
                cursor += 46 + nameBytes.size + extraBytes + commentBytes
            }
        }
    }
}
