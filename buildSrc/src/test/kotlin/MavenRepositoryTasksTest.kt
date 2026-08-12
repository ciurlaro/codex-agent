import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MavenRepositoryTasksTest {
    @Test
    fun `exact signed 22-coordinate 133-primary publication passes`() = withRepository { repository, inventory ->
        writeExactRepository(repository, signed = true)
        verifyMavenRepository(repository, GROUP, VERSION, true, inventory)
        val report = inventory.readReleaseObject()
        assertEquals(133, report.releaseInt("primaryArtifactCount"))
        assertEquals(22, report.releaseArray("artifactIds").size)
        expectedMavenPrimaryPaths(VERSION).forEach { relative ->
            val primary = repository.resolve("io/github/ciurlaro/$relative")
            assertEquals(primary.releaseDigest("MD5"), primary.resolveSibling(primary.name + ".md5").readText().trim())
            assertEquals(primary.releaseDigest("SHA-512"), primary.resolveSibling(primary.name + ".sha512").readText().trim())
        }
    }

    @Test
    fun `missing module or target binary is rejected`() = withRepository { repository, inventory ->
        writeExactRepository(repository)
        val group = repository.resolve("io/github/ciurlaro")
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
        val group = repository.resolve("io/github/ciurlaro")
        group.resolve("unexpected/0.2.0/unexpected-0.2.0.jar").apply { parentFile.mkdirs(); writeText("x") }
        assertFailsWith<IllegalStateException> { verifyMavenRepository(repository, GROUP, VERSION, false, inventory) }
    }

    @Test
    fun `arbitrary Maven sidecar is rejected`() = withRepository { repository, inventory ->
        writeExactRepository(repository)
        val primary = repository.resolve("io/github/ciurlaro/${expectedMavenPrimaryPaths(VERSION).first()}")
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
        val primary = repository.resolve("io/github/ciurlaro/${expectedMavenPrimaryPaths(VERSION).first()}")
        primary.resolveSibling(primary.name + ".asc").delete()
        val failure = assertFailsWith<IllegalStateException> {
            verifyMavenRepository(repository, GROUP, VERSION, true, inventory)
        }
        assertTrue(failure.message.orEmpty().contains(".asc is missing"))
    }

    @Test
    fun `every POM requires exact GPL metadata`() = withRepository { repository, inventory ->
        writeExactRepository(repository)
        val pom = repository.resolve("io/github/ciurlaro/codex-agent-client/0.2.0/codex-agent-client-0.2.0.pom")
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
        val group = repository.resolve("io/github/ciurlaro")
        expectedMavenPrimaryPaths(VERSION).forEach { relative ->
            group.resolve(relative).apply {
                parentFile.mkdirs()
                writeText(if (extension == "pom") validPom else relative)
                if (signed) resolveSibling(name + ".asc").writeText("signature")
            }
        }
    }

    companion object {
        private const val GROUP = "io.github.ciurlaro"
        private const val VERSION = "0.2.0"
        private const val validPom = """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license>
            <name>GNU General Public License v3.0 or later</name>
            <url>https://www.gnu.org/licenses/gpl-3.0.txt</url><distribution>repo</distribution>
            </license></licenses></project>
        """
    }
}
