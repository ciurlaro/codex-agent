import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder

class ProtectedAndroidRuntimeRegistrationTest {
    @Test
    fun `registration verifies and stages the exact Firebase evidence set`() = withProject { project ->
        val imported = project.projectDir.resolve("imported-evidence").apply { mkdirs() }
        val registration = project.registerProtectedFirebaseAndroidRuntimeEvidence(
            project.layout.buildDirectory.dir("candidate/evidence"),
        )
        val verifier = project.project(":tooling:android-runtime-evidence").tasks
            .named("verifyFirebaseAndroidRuntimeEvidence").get()
        val copies = project.tasks.withType(CopyCandidateFileTask::class.java).toList()
        val expected = (protectedFirebaseAndroidRuntimeRawFiles.map { it.second } +
            FIREBASE_ANDROID_VERIFICATION_RECEIPT_FILE).toSet()

        assertEquals(7, copies.size)
        assertEquals(expected, copies.map { it.sourceFile.get().asFile.name }.toSet())
        assertEquals(expected, copies.map { it.outputFile.get().asFile.name }.toSet())
        assertEquals(expected, registration.stagedFiles.files.map(File::getName).toSet())
        assertTrue(copies.all { verifier in it.taskDependencies.getDependencies(it) })
        assertEquals(
            copies.toSet(),
            registration.stageTask.get().taskDependencies.getDependencies(registration.stageTask.get()),
        )
        assertTrue(copies.filter { it.sourceFile.get().asFile.name != FIREBASE_ANDROID_VERIFICATION_RECEIPT_FILE }
            .all { it.sourceFile.get().asFile.parentFile.canonicalFile == imported.canonicalFile })
    }

    @Test
    fun `imported Firebase evidence directory has no fallback`() = withProject(false) { project ->
        project.registerProtectedFirebaseAndroidRuntimeEvidence(
            project.layout.buildDirectory.dir("candidate/evidence"),
        )
        assertFails {
            project.tasks.named("stageProtectedFirebaseAndroidRuntimeRecord", CopyCandidateFileTask::class.java)
                .get().sourceFile.get()
        }
    }

    private fun withProject(withEvidenceProperty: Boolean = true, block: (Project) -> Unit) {
        val directory = createTempDirectory("protected-firebase-android").toFile()
        val property = "org.gradle.project.$FIREBASE_ANDROID_EVIDENCE_DIRECTORY_PROPERTY"
        if (withEvidenceProperty) {
            System.setProperty(property, directory.resolve("imported-evidence").absolutePath)
        }
        try {
            val project = ProjectBuilder.builder().withProjectDir(directory).build()
            val toolingDirectory = directory.resolve("tooling").apply { mkdirs() }
            val tooling = ProjectBuilder.builder().withName("tooling").withParent(project)
                .withProjectDir(toolingDirectory).build()
            val androidDirectory = toolingDirectory.resolve("android-runtime-evidence").apply { mkdirs() }
            ProjectBuilder.builder().withName("android-runtime-evidence").withParent(tooling)
                .withProjectDir(androidDirectory).build()
                .tasks.register("verifyFirebaseAndroidRuntimeEvidence")
            block(project)
        } finally {
            System.clearProperty(property)
            directory.deleteRecursively()
        }
    }
}
