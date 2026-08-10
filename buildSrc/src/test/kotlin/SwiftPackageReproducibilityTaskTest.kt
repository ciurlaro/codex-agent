import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class SwiftPackageReproducibilityTaskTest {
    @Test
    fun `two identical clean builds succeed`() = withFixture { fixture ->
        fixture.gradleWrites("stable")
        fixture.manifest.writeText("checksum: \"stable\"")

        fixture.task.verify()

        assertEquals("2", fixture.counter.readText())
    }

    @Test
    fun `uncommitted first checksum fails before the second build`() = withFixture { fixture ->
        fixture.gradleWrites("stable")
        fixture.manifest.writeText("checksum: \"other\"")

        val failure = assertFailsWith<IllegalStateException> { fixture.task.verify() }

        assertTrue(failure.message.orEmpty().contains("not committed"))
        assertEquals("1", fixture.counter.readText())
    }

    @Test
    fun `different clean build bytes fail`() = withFixture { fixture ->
        fixture.gradleWrites("build-${'$'}count")
        fixture.manifest.writeText("checksum: \"build-1\"")

        val failure = assertFailsWith<IllegalStateException> { fixture.task.verify() }

        assertTrue(failure.message.orEmpty().contains("byte-for-byte"))
        assertEquals("2", fixture.counter.readText())
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val directory = createTempDirectory("swiftpm-reproducibility").toFile()
        try {
            val archive = directory.resolve("build/CodexAgent-0.2.0.xcframework.zip")
            val counter = directory.resolve("build-count.txt")
            val manifest = directory.resolve("Package.swift")
            val gradlew = directory.resolve("gradlew")
            val swift = directory.resolve("swift")
            swift.writeText("#!/bin/sh\ncat \"${'$'}3\"\n")
            swift.setExecutable(true)
            val project = ProjectBuilder.builder().withProjectDir(directory).build()
            val task = project.tasks.register(
                "verifyReproducibility",
                VerifySwiftPackageReproducibilityTask::class.java,
            ).get().apply {
                this.manifest.set(manifest)
                gradleWrapper.set(gradlew)
                repositoryDirectory.set(directory)
                archiveFile.set(archive)
                swiftExecutable.set(swift.absolutePath)
            }
            test(Fixture(task, archive, counter, manifest, gradlew))
        } finally {
            directory.deleteRecursively()
        }
    }

    private data class Fixture(
        val task: VerifySwiftPackageReproducibilityTask,
        val archive: java.io.File,
        val counter: java.io.File,
        val manifest: java.io.File,
        val gradlew: java.io.File,
    ) {
        fun gradleWrites(value: String) {
            gradlew.writeText(
                """
                #!/bin/sh
                set -eu
                count=0
                if [ -f "${counter.absolutePath}" ]; then count=${'$'}(cat "${counter.absolutePath}"); fi
                count=${'$'}((count + 1))
                mkdir -p "${archive.parentFile.absolutePath}"
                printf '%s' "${'$'}count" > "${counter.absolutePath}"
                printf '%s' "$value" > "${archive.absolutePath}"
                """.trimIndent() + "\n",
            )
            gradlew.setExecutable(true)
        }
    }
}
