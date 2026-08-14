import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LinuxArm64JvmEvidenceBundleTest {
    @Test
    fun `staged portable runner executes the exact JVM inventory on Linux ARM64`() = withFixture { fixture ->
        fixture.stage()
        assertEquals(
            setOf(
                "candidate-commit.txt", "codex-app-server-distributions.json",
                "app-server-linux-arm64.zip", JVM_RUNTIME_RUNNER_ARCHIVE,
            ),
            fixture.bundle.entries(),
        )
        val commands = mutableListOf<List<String>>()
        fixture.execute { command, environment ->
            commands += command
            assertTrue(File(environment.getValue("CODEX_AGENT_APP_SERVER_EXECUTABLE")).isFile)
            val supervisor = File(environment.getValue("CODEX_AGENT_PROCESS_SUPERVISOR_EXECUTABLE"))
            assertTrue(supervisor.isFile)
            assertEquals(supervisor.releaseDigest(), environment["CODEX_AGENT_PROCESS_SUPERVISOR_SHA256"])
            if (command.last() == "--list-tests") JvmEvidenceProcessResult(0, exactListing())
            else JvmEvidenceProcessResult(0, "")
        }
        assertEquals(5, commands.size)
        assertEquals("linuxArm64", fixture.evidence.readReleaseObject().releaseString("target"))
    }

    @Test
    fun `execution rejects commit runner and bundle mutations before tests`() = withFixture { fixture ->
        fixture.stage()
        assertFailsWith<IllegalStateException> {
            executeLinuxArm64JvmEvidenceBundle(
                "0".repeat(40), fixture.bundle, "java", fixture.evidence, ARM_ENV,
            ) { _, _ -> error("must not run") }
        }
        assertFailsWith<IllegalStateException> {
            executeLinuxArm64JvmEvidenceBundle(
                COMMIT, fixture.bundle, "java", fixture.evidence, ARM_ENV + ("RUNNER_ARCH" to "X64"),
            ) { _, _ -> error("must not run") }
        }
        val entries = fixture.bundle.readEntries().toMutableMap().apply { remove(JVM_RUNTIME_RUNNER_ARCHIVE) }
        fixture.bundle.writeZip(entries)
        assertFailsWith<IllegalStateException> {
            fixture.execute { _, _ -> error("must not run") }
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = createTempDirectory("linux-arm64-jvm-evidence").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }

    private class Fixture(val root: File) {
        private val appServer = "official app server".encodeToByteArray()
        val manifest = writeTestDesktopDistributionManifest(root.resolve("distributions.json"),
            appServer.inputStream().releaseDigest())
        val classifier = root.resolve("app-server-linux-arm64.zip").apply { writeZip(linkedMapOf(
            "codex-app-server" to appServer,
            "codex-process-supervisor" to "supervisor".encodeToByteArray(),
            "openai-codex-LICENSE.txt" to "license".encodeToByteArray(),
            "openai-codex-NOTICE.txt" to "notice".encodeToByteArray(),
        )) }
        val runner = root.resolve(JVM_RUNTIME_RUNNER_ARCHIVE).apply { writeZip(linkedMapOf(
            "classes/${JVM_RUNTIME_RUNNER_ENTRYPOINT.replace('.', '/')}.class" to "main".encodeToByteArray(),
            "lib/kotlin-stdlib.jar" to "stdlib".encodeToByteArray(),
        )) }
        val bundle = root.resolve("linux-arm64-jvm.zip")
        val evidence = root.resolve(jvmRuntimeEvidenceFileName("linuxArm64"))
        fun stage() = stageLinuxArm64JvmEvidenceBundle(COMMIT, manifest, classifier, runner, bundle)
        fun execute(process: (List<String>, Map<String, String>) -> JvmEvidenceProcessResult) =
            executeLinuxArm64JvmEvidenceBundle(COMMIT, bundle, "java", evidence, ARM_ENV, process)
    }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        val ARM_ENV = mapOf("RUNNER_OS" to "Linux", "RUNNER_ARCH" to "ARM64")
        fun exactListing() = buildString {
            append(DESKTOP_RUNTIME_TEST_CLASS).append(".\n")
            desktopRuntimeTestMethods.forEach { append("  ").append(it).append('\n') }
        }
    }
}

private fun File.entries() = ZipFile(this).use { zip ->
    zip.entries().asSequence().map(ZipEntry::getName).toSet()
}

private fun File.readEntries() = ZipFile(this).use { zip ->
    linkedMapOf<String, ByteArray>().apply {
        zip.entries().asSequence().forEach { entry ->
            put(entry.name, zip.getInputStream(entry).use { it.readBytes() })
        }
    }
}

private fun File.writeZip(entries: Map<String, ByteArray>) = ZipOutputStream(outputStream()).use { zip ->
    entries.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name).apply { setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0)) })
        zip.write(bytes)
        zip.closeEntry()
    }
}
