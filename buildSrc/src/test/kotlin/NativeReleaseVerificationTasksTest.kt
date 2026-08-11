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
                "bridge.h",
                "codex.tar.gz",
                "sqlite.crate",
            )
                .associateWith { name -> directory.resolve(name).apply { writeText(name) } }
            val bridgeSource = directory.resolve("bridge-src").apply { mkdirs() }
            val bridgeLib = bridgeSource.resolve("lib.rs").apply { writeText("lib") }
            directory.resolve("provenance.json").writeText(
                """
                {
                  "gitRevision": "${"1".repeat(40)}",
                  "sourceArchiveSha256": "${inputs.getValue("codex.tar.gz").sha256()}",
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
                  "minimumIosVersion": "15.0",
                  "releaseDebug": "0",
                  "releaseStrip": "debuginfo",
                  "sqliteCompileFlags": "SQLITE_ENABLE_LOCKING_STYLE=0 -DCODEX_AGENT_IOS_SQLITE_NO_FILESYSTEM_PROBES",
                  "adapterPatchSha256": "${inputs.getValue("adapter.patch").sha256()}",
                  "lockPatchSha256": "${inputs.getValue("lock.patch").sha256()}",
                  "sqliteWorkspacePatchSha256": "${inputs.getValue("sqlite-workspace.patch").sha256()}",
                  "sqliteSourcePatchSha256": "${inputs.getValue("sqlite-source.patch").sha256()}",
                  "bridgeManifestSha256": "${inputs.getValue("Cargo.toml").sha256()}",
                  "bridgeSourceSha256": "${bridgeSource.treeSha256()}",
                  "cHeaderSha256": "${inputs.getValue("bridge.h").sha256()}",
                  "sqliteSourceArchiveSha256": "${inputs.getValue("sqlite.crate").sha256()}"
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
                this.bridgeSource.set(project.layout.projectDirectory.dir("bridge-src"))
                cHeader.set(project.layout.projectDirectory.file("bridge.h"))
                codexArchive.set(project.layout.projectDirectory.file("codex.tar.gz"))
                sqliteArchive.set(project.layout.projectDirectory.file("sqlite.crate"))
                revision.set("1".repeat(40))
                archiveSha256.set(inputs.getValue("codex.tar.gz").sha256())
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
                minimumIosVersion.set("15.0")
                releaseDebug.set("0")
                releaseStrip.set("debuginfo")
                sqliteCompileFlags.set("SQLITE_ENABLE_LOCKING_STYLE=0 -DCODEX_AGENT_IOS_SQLITE_NO_FILESYSTEM_PROBES")
            }

            task.verify()
            bridgeLib.appendText("changed")
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
    fun `release metadata defaults an absent GitHub tag from the project version`() {
        verifyReleaseMetadata(releaseTag = null).verify()
    }

    @Test
    fun `release metadata accepts a matching explicit GitHub tag`() {
        verifyReleaseMetadata().verify()
    }

    @Test
    fun `release metadata rejects a mismatched explicit GitHub tag`() {
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
        releaseTag: String? = "v0.2.0",
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
            releaseTag?.let(this.releaseTag::set)
            swiftPackageManifest.set(project.layout.projectDirectory.file("Package.swift"))
            remoteConsumerManifest.set(project.layout.projectDirectory.file("RemoteConsumer.swift"))
        }
    }
}

private fun File.sha256(): String = MessageDigest.getInstance("SHA-256").digest(readBytes())
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun File.treeSha256(): String {
    val root = this
    val digest = MessageDigest.getInstance("SHA-256")
    walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach { file ->
        digest.update(file.relativeTo(root).invariantSeparatorsPath.toByteArray())
        digest.update(byteArrayOf(0))
        digest.update(file.length().toString().toByteArray())
        digest.update(byteArrayOf(0))
        digest.update(file.readBytes())
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
