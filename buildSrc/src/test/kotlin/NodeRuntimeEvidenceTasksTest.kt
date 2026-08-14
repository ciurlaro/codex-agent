import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class NodeRuntimeEvidenceTasksTest {
    @Test
    fun `Node owns host tests while buildSrc owns split ARM execution`() {
        assertEquals(
            ":buildSrc:executeLinuxArm64NodeRuntimeEvidenceBundle",
            nodeRuntimeEvidenceTestTask("linuxArm64"),
        )
        assertTrue((desktopRuntimeEvidenceTargets.keys - "linuxArm64").all {
            nodeRuntimeEvidenceTestTask(it).startsWith(":codex-agent-runtime-node:")
        })
    }

    @Test
    fun `exact five records bind runtime tests artifacts and Windows supervisor`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            val commands = mutableMapOf<String, MutableList<List<String>>>()
            desktopRuntimeEvidenceTargets.keys.forEach { target ->
                fixture.record(target) { command, environment ->
                    commands.getOrPut(target, ::mutableListOf) += command
                    if (command.last() != "--version") {
                        assertEquals(target, environment["CODEX_AGENT_DESKTOP_TARGET"])
                        assertTrue(File(environment.getValue("CODEX_AGENT_APP_SERVER_EXECUTABLE")).isFile)
                        if (target == "mingwX64") {
                            assertEquals(fixture.supervisor.absolutePath,
                                environment["CODEX_AGENT_WINDOWS_SUPERVISOR"])
                        } else assertNull(environment["CODEX_AGENT_WINDOWS_SUPERVISOR"])
                    }
                    successfulNodeEvidenceResult(command)
                }
            }

            assertTrue(fixture.validate().isEmpty())
            assertTrue(commands.values.all { it.size == 6 })
            desktopRuntimeEvidenceTargets.forEach { (target, expected) ->
                val report = fixture.evidence(target).readReleaseObject()
                assertEquals(1, report.releaseInt("schemaVersion"))
                assertEquals(NODE_EVIDENCE_COMMIT, report.releaseString("candidateCommit"))
                assertEquals(expected.classifier, report.releaseString("classifier"))
                assertEquals(expected.runnerOs, report.releaseString("runnerOs"))
                assertEquals(expected.runnerArch, report.releaseString("runnerArch"))
                assertEquals(PINNED_NODE_VERSION, report.releaseString("nodeVersion"))
                assertEquals(nodeRuntimeEvidenceTestTask(target), report.releaseString("testTask"))
                assertEquals(NODE_RUNTIME_TEST_CLASS, report.releaseString("testClass"))
                assertEquals(nodeRuntimeTestMethods, report.releaseArray("testMethods")
                    .map { it.jsonPrimitive.content }.toSet())
                assertEquals(nodeRuntimeTestMethods.size, report.releaseInt("tests"))
                assertEquals(0, report.releaseInt("skipped"))
                assertEquals(0, report.releaseInt("failures"))
                assertEquals(0, report.releaseInt("errors"))
                assertEquals(fixture.classifiers.getValue(target).releaseDigest(),
                    report.releaseString("classifierArchiveSha256"))
                assertEquals(fixture.compiled.releaseDigest(),
                    report.releaseString("compiledNodeTestRuntimeSha256"))
                if (target == "mingwX64") assertEquals(fixture.supervisor.releaseDigest(),
                    report.releaseString("windowsSupervisorSha256"))
                else assertNull(report.releaseStringOrNull("windowsSupervisorSha256"))
            }
        }

    @Test
    fun `verification rejects every bound identity result and hash field`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            fixture.recordAll()
            val file = fixture.evidence("macosArm64")
            val original = file.readBytes()
            val mutations = listOf<(MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit>(
                { it["schemaVersion"] = JsonPrimitive(2) },
                { it["candidateCommit"] = JsonPrimitive("f".repeat(40)) },
                { it["target"] = JsonPrimitive("linuxX64") },
                { it["classifier"] = JsonPrimitive("wrong") },
                { it["runnerOs"] = JsonPrimitive("Linux") },
                { it["runnerArch"] = JsonPrimitive("X64") },
                { it["nodeVersion"] = JsonPrimitive("24.18.1") },
                { it["testTask"] = JsonPrimitive(":wrong") },
                { it["testClass"] = JsonPrimitive("wrong") },
                { it["testMethods"] = JsonArray(emptyList()) },
                { it["tests"] = JsonPrimitive(3) },
                { it["skipped"] = JsonPrimitive(1) },
                { it["failures"] = JsonPrimitive(1) },
                { it["errors"] = JsonPrimitive(1) },
                { it["classifierArchiveBytes"] = JsonPrimitive(1) },
                { it["classifierArchiveSha256"] = JsonPrimitive("f".repeat(64)) },
                { it["appServerBinarySha256"] = JsonPrimitive("f".repeat(64)) },
                { it["compiledNodeTestRuntimeBytes"] = JsonPrimitive(1) },
                { it["compiledNodeTestRuntimeSha256"] = JsonPrimitive("f".repeat(64)) },
                { it["windowsSupervisorSha256"] = JsonPrimitive("f".repeat(64)) },
                { it["result"] = JsonPrimitive("failed") },
                { it["unexpected"] = JsonPrimitive(true) },
            )
            mutations.forEach { mutate ->
                val values = file.readReleaseObject().toMutableMap()
                mutate(values)
                file.atomicWriteJson(JsonObject(values))
                assertTrue(fixture.validate().isNotEmpty())
                file.writeBytes(original)
            }
        }

    @Test
    fun `verification rejects incomplete mismatched and tampered artifact sets`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            fixture.recordAll()
            val evidence = desktopRuntimeEvidenceTargets.keys.map(fixture::evidence)
            assertTrue(fixture.validate(evidence.dropLast(1)).isNotEmpty())
            assertTrue(fixture.validate(evidence + fixture.root.resolve("extra.json").apply { writeText("{}") }).isNotEmpty())
            assertTrue(fixture.validate(classifierFiles = fixture.classifiers.values.toList().dropLast(1)).isNotEmpty())
            assertTrue(fixture.validate(supervisorFile = null).isNotEmpty())

            val compiled = fixture.compiled.readBytes()
            fixture.compiled.appendText("tampered")
            assertTrue(fixture.validate().isNotEmpty())
            fixture.compiled.writeBytes(compiled)
            fixture.compiled.nodeEvidenceWriteZip(mapOf("dependency.js" to "missing entry".encodeToByteArray()))
            assertTrue(fixture.validate().isNotEmpty())
            fixture.compiled.writeBytes(compiled)

            val supervisor = fixture.supervisor.readBytes()
            fixture.supervisor.appendText("tampered")
            assertTrue(fixture.validate().isNotEmpty())
            fixture.supervisor.writeBytes(supervisor)

            val archive = fixture.classifiers.getValue("linuxX64")
            val archiveBytes = archive.readBytes()
            archive.appendText("tampered")
            assertTrue(fixture.validate().isNotEmpty())
            archive.writeBytes(archiveBytes)
        }

    @Test
    fun `execution fails closed on runner Node inventory test and supervisor mismatches`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            fun execute(target: String, os: String, arch: String, supervisor: File?,
                        runner: (List<String>, Map<String, String>) -> NodeEvidenceProcessResult) =
                executeNodeRuntimeEvidence(
                    NODE_EVIDENCE_COMMIT, target, os, arch, "node", fixture.manifest,
                    fixture.classifiers.getValue(target), fixture.compiled, supervisor,
                    fixture.evidence(target), fixture.report(target), runner,
                )

            var calls = 0
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", "macOS", "X64", null) { _, _ -> calls++; error("must not run") }
            }
            assertEquals(0, calls)
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", "Linux", "X64", fixture.supervisor) { _, _ -> error("must not run") }
            }
            assertFailsWith<IllegalStateException> {
                execute("mingwX64", "Windows", "X64", null) { _, _ -> error("must not run") }
            }
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", "Linux", "X64", null) { command, _ ->
                    if (command.last() == "--version") NodeEvidenceProcessResult(0, "v24.18.1")
                    else successfulNodeEvidenceResult(command)
                }
            }
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", "Linux", "X64", null) { command, _ ->
                    if (command.last() == "--list-tests") NodeEvidenceProcessResult(0,
                        exactNodeEvidenceListing() + "  unexpected\n")
                    else successfulNodeEvidenceResult(command)
                }
            }
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", "Linux", "X64", null) { command, _ ->
                    if (command.last().startsWith("--run-test=")) NodeEvidenceProcessResult(1, "failed")
                    else successfulNodeEvidenceResult(command)
                }
            }
            assertTrue(!fixture.evidence("linuxX64").exists())
        }
}
