import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome

class ImportedAndroidReleaseAarTest {
    @Test
    fun `imported AAR replaces the signed Android component without rebuilding it`() = withDirectory { root ->
        val fixture = publicationFixture(root)
        val publicationTask = "publishMavenPublicationToTestRepository"

        val imported = fixture.importArguments
        val signed = runner(root, publicationTask, "--dry-run", "-PenableSigning=true", *imported).build()
        assertTrue(":signMavenPublication" in signed.output, signed.output)
        assertNoAndroidRebuild(signed)

        val published = runner(root, publicationTask, *imported).build()
        assertEquals(TaskOutcome.SUCCESS, published.task(":verifyImportedAndroidReleaseAar")?.outcome)
        assertNoAndroidRebuild(published)

        val directory = root.resolve("build/repo/test/example/runtime/1.0")
        val aar = directory.resolve("runtime-1.0.aar")
        val module = directory.resolve("runtime-1.0.module")
        assertTrue(aar.readBytes().contentEquals(fixture.aar.readBytes()))
        assertTrue(directory.resolve("runtime-1.0.pom").isFile)
        assertTrue(directory.resolve("runtime-1.0-sources.jar").isFile)
        assertTrue(directory.resolve("runtime-1.0-javadoc.jar").isFile)
        val moduleAars = Json.parseToJsonElement(module.readText()).jsonObject
            .getValue("variants").jsonArray
            .flatMap { variant -> variant.jsonObject["files"]?.jsonArray?.toList().orEmpty() }
            .map { it.jsonObject }
            .filter { it["name"]?.jsonPrimitive?.content == aar.name }
        assertTrue(moduleAars.isNotEmpty())
        assertTrue(moduleAars.all { it.getValue("sha256").jsonPrimitive.content == fixture.aar.releaseDigest() })
    }

    @Test
    fun `typed validation binds the full AAR and pinned runtime to Firebase evidence`() = withDirectory { root ->
        val fixture = fixture(root)
        val receipt = verifyImportedAndroidReleaseAar(fixture.aar, fixture.evidence, COMMIT, fixture.runtimeSha256)
        assertEquals(fixture.aar.releaseDigest(), receipt.releaseString("releaseAarSha256"))
        fixture.aar.appendText("tampered")
        assertFailsWith<IllegalStateException> {
            verifyImportedAndroidReleaseAar(fixture.aar, fixture.evidence, COMMIT, fixture.runtimeSha256)
        }
    }

    @Test
    fun `Central binding rejects a different AAR containing the same runtime`() = withDirectory { root ->
        val fixture = fixture(root)
        val central = root.resolve("central.zip")
        val path = "io/github/ciurlaro/codex-agent-runtime-android/0.2.0/" +
            "codex-agent-runtime-android-0.2.0.aar"
        writeZip(central, mapOf(path to fixture.aar.readBytes()))
        verifyCandidateCentralAndroidRuntimeBinding(listOf(fixture.evidence), central, "0.2.0")

        val different = root.resolve("different.aar")
        writeZip(different, linkedMapOf(AAR_RUNTIME_ENTRY to RUNTIME, "extra.txt" to "extra".encodeToByteArray()))
        writeZip(central, mapOf(path to different.readBytes()))
        assertFailsWith<IllegalStateException> {
            verifyCandidateCentralAndroidRuntimeBinding(listOf(fixture.evidence), central, "0.2.0")
        }
    }

    private fun fixture(root: File): Fixture {
        val aar = root.resolve(FIREBASE_RELEASE_AAR)
        writeZip(aar, mapOf(AAR_RUNTIME_ENTRY to RUNTIME))
        val runtimeSha256 = RUNTIME.inputStream().use { it.releaseDigest() }
        val evidence = root.resolve(FIREBASE_ANDROID_EVIDENCE_FILE)
        evidence.atomicWriteJson(buildFirebaseAndroidEvidence(FirebaseAndroidEvidenceValues(
            COMMIT,
            FirebaseTestMatrix(
                "matrix-test", "test-project", "gs://bucket/results", FIREBASE_DEVICE_MODEL,
                FIREBASE_DEVICE_API, FIREBASE_DEVICE_LOCALE, FIREBASE_DEVICE_ORIENTATION,
            ),
            "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64),
            aar.releaseDigest(), runtimeSha256, runtimeSha256,
        )))
        return Fixture(aar, evidence, runtimeSha256)
    }

