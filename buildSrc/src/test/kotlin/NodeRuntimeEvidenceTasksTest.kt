import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

class NodeRuntimeEvidenceTasksTest {
    @Test
    fun `Node owns host tests while buildSrc owns split ARM execution`() {
        nodeRuntimeBackends.forEach { backend ->
            assertEquals(
                ":buildSrc:executeLinuxArm64NodeRuntimeEvidenceBundle",
                nodeRuntimeEvidenceTestTask("linuxArm64", backend),
            )
            val prefix = if (backend == NODE_RUNTIME_JS_BACKEND) "nodeRuntime" else "nodeWasmRuntime"
            assertTrue((desktopRuntimeEvidenceTargets.keys - "linuxArm64").all {
                nodeRuntimeEvidenceTestTask(it, backend).startsWith(":codex-agent-runtime-node:$prefix")
            })
        }
    }

    @Test
    fun `exact JS and Wasm records bind both classifier executables and runner`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            val commands = mutableMapOf<Pair<String, String>, MutableList<List<String>>>()
            nodeRuntimeBackends.forEach { backend ->
                desktopRuntimeEvidenceTargets.keys.forEach { target ->
                    fixture.record(target, backend) { command, environment ->
                        commands.getOrPut(target to backend, ::mutableListOf) += command
                        if (command.last() != "--version") {
                            assertEquals(target, environment["CODEX_AGENT_DESKTOP_TARGET"])
                            assertTrue(File(environment.getValue("CODEX_AGENT_APP_SERVER_EXECUTABLE")).isFile)
                            val supervisor = File(environment.getValue(
                                "CODEX_AGENT_PROCESS_SUPERVISOR_EXECUTABLE",
                            ))
                            assertTrue(supervisor.isFile)
                            assertEquals(supervisor.canonicalFile, supervisor.absoluteFile)
                            assertEquals(
                                supervisor.releaseDigest(),
                                environment["CODEX_AGENT_PROCESS_SUPERVISOR_SHA256"],
                            )
                        }
                        successfulNodeEvidenceResult(command)
                    }
                }
                assertTrue(fixture.validate(backend).isEmpty())
            }

