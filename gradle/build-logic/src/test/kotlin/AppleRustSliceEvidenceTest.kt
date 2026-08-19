import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.Task
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import org.gradle.testfixtures.ProjectBuilder

class AppleRustSliceEvidenceTest {
    @Test
    fun `exact complete evidence validates`() = fixture().use { it.verify() }

    @Test
    fun `tampered archive is rejected`() = fixture().use {
        it.evidence.resolve(appleRustSliceSpecs[0].archiveName).appendText("tampered")
        assertFailsWith<IllegalStateException> { it.verify() }
        Unit
    }

    @Test
    fun `wrong target proof is rejected`() = fixture().use {
        val proof = it.evidence.resolve(appleRustSliceSpecs[0].proofName)
        proof.writeText(proof.readText().replace(IOS_DEVICE_RUST_TARGET, IOS_SIMULATOR_RUST_TARGET))
        assertFailsWith<IllegalStateException> { it.verify() }
        Unit
    }

    @Test
    fun `missing extra and unsafe evidence entries are rejected`() = fixture().use {
        val missing = it.evidence.resolve(appleRustSliceSpecs[1].proofName)
        val bytes = missing.readBytes(); missing.delete()
        assertFailsWith<IllegalStateException> { it.verify() }
        missing.writeBytes(bytes)
        it.evidence.resolve("extra.json").writeText("{}")
        assertFailsWith<IllegalStateException> { it.verify() }
        it.evidence.resolve("extra.json").delete()
        val nested = it.evidence.resolve("nested").apply { mkdir() }
        assertFailsWith<IllegalStateException> { it.verify() }
        nested.delete()
        Unit
    }

    @Test
    fun `test command and identity mismatches are rejected`() = fixture().use {
        assertFailsWith<IllegalStateException> {
            verifyAppleRustEvidenceDirectory(
                it.evidence,
                it.identities.mapValues { (_, identity) -> identity.copy(compilerSettingsSha256 = "f".repeat(64)) },
                it.nativeTestsIdentity,
                it.commands,
            )
        }
        assertFailsWith<IllegalStateException> {
            verifyAppleRustEvidenceDirectory(
                it.evidence,
                it.identities,
                it.nativeTestsIdentity,
                it.commands.dropLast(1),
            )
        }
        Unit
    }

    @Test
    fun `exact compiler and SDK identities are required`() = fixture().use {
        val mismatched = it.identities.toMutableMap()
        mismatched[IOS_DEVICE_RUST_TARGET] = mismatched.getValue(IOS_DEVICE_RUST_TARGET).copy(
            rustCompilerIdentitySha256 = "f".repeat(64),
            appleToolchainIdentitySha256 = "e".repeat(64),
        )
        assertFailsWith<IllegalStateException> {
            verifyAppleRustEvidenceDirectory(it.evidence, mismatched, it.nativeTestsIdentity, it.commands)
        }
        Unit
    }

