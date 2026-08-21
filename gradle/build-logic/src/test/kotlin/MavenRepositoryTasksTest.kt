import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MavenRepositoryTasksTest {
    @Test
    fun `exact signed publication and six relocations pass`() = withRepository { repository, inventory ->
        writeExactRepository(repository, signed = true)
        verifyMavenRepository(repository, GROUP, VERSION, true, inventory)
        val report = inventory.readReleaseObject()
        assertEquals(160, report.releaseInt("primaryArtifactCount"))
        assertEquals(26, report.releaseArray("artifactIds").size)
        assertEquals(6, report.releaseArray("relocationArtifactIds").size)
        expectedMavenPrimaryPaths(VERSION).forEach { relative ->
            val primary = repository.resolve("io/github/codex-agent-labs/$relative")
            assertEquals(primary.releaseDigest("MD5"), primary.resolveSibling(primary.name + ".md5").readText().trim())
            assertEquals(primary.releaseDigest("SHA-512"), primary.resolveSibling(primary.name + ".sha512").readText().trim())
        }
    }

    @Test
    fun `Gradle transport metadata is excluded from verification and inventory`() =
        withRepository { repository, inventory ->
            writeExactRepository(repository, signed = true)
            val coordinate = repository.resolve("io/github/codex-agent-labs/codex-agent-client")
            coordinate.resolve("maven-metadata.xml").writeText("<metadata/>")
            coordinate.resolve("maven-metadata.xml.sha256").writeText("0".repeat(64))
            val primary = coordinate.resolve("$VERSION/codex-agent-client-$VERSION.jar")
            primary.resolveSibling(primary.name + ".asc.sha256").writeText("0".repeat(64))

            verifyMavenRepository(repository, GROUP, VERSION, true, inventory)

            val inventoryText = inventory.readText()
            assertTrue(!inventoryText.contains("maven-metadata.xml"))
            assertTrue(!inventoryText.contains(".asc.sha256"))
        }

    @Test
    fun `missing module or target binary is rejected`() = withRepository { repository, inventory ->
        writeExactRepository(repository)
        val group = repository.resolve("io/github/codex-agent-labs")
        listOf(
            "codex-agent-client/0.2.0/codex-agent-client-0.2.0.module",
            "codex-agent-runtime-android/0.2.0/codex-agent-runtime-android-0.2.0.aar",
        ).forEach { relative ->
            val file = group.resolve(relative)
            val bytes = file.readBytes()
            file.delete()
            assertFailsWith<IllegalStateException> { verifyMavenRepository(repository, GROUP, VERSION, false, inventory) }
            file.writeBytes(bytes)
        }
    }

    @Test
    fun `unexpected coordinate or primary artifact is rejected`() = withRepository { repository, inventory ->
        writeExactRepository(repository)
        val group = repository.resolve("io/github/codex-agent-labs")
        group.resolve("unexpected/0.2.0/unexpected-0.2.0.jar").apply { parentFile.mkdirs(); writeText("x") }
        assertFailsWith<IllegalStateException> { verifyMavenRepository(repository, GROUP, VERSION, false, inventory) }
    }

    @Test
    fun `missing or unexpected relocation is rejected`() = withRepository { repository, inventory ->
        writeExactRepository(repository)
        val relocation = repository.resolve(expectedMavenRelocationPaths(VERSION).first())
        val bytes = relocation.readBytes()
        relocation.delete()
        assertFailsWith<IllegalStateException> { verifyMavenRepository(repository, GROUP, VERSION, false, inventory) }
        relocation.apply { parentFile.mkdirs(); writeBytes(bytes) }
        repository.resolve("io/github/ciurlaro/unreleased/0.2.0/unreleased-0.2.0.pom").apply {
            parentFile.mkdirs()
            writeText(validPom)
        }
        assertFailsWith<IllegalStateException> { verifyMavenRepository(repository, GROUP, VERSION, false, inventory) }
    }

    @Test
    fun `arbitrary Maven sidecar is rejected`() = withRepository { repository, inventory ->
        writeExactRepository(repository)
        val primary = repository.resolve("io/github/codex-agent-labs/${expectedMavenPrimaryPaths(VERSION).first()}")
        primary.resolveSibling(primary.name + ".unexpected.sha256").writeText("0".repeat(64))
        assertFailsWith<IllegalStateException> { verifyMavenRepository(repository, GROUP, VERSION, false, inventory) }
    }

    @Test
    fun `regular file outside the publication set is rejected`() = withRepository { repository, inventory ->
        writeExactRepository(repository)
        repository.resolve("outside.txt").apply { parentFile.mkdirs(); writeText("outside") }
        assertFailsWith<IllegalStateException> { verifyMavenRepository(repository, GROUP, VERSION, false, inventory) }
    }

    @Test
    fun `every primary artifact requires a signature when enabled`() = withRepository { repository, inventory ->
        writeExactRepository(repository, signed = true)
        val primary = repository.resolve("io/github/codex-agent-labs/${expectedMavenPrimaryPaths(VERSION).first()}")
        primary.resolveSibling(primary.name + ".asc").delete()
        val failure = assertFailsWith<IllegalStateException> {
            verifyMavenRepository(repository, GROUP, VERSION, true, inventory)
        }
        assertTrue(failure.message.orEmpty().contains(".asc is missing"))
    }

    @Test
    fun `every POM requires exact GPL metadata`() = withRepository { repository, inventory ->
        writeExactRepository(repository)
        val pom = repository.resolve("io/github/codex-agent-labs/codex-agent-client/0.2.0/codex-agent-client-0.2.0.pom")
        pom.writeText(pom.readText().replace("distribution>repo", "distribution>manual"))
        val failure = assertFailsWith<IllegalStateException> {
            verifyMavenRepository(repository, GROUP, VERSION, false, inventory)
        }
        assertTrue(failure.message.orEmpty().contains("licence"))
    }

    private fun withRepository(block: (File, File) -> Unit) {
        val directory = createTempDirectory("maven-release").toFile()
        try { block(directory.resolve("repository"), directory.resolve("inventory.json")) } finally { directory.deleteRecursively() }
    }

    private fun writeExactRepository(repository: File, signed: Boolean = false) {
        val group = repository.resolve("io/github/codex-agent-labs")
        expectedMavenPrimaryPaths(VERSION).forEach { relative ->
            group.resolve(relative).apply {
                parentFile.mkdirs()
                writeText(if (extension == "pom") validPom else relative)
                if (signed) resolveSibling(name + ".asc").writeText("signature")
            }
        }
        generateMavenRelocationPoms(repository.resolve(OLD_MAVEN_GROUP.replace('.', '/')), GROUP, VERSION)
        if (signed) {
            expectedMavenRelocationPaths(VERSION).forEach { relative ->
                repository.resolve(relative).let { it.resolveSibling(it.name + ".asc") }.writeText("signature")
            }
        }
    }

    companion object {
        private const val GROUP = "io.github.codex-agent-labs"
        private const val VERSION = "0.2.0"
        private const val validPom = """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license>
            <name>GNU General Public License v3.0 or later</name>
            <url>https://www.gnu.org/licenses/gpl-3.0.txt</url><distribution>repo</distribution>
            </license></licenses></project>
        """
    }
}
