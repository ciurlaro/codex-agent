import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsNodeSupervisorTasksTest {
    @Test
    fun `commands bind exact generator architecture configuration target and paths`() = withFixture { fixture ->
        assertEquals(listOf(
            "C:/Program Files/CMake/bin/cmake.exe", "-S", fixture.source.absolutePath,
            "-B", fixture.root.resolve("build with spaces").absolutePath,
            "-G", "Visual Studio 17 2022", "-A", "x64", "-DCMAKE_BUILD_TYPE=Release",
            "-DCODEX_AGENT_SUPERVISOR_OUTPUT_DIR=${fixture.root.resolve("output with spaces").absolutePath}",
        ), windowsSupervisorConfigureCommand(
            "C:/Program Files/CMake/bin/cmake.exe", fixture.source,
            fixture.root.resolve("build with spaces"), fixture.root.resolve("output with spaces"),
        ))
        assertEquals(listOf(
            "cmake", "--build", fixture.root.resolve("build").absolutePath,
            "--config", "Release", "--target", "codex_agent_node_windows_supervisor", "--parallel", "1",
        ), windowsSupervisorBuildCommand("cmake", fixture.root.resolve("build")))
    }

    @Test
    fun `identity binds basename bytes source compiler and commands`() = withFixture { fixture ->
        val identity = fixture.identity()
        val file = fixture.root.resolve("windows-supervisor.json")
        writeWindowsSupervisorIdentity(file, identity)

        assertEquals(identity, readWindowsSupervisorIdentity(file))
        verifyWindowsSupervisorIdentity(identity, fixture.executable, fixture.source)

        fixture.executable.appendBytes(byteArrayOf(4))
        assertFailsWith<IllegalStateException> {
            verifyWindowsSupervisorIdentity(identity, fixture.executable, fixture.source)
        }
    }

    @Test
    fun `identity rejects incomplete schema and changed source`() = withFixture { fixture ->
        val identity = fixture.root.resolve("windows-supervisor.json")
        identity.writeText("""{"schemaVersion":1,"fileName":"$WINDOWS_SUPERVISOR_FILE_NAME"}""")
        assertFailsWith<IllegalStateException> { readWindowsSupervisorIdentity(identity) }

        val expected = fixture.identity()
        fixture.source.resolve("supervisor.c").appendText("changed")
        assertFailsWith<IllegalStateException> {
            verifyWindowsSupervisorIdentity(expected, fixture.executable, fixture.source)
        }
    }

    @Test
    fun `package is deterministic and exact`() = withFixture { fixture ->
        val identityFile = fixture.root.resolve("windows-supervisor.json")
        writeWindowsSupervisorIdentity(identityFile, fixture.identity())
        val first = fixture.root.resolve("first.zip")
        val second = fixture.root.resolve("second.zip")

        writeWindowsSupervisorPackage(first, fixture.executable, identityFile)
        writeWindowsSupervisorPackage(second, fixture.executable, identityFile)

        assertTrue(first.readBytes().contentEquals(second.readBytes()))
        assertEquals(fixture.identity(), verifyWindowsSupervisorPackage(first, identityFile, fixture.source))
    }

    @Test
    fun `package rejects extra members`() = withFixture { fixture ->
        val identityFile = fixture.root.resolve("windows-supervisor.json")
        writeWindowsSupervisorIdentity(identityFile, fixture.identity())
        val archive = fixture.root.resolve("extra.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf(
                WINDOWS_SUPERVISOR_FILE_NAME to fixture.executable.readBytes(),
                identityFile.name to identityFile.readBytes(),
                "extra" to byteArrayOf(1),
            ).forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            }
        }
        assertFailsWith<IllegalStateException> {
            verifyWindowsSupervisorPackage(archive, identityFile, fixture.source)
        }
    }

    @Test
    fun `package requires canonical identity basename`() = withFixture { fixture ->
        val identityFile = fixture.root.resolve("not-canonical.json")
        writeWindowsSupervisorIdentity(identityFile, fixture.identity())

        assertFailsWith<IllegalStateException> {
            writeWindowsSupervisorPackage(fixture.root.resolve("invalid.zip"), fixture.executable, identityFile)
        }
    }

    private fun withFixture(block: (SupervisorFixture) -> Unit) {
        val root = Files.createTempDirectory("windows-supervisor-").toFile()
        try {
            val source = root.resolve("source").also(File::mkdirs)
            source.resolve("CMakeLists.txt").writeText("cmake")
            source.resolve("supervisor.c").writeText("source")
            val executable = root.resolve(WINDOWS_SUPERVISOR_FILE_NAME).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            block(SupervisorFixture(root, source, executable))
        } finally {
            root.deleteRecursively()
        }
    }
}

private data class SupervisorFixture(val root: File, val source: File, val executable: File) {
    fun identity() = WindowsSupervisorIdentity(
        WINDOWS_SUPERVISOR_FILE_NAME,
        executable.windowsSupervisorSha256(),
        executable.length(),
        windowsSupervisorSourceSha256(source),
        WindowsSupervisorCompiler("MSVC", "19.44.35211.0", "3.31.6"),
    )
}