            assertTrue(commands.values.all { it.size == 6 })
            nodeRuntimeBackends.forEach { backend ->
                desktopRuntimeEvidenceTargets.forEach { (target, expected) ->
                    val report = fixture.evidence(target, backend).readReleaseObject()
                    val proof = inspectNodeClassifier(target, readDesktopCodexManifest(fixture.manifest),
                        fixture.classifiers.getValue(target))
                    assertEquals(2, report.releaseInt("schemaVersion"))
                    assertEquals(NODE_EVIDENCE_COMMIT, report.releaseString("candidateCommit"))
                    assertEquals(backend, report.releaseString("runtimeBackend"))
                    assertEquals(expected.classifier, report.releaseString("classifier"))
                    assertEquals(expected.runnerOs, report.releaseString("runnerOs"))
                    assertEquals(expected.runnerArch, report.releaseString("runnerArch"))
                    assertEquals(PINNED_NODE_VERSION, report.releaseString("nodeVersion"))
                    assertEquals(nodeRuntimeEvidenceTestTask(target, backend), report.releaseString("testTask"))
                    assertEquals(NODE_RUNTIME_TEST_CLASS, report.releaseString("testClass"))
                    assertEquals(nodeRuntimeTestMethods, report.releaseArray("testMethods")
                        .map { it.jsonPrimitive.content }.toSet())
                    assertEquals(nodeRuntimeTestMethods.size, report.releaseInt("tests"))
                    assertEquals(proof.binarySha256, report.releaseString("appServerBinarySha256"))
                    assertEquals(proof.supervisorSha256, report.releaseString("processSupervisorSha256"))
                    assertEquals(fixture.runnerArchive(backend).releaseDigest(),
                        report.releaseString("compiledNodeTestRuntimeSha256"))
                }
            }
        }

    @Test
    fun `runner archives preserve JS contract and require exact Wasm members`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            assertEquals(
                setOf(NODE_RUNTIME_RUNNER_ENTRY, "kotlin-kotlin-stdlib.js"),
                inspectNodeRuntimeRunnerArchive(fixture.compiled, NODE_RUNTIME_JS_BACKEND).toSet(),
            )
            assertEquals(
                nodeWasmRuntimeRunnerEntries,
                inspectNodeRuntimeRunnerArchive(fixture.compiledWasm, NODE_RUNTIME_WASM_BACKEND).toSet(),
            )
            val invalid = fixture.root.resolve(NODE_WASM_RUNTIME_RUNNER_ARCHIVE)
            invalid.nodeEvidenceWriteZip(nodeWasmRuntimeRunnerEntries.associateWith { byteArrayOf(1) } +
                ("unexpected.mjs" to byteArrayOf(1)))
            assertFailsWith<IllegalStateException> {
                inspectNodeRuntimeRunnerArchive(invalid, NODE_RUNTIME_WASM_BACKEND)
            }
        }

    @Test
    fun `verification rejects every bound identity result and hash field`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            fixture.recordAll(NODE_RUNTIME_WASM_BACKEND)
            val file = fixture.evidence("macosArm64", NODE_RUNTIME_WASM_BACKEND)
            val original = file.readBytes()
            val mutations = listOf<(MutableMap<String, kotlinx.serialization.json.JsonElement>) -> Unit>(
                { it["schemaVersion"] = JsonPrimitive(1) },
                { it["candidateCommit"] = JsonPrimitive("f".repeat(40)) },
                { it["target"] = JsonPrimitive("linuxX64") },
                { it["runtimeBackend"] = JsonPrimitive("js") },
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
                { it["processSupervisorSha256"] = JsonPrimitive("f".repeat(64)) },
                { it["compiledNodeTestRuntimeBytes"] = JsonPrimitive(1) },
                { it["compiledNodeTestRuntimeSha256"] = JsonPrimitive("f".repeat(64)) },
                { it["result"] = JsonPrimitive("failed") },
                { it["unexpected"] = JsonPrimitive(true) },
            )
            mutations.forEach { mutate ->
                val values = file.readReleaseObject().toMutableMap()
                mutate(values)
                file.atomicWriteJson(JsonObject(values))
                assertTrue(fixture.validate(NODE_RUNTIME_WASM_BACKEND).isNotEmpty())
                file.writeBytes(original)
            }
        }

    @Test
    fun `verification rejects incomplete and tampered artifact sets`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            fixture.recordAll()
            val evidence = desktopRuntimeEvidenceTargets.keys.map(fixture::evidence)
            assertTrue(fixture.validate(evidenceFiles = evidence.dropLast(1)).isNotEmpty())
            assertTrue(fixture.validate(evidenceFiles = evidence +
                fixture.root.resolve("extra.json").apply { writeText("{}") }).isNotEmpty())
            assertTrue(fixture.validate(classifierFiles = fixture.classifiers.values.toList().dropLast(1)).isNotEmpty())

            val compiled = fixture.compiled.readBytes()
            fixture.compiled.appendText("tampered")
            assertTrue(fixture.validate().isNotEmpty())
            fixture.compiled.writeBytes(compiled)
            fixture.compiled.nodeEvidenceWriteZip(mapOf("dependency.js" to "missing entry".encodeToByteArray()))
            assertTrue(fixture.validate().isNotEmpty())
            fixture.compiled.writeBytes(compiled)

            val archive = fixture.classifiers.getValue("linuxX64")
            val archiveBytes = archive.readBytes()
            archive.appendText("tampered")
            assertTrue(fixture.validate().isNotEmpty())
            archive.writeBytes(archiveBytes)
        }

    @Test
    fun `execution fails closed before invalid Node runtime tests`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            fun execute(
                target: String,
                backend: String,
                os: String,
                arch: String,
                compiled: File = fixture.runnerArchive(backend),
                runner: (List<String>, Map<String, String>) -> NodeEvidenceProcessResult,
            ) = executeNodeRuntimeEvidence(
                NODE_EVIDENCE_COMMIT, target, backend, os, arch, "node", fixture.manifest,
                fixture.classifiers.getValue(target), compiled, fixture.evidence(target, backend),
                fixture.report(target, backend), runner,
            )

            var calls = 0
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", "native", "Linux", "X64") { _, _ -> calls++; error("must not run") }
            }
            assertEquals(0, calls)
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", NODE_RUNTIME_JS_BACKEND, "macOS", "X64") { _, _ -> error("must not run") }
            }
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", NODE_RUNTIME_JS_BACKEND, "Linux", "X64", fixture.compiledWasm) { _, _ ->
                    error("must not run")
                }
            }
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", NODE_RUNTIME_JS_BACKEND, "Linux", "X64") { command, _ ->
                    if (command.last() == "--version") NodeEvidenceProcessResult(0, "v24.18.1")
                    else successfulNodeEvidenceResult(command)
                }
            }
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", NODE_RUNTIME_JS_BACKEND, "Linux", "X64") { command, _ ->
                    if (command.last() == "--list-tests") NodeEvidenceProcessResult(
                        0, exactNodeEvidenceListing() + "  unexpected\n",
                    ) else successfulNodeEvidenceResult(command)
                }
            }
            assertFailsWith<IllegalStateException> {
                execute("linuxX64", NODE_RUNTIME_JS_BACKEND, "Linux", "X64") { command, _ ->
                    if (command.last().startsWith("--run-test=")) NodeEvidenceProcessResult(1, "failed")
                    else successfulNodeEvidenceResult(command)
                }
            }
            assertTrue(!fixture.evidence("linuxX64").exists())
        }
}
