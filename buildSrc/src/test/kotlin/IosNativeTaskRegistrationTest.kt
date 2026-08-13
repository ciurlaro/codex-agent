import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.testfixtures.ProjectBuilder

class IosNativeTaskRegistrationTest {
    @Test
    fun `every Cargo task tracks the complete pinned native input set`() {
        val directory = createTempDirectory("ios-native-registration").toFile()
        try {
            val expectedInputs = listOf(
                "native/patches/0001-uninitialized-in-process-host.patch",
                "native/patches/0002-locked-ios-bridge.patch",
                "native/patches/0003-pinned-ios-sqlite.patch",
                "native/sqlite/0001-ios-filesystem-probes.patch",
                "native/bridge/Cargo.toml",
                "native/bridge/src/lib.rs",
                "native/include/codex_agent_ios.h",
                "native/provenance.json",
            ).map { path -> directory.resolve(path).apply { parentFile.mkdirs(); writeText(path) } }
            val project = ProjectBuilder.builder().withProjectDir(directory).build()
            val nativeTasks = project.registerIosNativeTasks(configuration())
            val expectedRoots = expectedInputs.take(4).map(File::getCanonicalFile).toSet() +
                setOf(
                    directory.resolve("native/bridge").canonicalFile,
                    directory.resolve("native/include/codex_agent_ios.h").canonicalFile,
                    directory.resolve("native/provenance.json").canonicalFile,
                    directory.resolve("build/pinned-inputs/codex-${"1".repeat(40)}.tar.gz").canonicalFile,
                    directory.resolve("build/pinned-inputs/libsqlite3-sys-0.37.0.crate").canonicalFile,
                )

            listOf(
                nativeTasks.testCodexIosBridge,
                nativeTasks.testCodexIosDirectToolMode,
                nativeTasks.buildCodexIosArm64Rust,
                nativeTasks.buildCodexIosSimulatorArm64Rust,
            ).forEach { provider ->
                val task = provider.get()
                val actual = task.sourceInputs.files.map(File::getCanonicalFile).toSet()
                assertTrue(actual.containsAll(expectedRoots), "${task.name} is missing native inputs")
                assertEquals(
                    directory.resolve("build/codex-source/codex-rs").canonicalFile,
                    task.workingDirectory.get().asFile.canonicalFile,
                )
                assertEquals("15.0", task.provenanceValues.get().getValue("minimumIosVersion"))
                assertEquals("fat", task.provenanceValues.get().getValue("releaseLto"))
                assertEquals("1", task.provenanceValues.get().getValue("releaseCodegenUnits"))
                assertEquals("-Cdebuginfo=0", task.provenanceValues.get().getValue("releaseRustFlags"))
                assertEquals("required", task.provenanceValues.get().getValue("rustSrcComponent"))
                assertEquals(
                    "/codex-agent/prepared-source",
                    task.provenanceValues.get().getValue("releaseRustPreparedSourcePrefix"),
                )
            }

            val getter = PinnedCargoTask::class.java.getMethod("getSourceInputs")
            assertTrue(getter.isAnnotationPresent(InputFiles::class.java))
            assertEquals(PathSensitivity.RELATIVE, getter.getAnnotation(PathSensitive::class.java).value)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `iOS release requires the pinned rust-src component`() {
        val sysroot = createTempDirectory("ios-rust-src").toFile()
        try {
            assertFailsWith<IllegalStateException> { requiredRustSrcManifest(sysroot.path) }
            val manifest = sysroot.resolve("lib/rustlib/src/rust/library/Cargo.toml")
                .apply { parentFile.mkdirs(); writeText("[workspace]") }
            assertEquals(manifest.canonicalFile, requiredRustSrcManifest(sysroot.path).canonicalFile)
        } finally {
            sysroot.deleteRecursively()
        }
    }

    @Test
    fun `iOS builds use only the targeted SQLite compiler flags`() {
        val directory = createTempDirectory("ios-native-environment").toFile()
        try {
            val project = ProjectBuilder.builder().withProjectDir(directory).build()
            val nativeTasks = project.registerIosNativeTasks(configuration())
            listOf(nativeTasks.buildCodexIosArm64Rust, nativeTasks.buildCodexIosSimulatorArm64Rust).forEach {
                val task = it.get()
                val environment = task.extraEnvironment.get()
                assertFalse(environment.containsKey("CFLAGS"))
                assertFalse(environment.keys.any { name -> name.startsWith("CARGO_TARGET_") })
                assertEquals(
                    "SQLITE_ENABLE_LOCKING_STYLE=0 -DCODEX_AGENT_IOS_SQLITE_NO_FILESYSTEM_PROBES",
                    environment.getValue("LIBSQLITE3_FLAGS"),
                )
                assertEquals("0", environment.getValue("CARGO_PROFILE_RELEASE_DEBUG"))
                assertEquals("debuginfo", environment.getValue("CARGO_PROFILE_RELEASE_STRIP"))
                assertEquals("fat", environment.getValue("CARGO_PROFILE_RELEASE_LTO"))
                assertEquals("1", environment.getValue("CARGO_PROFILE_RELEASE_CODEGEN_UNITS"))
                assertEquals(listOf("-Cdebuginfo=0"), task.rustcArguments.get())
                assertEquals("CARGO_ENCODED_RUSTFLAGS", task.rustFlagsEnvironmentVariable.get())
                assertEquals("required", task.rustSrcComponent.get())
            }
            assertEquals(
                listOf(
                    "/home=/codex-agent/builder-home",
                    "/cargo=/codex-agent/cargo-home",
                    "/sysroot=/codex-agent/rust-sysroot",
                    "/project=/codex-agent/project",
                    "/source=/codex-agent/prepared-source",
                ),
                remapIosReleasePaths(
                    listOf("/home", "/cargo", "/sysroot", "/project", "/source"),
                    configuration().pinnedReleaseRustPathRemapPolicy,
                ),
            )
            listOf(nativeTasks.testCodexIosBridge, nativeTasks.testCodexIosDirectToolMode).forEach {
                assertTrue(it.get().rustcArguments.get().isEmpty())
                assertTrue(it.get().rustPathRemappings.get().isEmpty())
                assertEquals("not-required", it.get().rustSrcComponent.get())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun configuration() = IosNativeTaskConfiguration(
        codexRevision = "1".repeat(40),
        codexArchiveSha256 = "2".repeat(64),
        codexCargoLockSha256 = "3".repeat(64),
        resolvedCargoLockSha256 = "4".repeat(64),
        libsqlite3SysVersion = "0.37.0",
        libsqlite3SysArchiveSha256 = "5".repeat(64),
        expectedSqliteSourceSha256 = "6".repeat(64),
        expectedPatchedSqliteSourceSha256 = "7".repeat(64),
        pinnedRustToolchain = "1.95.0",
        pinnedRustSrcComponent = "required",
        rustLibrary = "libcodex_agent_ios_bridge.a",
        minimumIosVersion = "15.0",
        pinnedSqliteArchiveSha256 = "5".repeat(64),
        sqliteArchiveBytes = 5_295_554,
        pinnedReleaseLto = "fat",
        pinnedReleaseCodegenUnits = "1",
        pinnedReleaseRustFlags = "-Cdebuginfo=0",
        pinnedReleaseRustPathRemapPolicy = linkedMapOf(
            "releaseRustFlagsTransport" to "CARGO_ENCODED_RUSTFLAGS",
            "releaseRustPathRemapOrder" to "builderHome,cargoHome,rustSysroot,projectRoot,preparedCodexSource",
            "releaseRustBuilderHomePrefix" to "/codex-agent/builder-home",
            "releaseRustCargoHomePrefix" to "/codex-agent/cargo-home",
            "releaseRustSysrootPrefix" to "/codex-agent/rust-sysroot",
            "releaseRustProjectRootPrefix" to "/codex-agent/project",
            "releaseRustPreparedSourcePrefix" to "/codex-agent/prepared-source",
        ),
    )
}
