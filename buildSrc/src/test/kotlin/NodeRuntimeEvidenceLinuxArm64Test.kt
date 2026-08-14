import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NodeRuntimeEvidenceLinuxArm64Test {
    @Test
    fun `split bundle reuses classifier and executes exact Node tests on ARM`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            val bundle = fixture.root.resolve("linux-arm64-node-execution.zip")
            stageLinuxArm64NodeRuntimeEvidenceBundle(
                NODE_EVIDENCE_COMMIT,
                fixture.compiled,
                fixture.classifiers.getValue("linuxArm64"),
                fixture.manifest,
                bundle,
            )
            ZipFile(bundle).use { zip ->
                assertEquals(
                    setOf("execution.json", NODE_RUNTIME_RUNNER_ARCHIVE, "app-server-linux-arm64.zip"),
                    zip.entries().asSequence().map { it.name }.toSet(),
                )
            }
            val commands = mutableListOf<List<String>>()
            executeLinuxArm64NodeRuntimeEvidenceBundle(
                NODE_EVIDENCE_COMMIT,
                bundle,
                fixture.manifest,
                "node",
                fixture.evidence("linuxArm64"),
                fixture.report("linuxArm64"),
                NODE_EVIDENCE_ARM_ENV,
            ) { command, environment ->
                commands += command
                if (command.last() != "--version") {
                    assertEquals("linuxArm64", environment["CODEX_AGENT_DESKTOP_TARGET"])
                }
                successfulNodeEvidenceResult(command)
            }
            assertEquals(6, commands.size)
            assertEquals(nodeRuntimeTestMethods, commands.drop(2).map {
                it.last().substringAfterLast('.')
            }.toSet())
            assertEquals(1, fixture.evidence("linuxArm64").readReleaseObject().releaseInt("schemaVersion"))
            verifyNodeRuntimeTestReport(fixture.report("linuxArm64"))
        }

    @Test
    fun `split execution rejects bundle commit runner and manifest mismatches before Node`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            val bundle = fixture.root.resolve("linux-arm64-node-execution.zip")
            stageLinuxArm64NodeRuntimeEvidenceBundle(
                NODE_EVIDENCE_COMMIT, fixture.compiled, fixture.classifiers.getValue("linuxArm64"),
                fixture.manifest, bundle,
            )
            fun rejected(commit: String = NODE_EVIDENCE_COMMIT,
                         environment: Map<String, String> = NODE_EVIDENCE_ARM_ENV,
                         manifest: java.io.File = fixture.manifest,
                         input: java.io.File = bundle) {
                var calls = 0
                assertFailsWith<IllegalStateException> {
                    executeLinuxArm64NodeRuntimeEvidenceBundle(
                        commit, input, manifest, "node", fixture.evidence("linuxArm64"),
                        fixture.report("linuxArm64"), environment,
                    ) { _, _ -> calls++; successfulNodeEvidenceResult(listOf("node", "--version")) }
                }
                assertEquals(0, calls)
            }
            rejected(commit = "f".repeat(40))
            rejected(environment = NODE_EVIDENCE_ARM_ENV + ("RUNNER_ARCH" to "X64"))
            val changedManifest = fixture.root.resolve("changed.json").apply {
                writeBytes(fixture.manifest.readBytes()); appendText("\n")
            }
            rejected(manifest = changedManifest)

            val originals = bundle.nodeEvidenceZipEntries()
            listOf(
                originals + (NODE_RUNTIME_RUNNER_ARCHIVE to "tampered".encodeToByteArray()),
                originals - "app-server-linux-arm64.zip",
                originals + ("extra" to byteArrayOf()),
                (originals - NODE_RUNTIME_RUNNER_ARCHIVE) +
                    ("../$NODE_RUNTIME_RUNNER_ARCHIVE" to byteArrayOf()),
            ).forEachIndexed { index, entries ->
                rejected(input = fixture.root.resolve("bad-$index.zip").apply {
                    nodeEvidenceWriteZip(entries)
                })
            }
        }
}
