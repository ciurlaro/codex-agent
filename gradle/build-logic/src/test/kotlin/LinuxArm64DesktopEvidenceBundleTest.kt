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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class LinuxArm64DesktopEvidenceBundleTest {
    @Test
    fun `schema two records and validates the actual logical task for every target`() = withFixture { fixture ->
        val files = desktopRuntimeEvidenceTargets.keys.map { target ->
            fixture.root.resolve(desktopRuntimeEvidenceFileName(target)).apply {
                atomicWriteJson(buildDesktopRuntimeEvidence(DesktopRuntimeEvidenceValues(
                    COMMIT, target, "a".repeat(64), "b".repeat(64), "c".repeat(64),
                )))
            }
        }
        assertTrue(validateDesktopRuntimeEvidence(files, COMMIT).isEmpty())
        val linux = files.single { it.name == desktopRuntimeEvidenceFileName("linuxArm64") }
        val values = linux.readReleaseObject().toMutableMap()
        values["testTask"] = JsonPrimitive(":codex-agent-runtime-desktop:linuxArm64Test")
        linux.atomicWriteJson(JsonObject(values))
        assertTrue(validateDesktopRuntimeEvidence(files, COMMIT).any { it.contains("test task mismatch") })
    }

    @Test
    fun `stage accepts one versionless-discovered classifier and rejects zero or multiple`() = withFixture { fixture ->
        val distributions = fixture.root.resolve("distributions").apply(File::mkdirs)
        assertFailsWith<IllegalStateException> { fixture.stage(distributions) }
        fixture.classifier.copyTo(distributions.resolve(
            "codex-agent-runtime-desktop-0.2.0-app-server-linux-arm64.zip",
        ))
        fixture.stage(distributions)
        assertTrue(fixture.bundle.isFile)
        fixture.classifier.copyTo(distributions.resolve(
            "codex-agent-runtime-desktop-next-app-server-linux-arm64.zip",
        ))
        assertFailsWith<IllegalStateException> { fixture.stage(distributions) }
    }

    @Test
    fun `stage binds exact cross-built inputs and ARM execution emits existing schema two evidence`() = withFixture { fixture ->
        fixture.stage()
        ZipFile(fixture.bundle).use { zip ->
            assertEquals(
                setOf(
                    "execution.json", "linuxArm64-test.kexe", "app-server-linux-arm64.zip",
                    "codex-app-server", "codex-process-supervisor",
                ),
                zip.entries().asSequence().map(ZipEntry::getName).toSet(),
            )
        }
        val commands = mutableListOf<List<String>>()
        fixture.execute { command, environment ->
            commands += command
            assertTrue(File(command.first()).isFile)
            assertTrue(File(environment.getValue("CODEX_AGENT_APP_SERVER_EXECUTABLE")).isFile)
            val supervisor = File(environment.getValue("CODEX_AGENT_PROCESS_SUPERVISOR_EXECUTABLE"))
            assertTrue(supervisor.isFile)
            assertEquals(supervisor.releaseDigest(), environment["CODEX_AGENT_PROCESS_SUPERVISOR_SHA256"])
            if (command.contains("--ktest_list_tests")) DesktopEvidenceProcessResult(0, exactListing())
            else DesktopEvidenceProcessResult(0, "")
        }

        assertEquals(5, commands.size)
        assertEquals(desktopRuntimeTestMethods, commands.drop(1).map {
            it.single { argument -> argument.startsWith("--ktest_filter=") }.substringAfterLast('.')
        }.toSet())
        val evidence = fixture.evidence.readReleaseObject()
        assertEquals(3, evidence.releaseInt("schemaVersion"))
        assertEquals(COMMIT, evidence.releaseString("candidateCommit"))
        assertEquals("linuxArm64", evidence.releaseString("target"))
        assertEquals(fixture.classifier.releaseDigest(), evidence.releaseString("classifierArchiveSha256"))
        verifyDesktopRuntimeTestReport(fixture.report, "linuxArm64")
    }

    @Test
    fun `execution rejects tampered missing extra and unsafe bundle entries before running tests`() = withFixture { fixture ->
        fixture.stage()
        val originals = fixture.bundle.entries()
        val variants = listOf(
            originals + ("linuxArm64-test.kexe" to "tampered".encodeToByteArray()),
            originals - "codex-app-server",
            originals + ("extra" to byteArrayOf()),
            (originals - "codex-app-server") + ("../codex-app-server" to APP_SERVER),
        )
        variants.forEachIndexed { index, entries ->
            val bundle = fixture.root.resolve("bad-$index.zip").apply { writeZip(entries) }
            var executions = 0
            assertFailsWith<IllegalStateException> {
                executeLinuxArm64DesktopEvidenceBundle(
                    COMMIT, bundle, fixture.evidence, fixture.report, ARM_ENV,
                ) { _, _ -> executions++; DesktopEvidenceProcessResult(0, exactListing()) }
            }
            assertEquals(0, executions)
        }
    }

    @Test
    fun `execution rejects wrong commit runner and test inventory`() = withFixture { fixture ->
        fixture.stage()
        listOf(
            "0".repeat(40) to ARM_ENV,
            COMMIT to (ARM_ENV + ("RUNNER_ARCH" to "X64")),
            COMMIT to (ARM_ENV + ("RUNNER_OS" to "macOS")),
        ).forEach { (commit, environment) ->
            assertFailsWith<IllegalStateException> {
                executeLinuxArm64DesktopEvidenceBundle(
                    commit, fixture.bundle, fixture.evidence, fixture.report, environment,
                ) { _, _ -> error("test process must not run") }
            }
        }
        listOf(
            exactListing() + "  extraTest\n",
            exactListing().replace("rejectsWrongTargetChecksum", "wrongTest"),
            exactListing().replace("  rejectsWrongTargetChecksum\n", ""),
        ).forEach { listing ->
            var executions = 0
            assertFailsWith<IllegalStateException> {
                fixture.execute { _, _ ->
                    executions++
                    DesktopEvidenceProcessResult(0, listing)
                }
            }
            assertEquals(1, executions)
        }
    }

    @Test
    fun `execution fails closed on a failed or incomplete exact test run`() = withFixture { fixture ->
        fixture.stage()
        var executions = 0
        assertFailsWith<IllegalStateException> {
            fixture.execute { command, _ ->
                executions++
                if (command.contains("--ktest_list_tests")) DesktopEvidenceProcessResult(0, exactListing())
                else DesktopEvidenceProcessResult(1, "failed")
            }
        }
        assertEquals(2, executions)
        assertTrue(!fixture.evidence.exists())
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = createTempDirectory("linux-arm64-desktop-evidence").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }

    private class Fixture(val root: File) {
        val test = root.resolve("test.kexe").apply { writeText("linked ARM64 test") }
        val classifier = root.resolve("classifier.zip").apply { writeZip(linkedMapOf(
            "codex-app-server" to APP_SERVER,
            "codex-process-supervisor" to SUPERVISOR,
            "openai-codex-LICENSE.txt" to "license".encodeToByteArray(),
            "openai-codex-NOTICE.txt" to "notice".encodeToByteArray(),
        )) }
        val bundle = root.resolve("execution.zip")
        val evidence = root.resolve(desktopRuntimeEvidenceFileName("linuxArm64"))
        val report = root.resolve(
            "TEST-linuxArm64Test.io.github.ciurlaro.codexmobile.appserver.runtime.DesktopCodexRuntimeTest.xml",
        )
        fun stage(classifierInput: File = classifier) =
            stageLinuxArm64DesktopEvidenceBundle(COMMIT, test, classifierInput, bundle)
        fun execute(runner: (List<String>, Map<String, String>) -> DesktopEvidenceProcessResult) =
            executeLinuxArm64DesktopEvidenceBundle(COMMIT, bundle, evidence, report, ARM_ENV, runner)
    }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        val ARM_ENV = mapOf("RUNNER_OS" to "Linux", "RUNNER_ARCH" to "ARM64")
        val APP_SERVER = "official app server".encodeToByteArray()
        val SUPERVISOR = "process supervisor".encodeToByteArray()
        fun exactListing() = buildString {
            append(DESKTOP_RUNTIME_TEST_CLASS).append(".\n")
            desktopRuntimeTestMethods.forEach { append("  ").append(it).append('\n') }
        }
    }
}

private fun File.entries(): LinkedHashMap<String, ByteArray> = ZipFile(this).use { zip ->
    linkedMapOf<String, ByteArray>().apply {
        zip.entries().asSequence().forEach { entry -> put(entry.name, zip.getInputStream(entry).use { it.readBytes() }) }
    }
}

private fun File.writeZip(entries: Map<String, ByteArray>) = ZipOutputStream(outputStream()).use { zip ->
    entries.forEach { (name, bytes) ->
        zip.putNextEntry(ZipEntry(name).apply { setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0)) })
        zip.write(bytes)
        zip.closeEntry()
    }
}
