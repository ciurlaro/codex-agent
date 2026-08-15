import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class JvmRuntimeEvidenceTasksTest {
    @Test
    fun `exact five records bind one runner and both classifier executables`() = withFixture { fixture ->
        val commands = mutableMapOf<String, MutableList<List<String>>>()
        desktopRuntimeEvidenceTargets.keys.forEach { target ->
            fixture.record(target) { command, environment ->
                commands.getOrPut(target, ::mutableListOf) += command
                assertRuntimeBundleEnvironment(environment, target)
                if (command.last() == "--list-tests") JvmEvidenceProcessResult(0, exactListing())
                else JvmEvidenceProcessResult(0, "")
            }
        }

        assertTrue(fixture.validate().isEmpty())
        assertTrue(commands.values.all { commandsForTarget -> commandsForTarget.size == 5 })
        desktopRuntimeEvidenceTargets.forEach { (target, expected) ->
            val record = fixture.evidence(target).readReleaseObject()
            val proof = inspectDesktopClassifier(target, readDesktopCodexManifest(fixture.manifest),
                fixture.classifiers.getValue(target))
            assertEquals(expected.classifier, record.releaseString("classifier"))
            assertEquals(jvmRuntimeEvidenceTestTask(target), record.releaseString("testTask"))
            assertEquals(proof.archiveSha256, record.releaseString("classifierArchiveSha256"))
            assertEquals(proof.binarySha256, record.releaseString("appServerBinarySha256"))
            assertEquals(proof.supervisorSha256, record.releaseString("supervisorBinarySha256"))
            assertEquals(fixture.runner.releaseDigest(), record.releaseString("compiledJvmTestRuntimeSha256"))
        }
        val arm = fixture.evidence("linuxArm64")
        val renamed = arm.readReleaseObject().toMutableMap()
        renamed["classifierArchiveFileName"] = JsonPrimitive("app-server-linux-arm64.zip")
        arm.atomicWriteJson(JsonObject(renamed))
        assertTrue(fixture.validate().isEmpty())
        renamed["classifierArchiveFileName"] = JsonPrimitive("../app-server-linux-arm64.zip")
        arm.atomicWriteJson(JsonObject(renamed))
        assertTrue(fixture.validate().isNotEmpty())
    }

    @Test
    fun `verification rejects tampered evidence classifier and runner`() = withFixture { fixture ->
        fixture.recordAll()
        val evidence = fixture.evidence("linuxX64")
        val originalEvidence = evidence.readBytes()
        val values = evidence.readReleaseObject().toMutableMap()
        values["supervisorBinarySha256"] = JsonPrimitive("f".repeat(64))
        evidence.atomicWriteJson(JsonObject(values))
        assertTrue(fixture.validate().isNotEmpty())
        evidence.writeBytes(originalEvidence)

        val classifier = fixture.classifiers.getValue("linuxX64")
        classifier.appendText("tampered")
        assertTrue(fixture.validate().isNotEmpty())
        fixture.writeClassifier("linuxX64")

        val runner = fixture.runner.readBytes()
        fixture.runner.appendText("tampered")
        assertTrue(fixture.validate().isNotEmpty())
        fixture.runner.writeBytes(runner)
        assertTrue(fixture.validate(evidenceFiles = desktopRuntimeEvidenceTargets.keys.map(fixture::evidence).dropLast(1))
            .isNotEmpty())
    }

    @Test
    fun `execution rejects identity inventory and failing lifecycle cases before writing evidence`() =
        withFixture { fixture ->
            assertFailsWith<IllegalStateException> {
                fixture.record("linuxX64", runnerOs = "macOS") { _, _ -> error("must not run") }
            }
            assertFailsWith<IllegalStateException> {
                fixture.record("linuxX64") { command, _ ->
                    if (command.last() == "--list-tests") JvmEvidenceProcessResult(0, exactListing() + "  extra\n")
                    else JvmEvidenceProcessResult(0, "")
                }
            }
            assertFailsWith<IllegalStateException> {
                fixture.record("linuxX64") { command, _ ->
                    if (command.last() == "--list-tests") JvmEvidenceProcessResult(0, exactListing())
                    else JvmEvidenceProcessResult(1, "failed")
                }
            }
            assertTrue(!fixture.evidence("linuxX64").exists())
        }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = createTempDirectory("jvm-runtime-evidence").toFile()
        try { block(Fixture(root)) } finally { root.deleteRecursively() }
    }

    private class Fixture(val root: File) {
        private val appServer = "official app server".encodeToByteArray()
        private val supervisor = "process supervisor".encodeToByteArray()
        val manifest = writeTestDesktopDistributionManifest(root.resolve("distributions.json"),
            appServer.inputStream().releaseDigest())
        val runner = root.resolve(JVM_RUNTIME_RUNNER_ARCHIVE).apply {
            writeZip(linkedMapOf(
                "classes/${JVM_RUNTIME_RUNNER_ENTRYPOINT.replace('.', '/')}.class" to "main".encodeToByteArray(),
                "lib/kotlin-stdlib.jar" to "stdlib".encodeToByteArray(),
            ))
        }
        val classifiers = desktopRuntimeEvidenceTargets.mapValues { (target, expected) ->
            root.resolve("codex-agent-runtime-desktop-0.2.0-${expected.classifier}.zip")
                .also { writeClassifier(target, it) }
        }

        fun evidence(target: String) = root.resolve(jvmRuntimeEvidenceFileName(target))
        fun recordAll() = desktopRuntimeEvidenceTargets.keys.forEach(::record)
        fun record(
            target: String,
            runnerOs: String = desktopRuntimeEvidenceTargets.getValue(target).runnerOs,
            process: (List<String>, Map<String, String>) -> JvmEvidenceProcessResult = { command, _ ->
                if (command.last() == "--list-tests") JvmEvidenceProcessResult(0, exactListing())
                else JvmEvidenceProcessResult(0, "")
            },
        ) = executeJvmRuntimeEvidence(
            COMMIT, target, runnerOs, desktopRuntimeEvidenceTargets.getValue(target).runnerArch,
            "java", manifest, classifiers.getValue(target), runner, evidence(target), runner = process,
        )

        fun validate(
            evidenceFiles: List<File> = desktopRuntimeEvidenceTargets.keys.map(::evidence),
        ) = validateJvmRuntimeEvidence(evidenceFiles, COMMIT, manifest, classifiers.values.toList(), runner)

        fun writeClassifier(target: String) = writeClassifier(target, classifiers.getValue(target))
        private fun writeClassifier(target: String, output: File) {
            val executable = if (target == "mingwX64") "codex-app-server.exe" else "codex-app-server"
            val supervisorExecutable = if (target == "mingwX64") {
                "codex-process-supervisor.exe"
            } else {
                "codex-process-supervisor"
            }
            val payload = linkedMapOf(
                executable to appServer,
                supervisorExecutable to supervisor,
                "openai-codex-LICENSE.txt" to "license".encodeToByteArray(),
                "openai-codex-NOTICE.txt" to "notice".encodeToByteArray(),
            )
            output.writeZip(payload + ("codex-runtime-manifest.json" to runtimeManifestFixture(
                "0.2.0",
                target,
                desktopRuntimeEvidenceTargets.getValue(target).classifier,
                payload,
                setOf(executable, supervisorExecutable),
            )))
        }
    }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        fun exactListing() = buildString {
            append(DESKTOP_RUNTIME_TEST_CLASS).append(".\n")
            desktopRuntimeTestMethods.forEach { append("  ").append(it).append('\n') }
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
