import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class DesktopClassifierImportFunctionalTest {
    @Test
    fun `prebuilt classifier does not require a loose supervisor`() {
        val project = createTempDirectory("desktop-classifier-import").toFile()
        try {
            val fixture = NodeRuntimeEvidenceFixture(project)
            val classifier = fixture.classifiers.getValue("linuxArm64")
            patchDesktopRuntimeUnixModes(
                classifier,
                setOf("codex-app-server", "codex-process-supervisor"),
            )
            project.resolve("codex-agent-runtime-android/src/main/assets").mkdirs()
            project.resolve("codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt")
                .writeText("license")
            project.resolve("codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt")
                .writeText("notice")
            project.resolve("settings.gradle.kts").writeText("rootProject.name = \"test\"\n")
            project.resolve("build.gradle.kts").writeText(
                """
                plugins {
                    id("org.jetbrains.kotlin.multiplatform")
                    id("maven-publish")
                    id("codexagent.desktop-runtime")
                }
                group = "io.github.ciurlaro"
                version = "0.2.0"
                """.trimIndent(),
            )

            val result = GradleRunner.create()
                .withProjectDir(project)
                .withPluginClasspath()
                .withArguments(
                    "packageLinuxArm64AppServer",
                    "-PcodexAgent.desktopClassifierDirectory=${project.absolutePath}",
                    "--no-configuration-cache",
                    "--stacktrace",
                )
                .build()

            assertEquals(TaskOutcome.SUCCESS, result.task(":packageLinuxArm64AppServer")?.outcome)
            assertFalse(project.resolve("build/supervisor/linuxArm64/codex-process-supervisor").exists())
            assertContentEquals(
                classifier.readBytes(),
                project.resolve(
                    "build/distributions/codex-agent-runtime-desktop-0.2.0-app-server-linux-arm64.zip",
                ).readBytes(),
            )
        } finally {
            project.deleteRecursively()
        }
    }
}
