import java.io.File
import java.security.MessageDigest
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.gradle.testfixtures.ProjectBuilder

class NativeReleaseVerificationTasksTest {
    @Test
    fun `provenance verifies every pinned native input`() {
        val directory = createTempDirectory("codex-ios-provenance").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(directory).build()
            val inputs = listOf(
                "adapter.patch",
                "lock.patch",
                "sqlite-workspace.patch",
                "sqlite-source.patch",
                "Cargo.toml",
                "lib.rs",
                "bridge.h",
                "sqlite.crate", "sqlite.patch", "workspace.patch",
            )
                .associateWith { name -> directory.resolve(name).apply { writeText(name) } }
            directory.resolve("provenance.json").writeText(
                """
                {
                  "gitRevision": "${"1".repeat(40)}",
                  "sourceArchiveSha256": "${"2".repeat(64)}",
                  "cargoLockSha256": "${"3".repeat(64)}",
                  "preparedCargoLockSha256": "${"4".repeat(64)}",
                  "rustToolchain": "1.95.0",
                  "libsqlite3SysVersion": "0.37.0",
                  "libsqlite3SysArchiveSha256": "${inputs.getValue("sqlite.crate").sha256()}",
                  "sqliteSourceSha256": "${"6".repeat(64)}",
                  "patchedSqliteSourceSha256": "${"7".repeat(64)}",
                  "releaseLto": "fat",
                  "releaseCodegenUnits": "1",
                  "releaseRustFlags": "-Cdebuginfo=0",
                  "adapterPatchSha256": "${inputs.getValue("adapter.patch").sha256()}",
                  "lockPatchSha256": "${inputs.getValue("lock.patch").sha256()}",
                  "sqliteWorkspacePatchSha256": "${inputs.getValue("sqlite-workspace.patch").sha256()}",
                  "sqliteSourcePatchSha256": "${inputs.getValue("sqlite-source.patch").sha256()}",
                  "bridgeManifestSha256": "${inputs.getValue("Cargo.toml").sha256()}",
                  "bridgeSourceSha256": "${inputs.getValue("lib.rs").sha256()}",
                  "cHeaderSha256": "${inputs.getValue("bridge.h").sha256()}"
                  ,"sqliteSourceArchiveSha256": "${inputs.getValue("sqlite.crate").sha256()}"
                  ,"sqlitePatchSha256": "${inputs.getValue("sqlite.patch").sha256()}"
                  ,"workspaceCargoPatchSha256": "${inputs.getValue("workspace.patch").sha256()}"
                }
                """.trimIndent(),
            )
            val task = project.tasks.register("verify", VerifyCodexIosProvenanceTask::class.java).get().apply {
                provenanceFile.set(project.layout.projectDirectory.file("provenance.json"))
                adapterPatch.set(project.layout.projectDirectory.file("adapter.patch"))
                lockPatch.set(project.layout.projectDirectory.file("lock.patch"))
                sqliteWorkspacePatch.set(project.layout.projectDirectory.file("sqlite-workspace.patch"))
                sqliteSourcePatch.set(project.layout.projectDirectory.file("sqlite-source.patch"))
                bridgeManifest.set(project.layout.projectDirectory.file("Cargo.toml"))
                bridgeSource.set(project.layout.projectDirectory.file("lib.rs"))
                cHeader.set(project.layout.projectDirectory.file("bridge.h"))
                sqliteArchive.set(project.layout.projectDirectory.file("sqlite.crate"))
                sqlitePatch.set(project.layout.projectDirectory.file("sqlite.patch"))
                workspaceCargoPatch.set(project.layout.projectDirectory.file("workspace.patch"))
                revision.set("1".repeat(40))
                archiveSha256.set("2".repeat(64))
                cargoLockSha256.set("3".repeat(64))
                preparedCargoLockSha256.set("4".repeat(64))
                rustToolchain.set("1.95.0")
                sqliteVersion.set("0.37.0")
                sqliteArchiveSha256.set(inputs.getValue("sqlite.crate").sha256())
                sqliteSourceSha256.set("6".repeat(64))
                patchedSqliteSourceSha256.set("7".repeat(64))
                releaseLto.set("fat")
                releaseCodegenUnits.set("1")
                releaseRustFlags.set("-Cdebuginfo=0")
            }

            task.verify()
            inputs.getValue("lib.rs").appendText("changed")
            assertFailsWith<IllegalStateException> { task.verify() }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `release checksum must match the public SwiftPM manifest`() {
        val directory = createTempDirectory("codex-ios-swiftpm").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(directory).build()
            val archive = directory.resolve("CodexAgent-0.2.0.xcframework.zip").apply { writeText("binary") }
            val checksum = directory.resolve("checksum.txt")
            project.tasks.register("checksum", GenerateSha256Task::class.java).get().apply {
                inputFile.set(archive)
                outputFile.set(checksum)
                generate()
            }
            val url = "https://github.com/ciurlaro/codex-agent/releases/download/v0.2.0/${archive.name}"
            val manifest = directory.resolve("Package.swift").apply {
                writeText(".binaryTarget(name: \"CodexAgent\", url: \"$url\", checksum: \"${checksum.readText().trim()}\")")
            }
            val verify = project.tasks.register("verify", VerifySwiftPackageBinaryTask::class.java).get().apply {
                this.manifest.set(manifest)
                checksumFile.set(checksum)
                expectedUrl.set(url)
            }

            verify.verify()
            manifest.writeText(manifest.readText().replace(checksum.readText().trim(), "0".repeat(64)))
            val failure = assertFailsWith<IllegalStateException> { verify.verify() }
            assertTrue(failure.message.orEmpty().contains("checksum mismatch"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `release metadata accepts one consistent version`() {
        verifyReleaseMetadata().verify()
    }

    @Test
    fun `release metadata rejects a mismatched GitHub tag`() {
        val failure = assertFailsWith<IllegalStateException> {
            verifyReleaseMetadata(releaseTag = "v0.2.1").verify()
        }
        assertTrue(failure.message.orEmpty().contains("release tag"))
    }

    @Test
    fun `release metadata rejects a mismatched SwiftPM URL version`() {
        val failure = assertFailsWith<IllegalStateException> {
            verifyReleaseMetadata(urlVersion = "0.2.1").verify()
        }
        assertTrue(failure.message.orEmpty().contains("URL version"))
    }

    @Test
    fun `release metadata rejects a mismatched SwiftPM filename version`() {
        val failure = assertFailsWith<IllegalStateException> {
            verifyReleaseMetadata(filenameVersion = "0.2.1").verify()
        }
        assertTrue(failure.message.orEmpty().contains("filename version"))
    }

    @Test
    fun `release metadata rejects a mismatched RemoteConsumer version`() {
        val failure = assertFailsWith<IllegalStateException> {
            verifyReleaseMetadata(consumerVersion = "0.2.1").verify()
        }
        assertTrue(failure.message.orEmpty().contains("dependency version"))
    }

    private fun verifyReleaseMetadata(
        releaseTag: String = "v0.2.0",
        urlVersion: String = "0.2.0",
        filenameVersion: String = "0.2.0",
        consumerVersion: String = "0.2.0",
    ): VerifyReleaseMetadataTask {
        val directory = createTempDirectory("codex-release-metadata").toFile()
        directory.deleteOnExit()
        directory.resolve("Package.swift").writeText(
            """
            .binaryTarget(
                name: "CodexAgent",
                url: "https://github.com/ciurlaro/codex-agent/releases/download/v$urlVersion/CodexAgent-$filenameVersion.xcframework.zip",
                checksum: "${"0".repeat(64)}"
            )
            """.trimIndent(),
        )
        directory.resolve("RemoteConsumer.swift").writeText(
            """
            .package(
                url: "https://github.com/ciurlaro/codex-agent.git",
                exact: "$consumerVersion"
            )
            """.trimIndent(),
        )
        val project = ProjectBuilder.builder().withProjectDir(directory).build()
        return project.tasks.register("verifyReleaseMetadata", VerifyReleaseMetadataTask::class.java).get().apply {
            projectVersion.set("0.2.0")
            this.releaseTag.set(releaseTag)
            swiftPackageManifest.set(project.layout.projectDirectory.file("Package.swift"))
            remoteConsumerManifest.set(project.layout.projectDirectory.file("RemoteConsumer.swift"))
        }
    }
}

private fun File.sha256(): String = MessageDigest.getInstance("SHA-256").digest(readBytes())
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
