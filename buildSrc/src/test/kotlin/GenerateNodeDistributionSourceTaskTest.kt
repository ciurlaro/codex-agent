import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class GenerateNodeDistributionSourceTaskTest {
    @Test
    fun `generates every target and leaves an absent supervisor unbound`() = withTask { task, output ->
        task.generate()
        val source = output.walkTopDown().single { it.name == "NodeCodexDistribution.generated.kt" }.readText()
        targets.forEach { assertTrue("\"$it\" to NodeCodexDistribution" in source) }
        assertTrue("windowsNodeSupervisorSha256: String? = null" in source)
    }

    @Test
    fun `rejects an incomplete authoritative target set`() =
        withTask(targets.dropLast(1)) { task, _ ->
            assertFailsWith<IllegalStateException> { task.generate() }
        }

    private fun withTask(
        distributions: List<String> = targets,
        block: (GenerateNodeDistributionSourceTask, java.io.File) -> Unit,
    ) {
        val root = createTempDirectory("node-distribution-source").toFile()
        try {
            val manifest = root.resolve("distributions.json").apply { writeText(manifest(distributions)) }
            val output = root.resolve("generated")
            val task = ProjectBuilder.builder().build().tasks.create(
                "generateNodeDistributionSourceTest",
                GenerateNodeDistributionSourceTask::class.java,
            ).apply {
                distributionManifest.set(manifest)
                outputDirectory.set(output)
            }
            block(task, output)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifest(distributions: List<String>) = """
        {"version":"0.145.0","releaseTag":"rust-v0.145.0","distributions":[
        ${distributions.map { target ->
            val index = targets.indexOf(target)
            val suffix = if (target == "mingwX64") ".exe" else ""
            """{"target":"$target","classifier":"${classifiers[index]}","asset":"asset-$index.zip","archiveSha256":"${"a".repeat(64)}","archiveEntry":"binary-$index","binarySha256":"${"b".repeat(64)}","executableName":"codex-app-server$suffix"}"""
        }.joinToString(",")}
        ]}
    """.trimIndent()

    private companion object {
        val targets = listOf("macosArm64", "macosX64", "linuxArm64", "linuxX64", "mingwX64")
        val classifiers = listOf(
            "app-server-macos-arm64", "app-server-macos-x64", "app-server-linux-arm64",
            "app-server-linux-x64", "app-server-windows-x64",
        )
    }
}
