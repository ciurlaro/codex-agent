import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CentralBundleTasksTest {
    @Test
    fun `bundle is deterministic sorted and excludes only repository metadata and signature checksums`() = withFixture { fixture ->
        fixture.repository.resolve("b/file.pom").write("pom")
        fixture.repository.resolve("a/file.jar").write("jar")
        fixture.repository.resolve("a/file.jar.asc").write("sig")
        fixture.repository.resolve("a/file.jar.asc.sha256").write("sig checksum")
        fixture.repository.resolve("a/maven-metadata.xml").write("metadata")

        buildCentralBundle(fixture.repository, fixture.inventory, fixture.bundleA, fixture.reportA, 1_000_000)
        buildCentralBundle(fixture.repository, fixture.inventory, fixture.bundleB, fixture.reportB, 1_000_000)

        assertContentEquals(fixture.bundleA.readBytes(), fixture.bundleB.readBytes())
        ZipFile(fixture.bundleA).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertEquals(names.sorted(), names)
            assertTrue("a/file.jar.asc" in names)
            assertFalse("a/file.jar.asc.sha256" in names)
            assertFalse("a/maven-metadata.xml" in names)
        }
        assertTrue(fixture.reportA.readReleaseObject().releaseBoolean("belowCentralPortalUploadLimit"))
    }

    @Test
    fun `bundle must be strictly below the portal limit`() = withFixture { fixture ->
        fixture.repository.resolve("file.jar").write("payload")
        buildCentralBundle(fixture.repository, fixture.inventory, fixture.bundleA, fixture.reportA, 1_000_000)
        assertFailsWith<IllegalStateException> {
            buildCentralBundle(fixture.repository, fixture.inventory, fixture.bundleB, fixture.reportB, fixture.bundleA.length())
        }
    }

    @Test
    fun `bundle cannot be written inside staged repository`() = withFixture { fixture ->
        fixture.repository.resolve("file.jar").write("payload")
        assertFailsWith<IllegalStateException> {
            buildCentralBundle(
                fixture.repository,
                fixture.inventory,
                fixture.repository.resolve("bundle.zip"),
                fixture.reportA,
                1_000_000,
            )
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val directory = createTempDirectory("central-bundle").toFile()
        try { block(Fixture(directory)) } finally { directory.deleteRecursively() }
    }

    private data class Fixture(val root: File) {
        val repository = root.resolve("repository").apply { mkdirs() }
        val inventory = root.resolve("maven.json").apply { writeText("{}") }
        val bundleA = root.resolve("a.zip")
        val bundleB = root.resolve("b.zip")
        val reportA = root.resolve("a.json")
        val reportB = root.resolve("b.json")
    }
}

private fun File.write(value: String) { parentFile.mkdirs(); writeText(value) }
private fun <T> java.util.Enumeration<T>.toList(): List<T> = buildList { while (hasMoreElements()) add(nextElement()) }
