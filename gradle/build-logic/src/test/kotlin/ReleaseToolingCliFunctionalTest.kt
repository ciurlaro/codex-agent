import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseToolingCliFunctionalTest {
    private val repository = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .first { it.resolve(".github/workflows/release-candidate.yml").isFile }
    private val jar = File(checkNotNull(System.getProperty("codexAgent.releaseToolingJar")))

    @Test
    fun `packaged release tool runs without Gradle from an empty directory`() {
        val workingDirectory = createTempDirectory("release-tooling-cli").toFile()
        try {
            val (exit, output) = runTool(workingDirectory, "self-check")
            assertEquals(0, exit, output)
            assertEquals("codex-agent release tooling is ready", output.trim())
            assertTrue(workingDirectory.listFiles().isNullOrEmpty())
            val (centralExit, centralOutput) = runTool(
                workingDirectory,
                "central-prepare",
                "--bundle", workingDirectory.resolve("missing.zip").absolutePath,
                "--candidate", workingDirectory.resolve("missing.json").absolutePath,
                "--record", workingDirectory.resolve("record.json").absolutePath,
                "--allow-new-upload", "true",
            )
            assertTrue(centralExit != 0)
            assertTrue("Central bundle and candidate manifest must be files" in centralOutput)
            assertFalse("NoClassDefFoundError" in centralOutput || "ClassNotFoundException" in centralOutput)
            assertTrue(jar.length() in 1..8_000_000)
            ZipFile(jar).use { archive ->
                val entries = archive.entries().asSequence().map { it.name }.toList()
                assertTrue("ReleaseToolingCliKt.class" in entries)
                assertFalse("ReleaseToolingGradleTasksKt.class" in entries)
                assertFalse(entries.any { it.startsWith("org/gradle/") || it.startsWith("com/android/") })
                assertFalse(entries.any { it.startsWith("gradle/kotlin/dsl/") })
            }
            val jdepsName = if (System.getProperty("os.name").startsWith("Windows")) "jdeps.exe" else "jdeps"
            val jdeps = File(System.getProperty("java.home"), "bin/$jdepsName")
            val modules = ProcessBuilder(jdeps.absolutePath, "--print-module-deps", jar.absolutePath)
                .redirectErrorStream(true)
                .start()
            val moduleOutput = modules.inputStream.bufferedReader().use { it.readText() }
            assertEquals(0, modules.waitFor(), moduleOutput)
            assertEquals("java.base,java.net.http,java.xml", moduleOutput.trim())
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    @Test
    fun `packaged tool stages canonical Maven bytes from a fresh directory`() {
        val root = createTempDirectory("release-tooling-maven").toFile()
        try {
            val commit = "0123456789abcdef0123456789abcdef01234567"
            val version = "0.2.0"
            val promoted = root.resolve("promoted")
            val output = root.resolve("output")
            val repositories = promotedMavenArtifactOwnership.keys.associateWith { target ->
                promoted.resolve("codex-agent-promoted-consumer-$target-$commit/payload/maven").apply { mkdirs() }
            }
            val owners = canonicalPromotedMavenOwners()
            val group = CodexAgentBuild.MAVEN_GROUP.replace('.', '/')
            expectedMavenPrimaryPaths(version).forEach { relative ->
                val source = repositories.getValue(owners.getValue(relative.substringBefore('/')))
                    .resolve("$group/$relative")
                source.parentFile.mkdirs()
                source.writeText(relative)
                source.resolveSibling(source.name + ".sha256").writeText("verification-only checksum")
            }
            expectedMavenRelocationPaths(version).forEach { relative ->
                repositories.getValue("common").resolve(relative).apply {
                    parentFile.mkdirs()
                    writeText(relative)
                }
            }
            val result = runTool(
                root,
                "stage-promoted-maven",
                "--promoted", promoted.absolutePath,
                "--commit", commit,
                "--version", version,
                "--output", output.absolutePath,
            )
            assertEquals(0, result.first, result.second)
            assertEquals(
                expectedMavenPrimaryPaths(version).size + expectedMavenRelocationPaths(version).size,
                output.walkTopDown().count(File::isFile),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `packaged tool exposes every protected workflow command`() {
        val workingDirectory = createTempDirectory("release-tooling-commands").toFile()
        try {
            listOf(
                "stage-promoted-maven",
                "assemble-promoted-candidate",
                "verify-candidate",
                "central-prepare",
                "central-await",
                "central-release",
            ).forEach { command ->
                val (exit, output) = runTool(workingDirectory, command)
                assertTrue(exit != 0, command)
                assertTrue("Unexpected release-tooling options" in output, "$command: $output")
                assertFalse("NoClassDefFoundError" in output || "ClassNotFoundException" in output, command)
                assertFalse("Unknown release-tooling command" in output, command)
            }
        } finally {
            workingDirectory.deleteRecursively()
        }
    }

    @Test
    fun `candidate and publication execute only the receipt-bound packaged tool`() {
        val candidate = repository.resolve(".github/workflows/release-candidate.yml").readText()
        val publication = repository.resolve(".github/workflows/publish.yml").readText()
        listOf(candidate, publication).forEach { workflow ->
            assertFalse("./gradlew" in workflow)
            assertFalse("kotlinc" in workflow || "javac" in workflow)
            assertTrue("java -jar \"\$RELEASE_TOOL\"" in workflow)
        }
        assertTrue(candidate.indexOf("expected_sha256") < candidate.indexOf("java -jar \"\$RELEASE_TOOL\""))
        assertTrue(publication.indexOf("release_tool_sha256") < publication.indexOf("java -jar \"\$RELEASE_TOOL\""))
        assertTrue("assemble-promoted-candidate" in candidate)
        assertTrue("verify-candidate" in publication)
        listOf("central-prepare", "central-await", "central-release").forEach {
            assertTrue(it in publication)
        }
    }

    @Test
    fun `manifest carries the exact tool and portable alone owns runtime runner primaries`() {
        val promoted = repository.resolve(
            "gradle/build-logic/src/main/kotlin/PromotedCandidateTasks.kt",
        ).readText()
        val manifest = repository.resolve(
            "gradle/build-logic/src/main/kotlin/CandidateManifestValidation.kt",
        ).readText()
        val driver = repository.resolve("ci/run-lane.sh").readText()
        val staging = repository.resolve("ci/stage.py").readText()
        assertTrue("put(\"releaseTooling\", releaseTooling.releaseRecord())" in promoted)
        assertTrue("\"releaseTooling\" to RELEASE_TOOLING_FILE_NAME" in manifest)
        assertTrue("val nodeRunnerSource = portable.one(\"node-js-runner\")" in promoted)
        assertTrue("val nodeWasmRunnerSource = portable.one(\"node-wasm-runner\")" in promoted)
        assertFalse("lanes.getValue(\"node-js\").one(\"node-js-runner\")" in promoted)
        assertFalse("lanes.getValue(\"node-wasm\").one(\"node-wasm-runner\")" in promoted)
        listOf(
            "packageJvmRuntimeEvidenceRunner",
            "packageNodeRuntimeEvidenceRunner",
            "packageNodeWasmRuntimeEvidenceRunner",
        ).forEach { task -> assertEquals(1, Regex(Regex.escape(task)).findAll(driver).count(), task) }
        assertTrue(":codex-agent-runtime-node:jsNodeTest" in driver)
        assertTrue(":codex-agent-runtime-node:wasmJsNodeTest" in driver)
        listOf(
            "codex-agent-jvm-runtime-evidence-runner.zip",
            "codex-agent-node-runtime-evidence-runner.zip",
            "codex-agent-node-wasm-runtime-evidence-runner.zip",
        ).forEach { archive -> assertEquals(1, Regex(Regex.escape(archive)).findAll(staging).count(), archive) }
    }

    private fun runTool(directory: File, vararg arguments: String): Pair<Int, String> {
        val java = File(System.getProperty("java.home"), "bin/java")
        val process = ProcessBuilder(java.absolutePath, "-jar", jar.absolutePath, *arguments)
            .directory(directory)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return process.waitFor() to output
    }
}