    @Test
    fun `native input digest is stable and rejects outside input`() {
        val root = createTempDirectory("apple-native-inputs").toFile()
        val outside = createTempDirectory("apple-native-outside").toFile().resolve("input").apply { writeText("x") }
        try {
            val a = root.resolve("native/a").apply { parentFile.mkdirs(); writeText("a") }
            val b = root.resolve("native/b").apply { writeText("b") }
            assertEquals(
                appleNativeInputDigest(root, setOf(a, b)),
                appleNativeInputDigest(root, setOf(b, a)),
            )
            assertFailsWith<IllegalStateException> { appleNativeInputDigest(root, setOf(outside)) }
        } finally {
            root.deleteRecursively(); outside.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `default markers preserve matching Rust builds`() {
        registrationFixture(imported = false).use { fixture ->
            assertEquals(setOf("buildDevice"), directDependencies(fixture.selection.prepareDevice))
            assertEquals(setOf("buildSimulator"), directDependencies(fixture.selection.prepareSimulator))
            assertEquals(fixture.deviceArchive.get().asFile, fixture.selection.deviceArchive.get().asFile)
            assertEquals(fixture.simulatorArchive.get().asFile, fixture.selection.simulatorArchive.get().asFile)
            assertEquals(
                setOf("bridgeTest", "directToolTest"),
                directDependencies(fixture.registeredTask("exportCodexAgentIosNativeTestsProof")),
            )
            assertFalse(
                transitiveDependencies(fixture.registeredTask("exportCodexAgentIosArm64RustSlice"))
                    .any { it in setOf("bridgeTest", "directToolTest") },
            )
        }
    }

    @Test
    fun `import markers never reach Rust build or test tasks`() {
        registrationFixture(imported = true).use { fixture ->
            val reached = transitiveDependencies(fixture.selection.prepareDevice) +
                transitiveDependencies(fixture.selection.prepareSimulator)
            assertTrue("validateImportedCodexAgentIosNativeEvidence" in reached)
            assertTrue("verifyAppleToolchain" in reached)
            assertFalse(reached.any { it in setOf("buildDevice", "buildSimulator", "bridgeTest", "directToolTest") })
            assertTrue(fixture.selection.deviceArchive.get().asFile.path.contains("imported-rust"))
            assertTrue(fixture.selection.simulatorArchive.get().asFile.path.contains("imported-rust"))
        }
    }

    @Test
    fun `evidence tasks always execute live checkout validation`() {
        val source = File("src/main/kotlin/AppleRustSliceTasks.kt").readText()
        assertEquals(3, Regex("init \\{ outputs\\.upToDateWhen \\{ false } }").findAll(source).count())
    }
}

private class EvidenceFixture : AutoCloseable {
    val repository = createTempDirectory("apple-rust-evidence").toFile()
    val evidence = repository.resolve("evidence").apply { mkdirs() }
    private val native = repository.resolve("native/input").apply { parentFile.mkdirs(); writeText("native") }
    private val provenance = repository.resolve("native/provenance.json").apply { writeText("{}") }
    private val xcode = repository.resolve("xcode.txt").apply { writeText("Xcode 26.6") }
    private val swift = repository.resolve("swift.txt").apply { writeText("Swift 6.3.3") }
    private val settings = mapOf("rustToolchain" to "1.95.0", "rustSrcComponent" to "required", "releaseLto" to "thin")
    private val identity = AppleRustEvidenceIdentity(
        "1".repeat(40), "2".repeat(40), appleNativeInputDigest(repository, setOf(native, provenance)),
        provenance.releaseDigest(), appleCompilerSettingsDigest(settings), "1.95.0", "required",
        "3".repeat(64), "4".repeat(64),
        xcode.releaseDigest(), swift.releaseDigest(),
    )
    val identities = appleRustSliceSpecs.associate { spec ->
        spec.target to identity.copy(appleToolchainIdentitySha256 = spec.target.byteInputStream().releaseDigest())
    }
    val nativeTestsIdentity = AppleNativeTestsIdentity(
        "1".repeat(40), "2".repeat(40), appleNativeInputDigest(repository, setOf(native, provenance)),
        provenance.releaseDigest(), "1.95.0", "not-required",
    )
    val commands = listOf(
        AppleNativeTestCommand(":runtime:testBridge", listOf("test", "--locked", "-p", "bridge")),
        AppleNativeTestCommand(":runtime:testDirect", listOf("test", "--locked", "-p", "core", "direct")),
    )

    init {
        appleRustSliceSpecs.forEach { spec ->
            val archive = evidence.resolve(spec.archiveName).apply { writeBytes("!<arch>\n$spec".toByteArray()) }
            evidence.resolve(spec.proofName).atomicWriteJson(
                buildAppleRustSliceProof(spec, archive, identities.getValue(spec.target)),
            )
        }
        evidence.resolve(IOS_NATIVE_TESTS_PROOF).atomicWriteJson(buildAppleNativeTestsProof(nativeTestsIdentity, commands))
    }

    fun verify() = verifyAppleRustEvidenceDirectory(evidence, identities, nativeTestsIdentity, commands)
    override fun close() = repository.deleteRecursively().let { }
}

private class RegistrationFixture(imported: Boolean) : AutoCloseable {
    val directory = createTempDirectory("apple-rust-registration").toFile()
    private val project = ProjectBuilder.builder().withProjectDir(directory).build()
    private fun task(name: String) = project.tasks.register(name)
    private val buildDevice = task("buildDevice")
    private val buildSimulator = task("buildSimulator")
    private val bridgeTest = task("bridgeTest")
    private val directToolTest = task("directToolTest")
    val deviceArchive = project.layout.buildDirectory.file("device/$IOS_RUST_LIBRARY")
    val simulatorArchive = project.layout.buildDirectory.file("simulator/$IOS_RUST_LIBRARY")
    val selection: AppleRustSliceSelection
    fun registeredTask(name: String): TaskProvider<Task> = project.tasks.named(name)

    init {
        task("verifyAppleToolchain")
        val provenance = directory.resolve("native/provenance.json").apply { parentFile.mkdirs(); writeText("{}") }
        val input = directory.resolve("native/input").apply { writeText("input") }
        val evidence: Provider<Directory>? = if (imported) project.layout.dir(project.provider {
            directory.resolve("evidence")
        }) else null
        selection = project.registerAppleRustSliceReuse(AppleRustSliceRegistrationInputs(
            project.provider { "1".repeat(40) }, evidence, project.files(input, provenance),
            project.provider { project.layout.projectDirectory.file("native/provenance.json") },
            mapOf("rustToolchain" to "1.95.0", "rustSrcComponent" to "required"),
            project.provider { "rustc 1.95.0" },
            appleRustSliceSpecs.associate { spec -> spec.target to project.provider { "xcode:${spec.target}" } },
            buildDevice, buildSimulator, bridgeTest, directToolTest, deviceArchive, simulatorArchive,
        ))
    }
    override fun close() = directory.deleteRecursively().let { }
}

private fun fixture() = EvidenceFixture()
private fun registrationFixture(imported: Boolean) = RegistrationFixture(imported)
private fun directDependencies(task: TaskProvider<out Task>) =
    task.get().taskDependencies.getDependencies(task.get()).map(Task::getName).toSet()
private fun transitiveDependencies(task: TaskProvider<out Task>): Set<String> {
    val seen = linkedSetOf<String>()
    fun visit(value: Task) {
        value.taskDependencies.getDependencies(value).forEach { dependency ->
            if (seen.add(dependency.name)) visit(dependency)
        }
    }
    visit(task.get())
    return seen
}