    private fun publicationFixture(root: File): PublicationFixture {
        writeAndroidSdkLocation(root)
        val runtime = "exact-main-runtime".encodeToByteArray()
        val aar = root.resolve(FIREBASE_RELEASE_AAR)
        writeZip(aar, mapOf(AAR_RUNTIME_ENTRY to runtime))
        val runtimeSha256 = runtime.inputStream().use { it.releaseDigest() }
        val evidenceDirectory = root.resolve("evidence").apply { mkdirs() }
        evidenceDirectory.resolve(FIREBASE_ANDROID_EVIDENCE_FILE).atomicWriteJson(
            buildFirebaseAndroidEvidence(FirebaseAndroidEvidenceValues(
                COMMIT,
                FirebaseTestMatrix(
                    "matrix-publication", "test-project", "gs://bucket/results", FIREBASE_DEVICE_MODEL,
                    FIREBASE_DEVICE_API, FIREBASE_DEVICE_LOCALE, FIREBASE_DEVICE_ORIENTATION,
                ),
                "1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64),
                aar.releaseDigest(), runtimeSha256, runtimeSha256,
            )),
        )
        root.resolve("settings.gradle.kts").writeText(
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = "android-publication-test"
            """.trimIndent(),
        )
        root.resolve("build.gradle.kts").writeText(
            """
            import com.android.build.api.dsl.LibraryExtension
            import org.gradle.api.publish.PublishingExtension
            import org.gradle.api.publish.maven.MavenPublication
            import org.gradle.api.tasks.bundling.Jar
            import org.gradle.plugins.signing.SigningExtension

            plugins {
                id("com.android.library")
                `maven-publish`
                signing
                id("codexagent.codex-runtime")
            }
            group = "test.example"
            version = "1.0"
            extensions.configure<LibraryExtension> {
                namespace = "test.example.runtime"
                compileSdk = 37
                defaultConfig { minSdk = 26 }
                publishing { singleVariant("release") { withSourcesJar() } }
            }
            val emptyJavadocJar = tasks.register<Jar>("emptyJavadocJar") {
                archiveClassifier.set("javadoc")
            }
            publishing {
                repositories { maven { name = "test"; url = uri(layout.buildDirectory.dir("repo")) } }
            }
            afterEvaluate {
                val publication = extensions.getByType<PublishingExtension>().publications
                    .create<MavenPublication>("maven") {
                        groupId = "test.example"
                        artifactId = "runtime"
                        version = "1.0"
                        from(components["release"])
                        artifact(emptyJavadocJar)
                        pom { name.set("Runtime"); description.set("Publication fixture") }
                    }
                if (providers.gradleProperty("enableSigning").isPresent) {
                    extensions.getByType<SigningExtension>().sign(publication)
                }
            }
            """.trimIndent(),
        )
        root.resolve("gradle.properties").writeText(
            """
            codexAgent.codexVersion=0.0.0
            codexAgent.codexArchiveSha256=${"0".repeat(64)}
            codexAgent.codexBinarySha256=$runtimeSha256
            """.trimIndent(),
        )
        return PublicationFixture(aar, evidenceDirectory)
    }

    private fun writeAndroidSdkLocation(root: File) {
        val home = System.getProperty("user.home")
        val sdk = listOfNotNull(
            System.getenv("ANDROID_HOME"),
            System.getenv("ANDROID_SDK_ROOT"),
            "$home/Library/Android/sdk",
            "$home/Android/Sdk",
        ).map(::File).firstOrNull(File::isDirectory)
            ?: error("Android SDK is required for the publication integration test")
        root.resolve("local.properties").writeText(
            "sdk.dir=${sdk.absolutePath.replace("\\", "\\\\")}\n",
        )
    }

    private fun runner(project: File, vararg arguments: String) = GradleRunner.create()
        .withProjectDir(project)
        .withPluginClasspath()
        .withArguments(*arguments, "--console=plain", "--stacktrace")

    private fun assertNoAndroidRebuild(result: org.gradle.testkit.runner.BuildResult) {
        listOf(":prepareCodexRuntime", ":compileReleaseJavaWithJavac", ":bundleReleaseAar").forEach { task ->
            assertFalse(task in result.output, "$task must not be in the imported publication graph")
        }
    }

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val root = createTempDirectory("imported-android-aar").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private data class Fixture(val aar: File, val evidence: File, val runtimeSha256: String)
    private data class PublicationFixture(val aar: File, val evidenceDirectory: File) {
        val importArguments: Array<String> get() = arrayOf(
            "-P$IMPORTED_ANDROID_RELEASE_AAR_PROPERTY=${aar.absolutePath}",
            "-PcodexAgent.candidateCommit=$COMMIT",
            "-P$FIREBASE_ANDROID_EVIDENCE_DIRECTORY_PROPERTY=${evidenceDirectory.absolutePath}",
        )
    }

    private companion object {
        const val COMMIT = "0123456789abcdef0123456789abcdef01234567"
        val RUNTIME = "runtime".encodeToByteArray()
    }
}
