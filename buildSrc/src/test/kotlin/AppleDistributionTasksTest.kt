import java.io.File
import kotlin.io.path.createTempDirectory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AppleDistributionTasksTest {
    @Test
    fun `structured simulator JSON selects exact available runtime and device`() {
        val selection = selectSimulator(
            """{"runtimes":[{"name":"iOS 26.5","isAvailable":true,"identifier":"runtime-1"}]}""",
            """{"devices":{"runtime-1":[{"isAvailable":true,"deviceTypeIdentifier":"iphone-17","udid":"device-1","state":"Shutdown"}]}}""",
            "iOS 26.5",
            "iphone-17",
        )
        assertEquals(SimulatorSelection("runtime-1", "device-1", "Shutdown"), selection)
        assertEquals(
            SimulatorStatus(true, "Shutdown"),
            simulatorStatus(
                """{"devices":{"runtime-1":[{"isAvailable":true,"udid":"device-1","state":"Shutdown"}]}}""",
                "runtime-1",
                "device-1",
            ),
        )
        assertEquals(
            null,
            simulatorStatus("""{"devices":{"runtime-1":[]}}""", "runtime-1", "device-1"),
        )
        val missing = """{"devices":{"runtime-1":[]}}"""
        assertTrue(shouldRetryDisappearedSimulator(missing, "runtime-1", "device-1", 0))
        assertTrue(shouldRetryDisappearedSimulator("""{"devices":{}}""", "runtime-1", "device-1", 0))
        assertFalse(shouldRetryDisappearedSimulator(missing, "runtime-1", "device-1", 1))
        assertFalse(shouldRetryDisappearedSimulator("invalid", "runtime-1", "device-1", 0))
        assertFailsWith<IllegalStateException> {
            selectSimulator(
                """{"runtimes":[]}""",
                """{"devices":{}}""",
                "iOS 26.5",
                "iphone-17",
            )
        }
    }

    @Test
    fun `xcresult summary requires exactly 23 nonfailing tests`() {
        val summary = parseSwiftTestSummary("""{"totalTestCount":23,"failedTests":0}""")
        assertEquals(SwiftTestSummary(23, 0), summary)
        verifySwiftTestSummary(summary, 23)
        assertFailsWith<IllegalStateException> { verifySwiftTestSummary(SwiftTestSummary(22, 0), 23) }
        assertFailsWith<IllegalStateException> { verifySwiftTestSummary(SwiftTestSummary(23, 1), 23) }
        assertFailsWith<IllegalStateException> { verifySwiftTestSummary(SwiftTestSummary(0, 0), 0) }
    }

    @Test
    fun `process arguments are explicit and failures retain stderr`() {
        val root = File("/tmp/release args")
        assertEquals(
            listOf(
                "/usr/bin/xcrun", "libtool", "-static", "-D", "-no_warning_for_no_symbols",
                "/tmp/release args/CodexAgent", "-o", "/tmp/release args/CodexAgent.normalized",
            ),
            libtoolNormalizeCommand(root.resolve("CodexAgent"), root.resolve("CodexAgent.normalized")),
        )
        assertEquals(
            listOf(
                "/usr/bin/xcrun", "strip", "-S", "-x", "-o",
                "/tmp/release args/CodexAgent.stripped", "/tmp/release args/CodexAgent",
            ),
            stripReleaseArchiveCommand(root.resolve("CodexAgent"), root.resolve("CodexAgent.stripped")),
        )
        val xcodebuild = swiftAuthenticationXcodebuildCommand("device", root.resolve("derived"), root.resolve("tests.xcresult"))
        assertEquals("xcodebuild", xcodebuild.first())
        assertTrue("platform=iOS Simulator,id=device" in xcodebuild)
        val failure = assertFailsWith<IllegalStateException> {
            requireSuccessfulReleaseProcess(listOf("xcodebuild", "test"), 65, "", "tests failed")
        }
        assertTrue(failure.message.orEmpty().contains("tests failed"))
    }

    @Test
    fun `release archive normalization removes checkout roots and preserves exported symbols`() = withRoot { root ->
        if (!File("/usr/bin/xcrun").canExecute()) return@withRoot
        fun run(command: List<String>) {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "${command.joinToString(" ")} failed: $output" }
        }
        fun normalized(name: String): File {
            val directory = root.resolve(name).apply { mkdirs() }
            val source = directory.resolve("source.c").apply {
                writeText("int codex_export(void) { return 7; }")
            }
            val objectFile = directory.resolve("source.o")
            val archive = directory.resolve("CodexAgent")
            val stripped = directory.resolve("CodexAgent.stripped")
            val normalized = directory.resolve("CodexAgent.normalized")
            run(listOf("/usr/bin/xcrun", "clang", "-g", "-c", source.absolutePath, "-o", objectFile.absolutePath))
            run(listOf("/usr/bin/xcrun", "libtool", "-static", "-D", objectFile.absolutePath, "-o", archive.absolutePath))
            run(stripReleaseArchiveCommand(archive, stripped))
            run(libtoolNormalizeCommand(stripped, normalized))
            return normalized
        }
        val first = normalized("one")
        val second = normalized("two")
        assertTrue(Files.mismatch(first.toPath(), second.toPath()) == -1L)
        val strings = ProcessBuilder("/usr/bin/strings", first.absolutePath).start().inputStream.bufferedReader().readText()
        assertFalse(root.absolutePath in strings)
        val symbols = ProcessBuilder("/usr/bin/xcrun", "nm", "-gU", first.absolutePath)
            .start().inputStream.bufferedReader().readText()
        assertTrue("_codex_export" in symbols)
    }

    @Test
    fun `distribution staging copies the exact package and sample layout`() = withRoot { root ->
        fun directory(name: String) = root.resolve(name).apply { mkdirs(); resolve("content").writeText(name) }
        fun file(name: String) = root.resolve(name).apply { parentFile.mkdirs(); writeText(name) }
        val output = root.resolve("distribution")
        stageAppleDistribution(
            AppleDistributionInputs(
                file("inputs/Package.swift"), directory("Sources"), directory("Tests"), directory("Framework"),
                file("LICENSE"), file("THIRD_PARTY_NOTICES.md"), file("codex-license"), file("codex-notice"),
                directory("TestApp"),
            ),
            output,
        )
        val packageRoot = output.resolve("CodexAgentPackage")
        assertEquals("inputs/Package.swift", packageRoot.resolve("Package.swift").readText())
        assertEquals("Sources", packageRoot.resolve("Sources/content").readText())
        assertEquals("Framework", packageRoot.resolve("CodexAgent.xcframework/content").readText())
        assertEquals("TestApp", output.resolve("CodexAgentTestApp/content").readText())
    }

    @Test
    fun `privacy placement and XCFramework library order are exact`() = withRoot { root ->
        val privacy = root.resolve("PrivacyInfo.xcprivacy").apply { writeText("privacy") }
        val framework = root.resolve("CodexAgent.xcframework")
        listOf("ios-arm64", "ios-arm64-simulator").forEach { slice ->
            framework.resolve("$slice/CodexAgent.framework/PrivacyInfo.xcprivacy").apply {
                parentFile.mkdirs(); writeText("privacy")
            }
        }
        verifyPrivacyPlacement(framework, privacy)
        assertEquals(
            "[{\"LibraryIdentifier\":\"a\"},{\"LibraryIdentifier\":\"b\"}]",
            sortedAvailableLibraries(
                "[{\"LibraryIdentifier\":\"b\"},{\"LibraryIdentifier\":\"a\"}]",
            ),
        )
        framework.resolve("ios-arm64/CodexAgent.framework/PrivacyInfo.xcprivacy").writeText("changed")
        assertFailsWith<IllegalStateException> { verifyPrivacyPlacement(framework, privacy) }
    }

    @Test
    fun `license verification compares bytes and emits deterministic SHA256 lines`() = withRoot { root ->
        val source = root.resolve("LICENSE").apply { writeText("license") }
        val packaged = root.resolve("package/LICENSE.txt").apply { parentFile.mkdirs(); writeText("license") }
        val build = root.resolve("build.gradle.kts").apply {
            writeText("GNU General Public License v3.0 or later")
        }
        val report = verifyPackagedLicenses(listOf(source to packaged), build)
        assertEquals("${packaged.releaseDigest()}  ${packaged.absolutePath}\n", report)
        packaged.appendText("changed")
        assertFailsWith<IllegalStateException> { verifyPackagedLicenses(listOf(source to packaged), build) }
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = createTempDirectory("apple-distribution").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
