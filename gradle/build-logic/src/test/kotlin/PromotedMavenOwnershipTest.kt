import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromotedMavenOwnershipTest {
    @Test
    fun `every coordinate has one canonical owner and only its bytes are forwarded`() = withFixture { fixture ->
        val owners = canonicalPromotedMavenOwners()
        assertEquals(
            expectedMavenPrimaryPaths(VERSION).mapTo(sortedSetOf()) { it.substringBefore('/') },
            owners.keys,
        )
        assertEquals("common", owners.getValue("codex-agent-client"))
        assertEquals("common", owners.getValue("codex-agent-client-jvm"))
        assertEquals("ios-device", owners.getValue("codex-agent-runtime-ios"))
        assertEquals("node-js", owners.getValue("codex-agent-runtime-node"))

        val sharedPath = "$GROUP_PATH/codex-agent-client/$VERSION/codex-agent-client-$VERSION.jar"
        fixture.repositories.getValue("android").resolve(sharedPath).apply {
            parentFile.mkdirs()
            writeText("independent duplicate that must not be compared or forwarded")
        }
        stageCanonicalPromotedMavenPrimaries(fixture.promoted, COMMIT, VERSION, fixture.output)

        assertEquals("common:$sharedPath", fixture.output.resolve(sharedPath).readText())
        val expected = expectedMavenPrimaryPaths(VERSION).mapTo(sortedSetOf()) { "$GROUP_PATH/$it" } +
            expectedMavenRelocationPaths(VERSION)
        assertEquals(
            expected,
            fixture.output.walkTopDown().filter(File::isFile)
                .mapTo(sortedSetOf()) { it.relativeTo(fixture.output).invariantSeparatorsPath },
        )
    }

    @Test
    fun `duplicate canonical ownership is rejected instead of comparing output bytes`() {
        val conflicting = promotedMavenArtifactOwnership.mapValuesTo(linkedMapOf()) { it.value.toSet() }
        conflicting["android"] = conflicting.getValue("android") + "codex-agent-client"
        val failure = assertFailsWith<IllegalStateException> { canonicalPromotedMavenOwners(conflicting) }
        assertTrue(failure.message.orEmpty().contains("Duplicate canonical Maven ownership"))
    }

    @Test
    fun `candidate forwards canonical primaries without comparing consumer repositories`() {
        val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { it.resolve(".github/workflows/release-candidate.yml").isFile }
        val workflow = repository.resolve(".github/workflows/release-candidate.yml").readText()
        assertTrue("java -jar \"${'$'}RELEASE_TOOL\" stage-promoted-maven" in workflow)
        assertTrue(workflow.indexOf("stage-promoted-maven") < workflow.indexOf("gpg --batch --import"))
        assertFalse(Regex("(?m)\\bcmp(?:\\s|$)").containsMatchIn(workflow))
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = createTempDirectory("promoted-maven-ownership").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }

    private class Fixture(root: File) {
        val promoted = root.resolve("promoted")
        val output = root.resolve("canonical")
        val repositories = promotedMavenArtifactOwnership.keys.associateWith { target ->
            promoted.resolve("codex-agent-promoted-consumer-$target-$COMMIT/payload/maven").apply { mkdirs() }
        }

        init {
            val owners = canonicalPromotedMavenOwners()
            expectedMavenPrimaryPaths(VERSION).forEach { relative ->
                val artifactId = relative.substringBefore('/')
                val owner = owners.getValue(artifactId)
                val path = "$GROUP_PATH/$relative"
                repositories.getValue(owner).resolve(path).apply {
                    parentFile.mkdirs()
                    writeText("$owner:$path")
                }
            }
            expectedMavenRelocationPaths(VERSION).forEach { relative ->
                repositories.getValue("common").resolve(relative).apply {
                    parentFile.mkdirs()
                    writeText("common:$relative")
                }
            }
            repositories.values.forEach { repository ->
                repository.resolve("$GROUP_PATH/maven-metadata.xml").apply {
                    parentFile.mkdirs()
                    writeText("verification-only metadata")
                }
            }
        }
    }

    companion object {
        private const val VERSION = "0.2.0"
        private const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        private val GROUP_PATH = CodexAgentBuild.MAVEN_GROUP.replace('.', '/')
    }
}
