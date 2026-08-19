import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

class DesktopRuntimeEvidenceTasksTest {
    @Test
    fun `test report requires the exact class and four exact methods`() = withDirectory { root ->
        val report = root.resolve("TEST-desktop.xml")
        writeReport(report)
        verifyDesktopRuntimeTestReport(report, TARGET)

        writeReport(report, desktopRuntimeTestMethods - "rejectsWrongTargetChecksum" + "unexpected")
        assertFailsWith<IllegalStateException> { verifyDesktopRuntimeTestReport(report, TARGET) }
        writeReport(report, className = DESKTOP_RUNTIME_TEST_CLASS)
        assertFailsWith<IllegalStateException> { verifyDesktopRuntimeTestReport(report, TARGET) }
        writeReport(report, className = "wrongTest.$DESKTOP_RUNTIME_TEST_CLASS")
        assertFailsWith<IllegalStateException> { verifyDesktopRuntimeTestReport(report, TARGET) }
        writeReport(report)
        report.writeText(report.readText().replaceFirst("${TARGET}Test.", "wrongTest."))
        assertFailsWith<IllegalStateException> { verifyDesktopRuntimeTestReport(report, TARGET) }
    }

    @Test
    fun `five evidence files bind commit pinned binaries and Maven classifiers`() = withDirectory { root ->
        val commit = "a".repeat(40)
        val binary = "b".repeat(64)
        val archive = "c".repeat(64)
        val supervisor = "d".repeat(64)
        val manifest = writeTestDesktopDistributionManifest(root.resolve("desktop.json"), binary)
        val evidence = desktopRuntimeEvidenceTargets.keys.map { target ->
            root.resolve(desktopRuntimeEvidenceFileName(target)).apply {
                atomicWriteJson(buildDesktopRuntimeEvidence(
                    DesktopRuntimeEvidenceValues(commit, target, binary, supervisor, archive),
                ))
            }
        }
        val inventory = root.resolve("maven.json").apply { atomicWriteJson(buildJsonObject {
            put("files", buildJsonArray { desktopRuntimeEvidenceTargets.values.forEach { target ->
                add(buildJsonObject {
                    put("path", JsonPrimitive(
                        "io/github/ciurlaro/codex-agent-runtime-desktop/0.2.0/" +
                            "codex-agent-runtime-desktop-0.2.0-${target.classifier}.zip",
                    ))
                    put("sha256", JsonPrimitive(archive))
                })
            } })
        }) }

        assertTrue(validateDesktopRuntimeEvidence(evidence, commit, "0.2.0", inventory, manifest).isEmpty())
        evidence.first().writeText(evidence.first().readText().replace(binary, "d".repeat(64)))
        assertTrue(validateDesktopRuntimeEvidence(evidence, commit, "0.2.0", inventory, manifest).isNotEmpty())
    }

    private fun writeReport(
        file: File,
        methods: Set<String> = desktopRuntimeTestMethods,
        className: String = "${TARGET}Test.$DESKTOP_RUNTIME_TEST_CLASS",
    ) {
        file.writeText(buildString {
            append("<testsuite tests=\"").append(methods.size)
                .append("\" skipped=\"0\" failures=\"0\" errors=\"0\">")
            methods.forEach { method ->
                append("<testcase classname=\"").append(className).append("\" name=\"")
                    .append(method).append("[fixture]\"/>")
            }
            append("</testsuite>")
        })
    }

    private fun withDirectory(block: (File) -> Unit) {
        val root = createTempDirectory("desktop-evidence").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private companion object { const val TARGET = "macosArm64" }
}
