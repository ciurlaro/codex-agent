import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxArm64RuntimeEvidenceBundleTest {
    @Test
    fun `one bundle executes four backends against shared hash-bound inputs`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            val inputs = inputs(fixture)
            stage(inputs)
            ZipFile(inputs.bundle).use { zip ->
                assertEquals(
                    setOf(
                        "execution.json", "linuxArm64-test.kexe", "codex-app-server-distributions.json",
                        "app-server-linux-arm64.zip", JVM_RUNTIME_RUNNER_ARCHIVE,
                        NODE_RUNTIME_RUNNER_ARCHIVE, NODE_WASM_RUNTIME_RUNNER_ARCHIVE,
                    ),
                    zip.entries().asSequence().map { it.name }.toSet(),
                )
            }
            execute(inputs)
            assertTrue(inputs.desktopEvidence.isFile && inputs.jvmEvidence.isFile)
            assertTrue(fixture.evidence("linuxArm64").isFile)
            assertTrue(fixture.evidence("linuxArm64", NODE_RUNTIME_WASM_BACKEND).isFile)
        }

    @Test
    fun `unified execution is sequential and has no executor`() {
        val source = File("src/main/kotlin/LinuxArm64RuntimeEvidenceBundle.kt").readText()
        val execution = source.substringAfter("val executables =").substringBefore("} finally")
        assertFalse("Executors" in source || "Callable" in source)
        assertTrue(
            listOf(
                "executeLinuxArm64DesktopEvidenceInputs(", "executeJvmRuntimeEvidence(",
                "NODE_RUNTIME_JS_BACKEND", "NODE_RUNTIME_WASM_BACKEND",
            ).map(execution::indexOf).zipWithNext().all { (first, second) -> first >= 0 && first < second },
        )
    }

    @Test
    fun `bundle hash mismatch fails before any backend starts`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            val inputs = inputs(fixture)
            stage(inputs)
            val tampered = inputs.root.resolve("tampered.zip")
            ZipFile(inputs.bundle).use { source ->
                java.util.zip.ZipOutputStream(tampered.outputStream()).use { output ->
                    source.entries().asSequence().forEach { entry ->
                        output.putNextEntry(java.util.zip.ZipEntry(entry.name))
                        if (entry.name == JVM_RUNTIME_RUNNER_ARCHIVE) output.write("tampered".encodeToByteArray())
                        else source.getInputStream(entry).use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            val calls = AtomicInteger()
            assertFailsWith<IllegalStateException> {
                execute(inputs.copy(bundle = tampered), calls)
            }
            assertEquals(0, calls.get())
        }

    @Test
    fun `unexpected native test inventory fails before a test runs`() =
        withNodeRuntimeEvidenceFixture { fixture ->
            val inputs = inputs(fixture)
            stage(inputs)
            val calls = AtomicInteger()
            assertFailsWith<IllegalStateException> {
                execute(inputs, calls, desktopListing() + "  unexpectedTest\n")
            }
            assertEquals(1, calls.get())
            assertFalse(inputs.desktopEvidence.exists())
        }

    private fun inputs(fixture: NodeRuntimeEvidenceFixture): Inputs {
        val root = fixture.root
        val test = root.resolve("linuxArm64-test.kexe").apply { writeText("native-test") }
        val jvm = root.resolve(JVM_RUNTIME_RUNNER_ARCHIVE).apply {
            java.util.zip.ZipOutputStream(outputStream()).use { zip ->
                mapOf(
                    "classes/${JVM_RUNTIME_RUNNER_ENTRYPOINT.replace('.', '/')}.class" to "main",
                    "lib/kotlin-stdlib.jar" to "stdlib",
                ).forEach { (name, value) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name)); zip.write(value.encodeToByteArray()); zip.closeEntry()
                }
            }
        }
        return Inputs(
            root, test, fixture.classifiers.getValue("linuxArm64"), fixture.manifest, jvm,
            fixture.compiled, fixture.compiledWasm, root.resolve("linux-arm64-runtime-evidence.zip"),
            root.resolve(desktopRuntimeEvidenceFileName("linuxArm64")),
            root.resolve("TEST-linuxArm64Test.$DESKTOP_RUNTIME_TEST_CLASS.xml"),
            root.resolve(jvmRuntimeEvidenceFileName("linuxArm64")), fixture,
        )
    }

    private fun stage(input: Inputs) = stageLinuxArm64RuntimeEvidenceBundle(
        COMMIT, input.test, input.classifier, input.manifest, input.jvm, input.js, input.wasm, input.bundle,
    )

    private fun execute(
        input: Inputs,
        calls: AtomicInteger? = null,
        nativeListing: String = desktopListing(),
    ) = executeLinuxArm64RuntimeEvidenceBundle(
        COMMIT, input.bundle, "java", "node", input.desktopEvidence, input.desktopReport, input.jvmEvidence,
        input.fixture.evidence("linuxArm64"), input.fixture.report("linuxArm64"),
        input.fixture.evidence("linuxArm64", NODE_RUNTIME_WASM_BACKEND),
        input.fixture.report("linuxArm64", NODE_RUNTIME_WASM_BACKEND), ARM_ENV,
        desktopRunner = { command, _ ->
            calls?.incrementAndGet()
            if (command.contains("--ktest_list_tests")) DesktopEvidenceProcessResult(0, nativeListing)
            else DesktopEvidenceProcessResult(0, "")
        },
        jvmRunner = { command, _ ->
            calls?.incrementAndGet()
            if (command.last() == "--list-tests") JvmEvidenceProcessResult(0, desktopListing())
            else JvmEvidenceProcessResult(0, "")
        },
        nodeRunner = { command, _ -> calls?.incrementAndGet(); successfulNodeEvidenceResult(command) },
    )

    private data class Inputs(
        val root: File,
        val test: File,
        val classifier: File,
        val manifest: File,
        val jvm: File,
        val js: File,
        val wasm: File,
        val bundle: File,
        val desktopEvidence: File,
        val desktopReport: File,
        val jvmEvidence: File,
        val fixture: NodeRuntimeEvidenceFixture,
    )

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        val ARM_ENV = mapOf("RUNNER_OS" to "Linux", "RUNNER_ARCH" to "ARM64")
        fun desktopListing() = buildString {
            append(DESKTOP_RUNTIME_TEST_CLASS).append(".\n")
            desktopRuntimeTestMethods.forEach { append("  ").append(it).append('\n') }
        }
    }
}
