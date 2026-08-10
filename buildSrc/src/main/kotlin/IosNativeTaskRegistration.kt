import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

data class IosNativeTaskConfiguration(
    val codexRevision: String,
    val codexArchiveSha256: String,
    val codexCargoLockSha256: String,
    val resolvedCargoLockSha256: String,
    val libsqlite3SysVersion: String,
    val libsqlite3SysArchiveSha256: String,
    val expectedSqliteSourceSha256: String,
    val expectedPatchedSqliteSourceSha256: String,
    val pinnedRustToolchain: String,
    val rustLibrary: String,
    val minimumIosVersion: String,
    val pinnedSqliteArchiveSha256: String,
    val sqliteArchiveBytes: Long,
    val pinnedReleaseLto: String,
    val pinnedReleaseCodegenUnits: String,
    val pinnedReleaseRustFlags: String,
)

data class IosNativeTasks(
    val testCodexIosBridge: TaskProvider<PinnedCargoTask>,
    val testCodexIosDirectToolMode: TaskProvider<PinnedCargoTask>,
    val buildCodexIosArm64Rust: TaskProvider<PinnedCargoTask>,
    val buildCodexIosSimulatorArm64Rust: TaskProvider<PinnedCargoTask>,
)

fun Project.registerIosNativeTasks(configuration: IosNativeTaskConfiguration): IosNativeTasks {
    val provenanceRecordFile = layout.projectDirectory.file("native/provenance.json")
    val provenanceInputs = mapOf(
        "adapterPatchSha256" to layout.projectDirectory.file("native/patches/0001-uninitialized-in-process-host.patch"),
        "lockPatchSha256" to layout.projectDirectory.file("native/patches/0002-locked-ios-bridge.patch"),
        "sqliteWorkspacePatchSha256" to layout.projectDirectory.file("native/patches/0003-pinned-ios-sqlite.patch"),
        "sqliteSourcePatchSha256" to layout.projectDirectory.file("native/sqlite/0001-ios-filesystem-probes.patch"),
        "bridgeManifestSha256" to layout.projectDirectory.file("native/bridge/Cargo.toml"),
        "bridgeSourceSha256" to layout.projectDirectory.file("native/bridge/src/lib.rs"),
        "cHeaderSha256" to layout.projectDirectory.file("native/include/codex_agent_ios.h"),
    )
    val sqlitePatchFile = layout.projectDirectory.file("native/bridge/sqlite-ios-privacy.h")
    val workspaceCargoPatchFile = layout.projectDirectory.file("native/patches/0001-uninitialized-in-process-host.patch")
    val lockPatch = layout.projectDirectory.file("native/patches/0002-locked-ios-bridge.patch")
    val pinnedSqliteArchive = tasks.register<PreparePinnedArchiveTask>("preparePinnedSqliteArchive") {
        sourceUrl.set("https://static.crates.io/crates/libsqlite3-sys/libsqlite3-sys-0.37.0.crate")
        expectedSha256.set(configuration.pinnedSqliteArchiveSha256)
        providers.gradleProperty("codexAgent.sqliteArchiveFile").orNull?.let { path ->
            localArchive.set(rootProject.layout.projectDirectory.file(path))
        }
        outputFile.set(layout.buildDirectory.file("pinned-inputs/libsqlite3-sys-0.37.0.crate"))
    }

    val verifyCodexIosProvenance = tasks.register<VerifyCodexIosProvenanceTask>("verifyCodexIosProvenance") {
        dependsOn(pinnedSqliteArchive)
        group = "verification"
        description = "Verifies the pinned iOS native source and bridge provenance."
        provenanceFile.set(provenanceRecordFile)
        adapterPatch.set(provenanceInputs.getValue("adapterPatchSha256"))
        this.lockPatch.set(provenanceInputs.getValue("lockPatchSha256"))
        sqliteWorkspacePatch.set(provenanceInputs.getValue("sqliteWorkspacePatchSha256"))
        sqliteSourcePatch.set(provenanceInputs.getValue("sqliteSourcePatchSha256"))
        bridgeManifest.set(provenanceInputs.getValue("bridgeManifestSha256"))
        bridgeSource.set(provenanceInputs.getValue("bridgeSourceSha256"))
        cHeader.set(provenanceInputs.getValue("cHeaderSha256"))
        sqliteArchive.set(pinnedSqliteArchive.flatMap { it.outputFile })
        sqlitePatch.set(sqlitePatchFile)
        workspaceCargoPatch.set(workspaceCargoPatchFile)
        revision.set(configuration.codexRevision)
        archiveSha256.set(configuration.codexArchiveSha256)
        cargoLockSha256.set(configuration.codexCargoLockSha256)
        preparedCargoLockSha256.set(configuration.resolvedCargoLockSha256)
        rustToolchain.set(configuration.pinnedRustToolchain)
        sqliteVersion.set(configuration.libsqlite3SysVersion)
        sqliteArchiveSha256.set(configuration.libsqlite3SysArchiveSha256)
        sqliteSourceSha256.set(configuration.expectedSqliteSourceSha256)
        patchedSqliteSourceSha256.set(configuration.expectedPatchedSqliteSourceSha256)
        releaseLto.set(configuration.pinnedReleaseLto)
        releaseCodegenUnits.set(configuration.pinnedReleaseCodegenUnits)
        releaseRustFlags.set(configuration.pinnedReleaseRustFlags)
    }

    val prepareCodexIosSource = tasks.register<PrepareCodexIosSourceTask>("prepareCodexIosSource") {
        dependsOn(verifyCodexIosProvenance)
        revision.set(configuration.codexRevision)
        archiveSha256.set(configuration.codexArchiveSha256)
        cargoLockSha256.set(configuration.codexCargoLockSha256)
        preparedCargoLockSha256.set(configuration.resolvedCargoLockSha256)
        sqliteVersion.set(configuration.libsqlite3SysVersion)
        sqliteArchiveSha256.set(configuration.libsqlite3SysArchiveSha256)
        sqliteSourceSha256.set(configuration.expectedSqliteSourceSha256)
        patchedSqliteSourceSha256.set(configuration.expectedPatchedSqliteSourceSha256)
        providers.gradleProperty("codexAgent.codexIosArchiveFile").orNull?.let { path ->
            localArchive.set(rootProject.layout.projectDirectory.file(path))
        }
        providers.gradleProperty("codexAgent.libsqlite3SysArchiveFile").orNull?.let { path ->
            localSqliteArchive.set(rootProject.layout.projectDirectory.file(path))
        }
        sqlitePatch.set(layout.projectDirectory.file("native/sqlite/0001-ios-filesystem-probes.patch"))
        patches.from(layout.projectDirectory.dir("native/patches").asFileTree.matching { include("*.patch") })
        bridgeSource.set(layout.projectDirectory.dir("native/bridge"))
        outputDirectory.set(layout.buildDirectory.dir("codex-source"))
    }

    val codexRustRoot = layout.buildDirectory.dir("codex-source/codex-rs")

    tasks.matching { it.name in setOf("commonizeCInterop", "compileIosMainKotlinMetadata") }.configureEach {
        notCompatibleWithConfigurationCache("Kotlin/Native commonization accesses project state at execution time")
    }

    fun PinnedCargoTask.trackNativeInputs() {
        sourceInputs.from(
            pinnedSqliteArchive.flatMap { it.outputFile },
            sqlitePatchFile,
            workspaceCargoPatchFile,
            lockPatch,
            provenanceRecordFile,
        )
        provenanceValues.putAll(
            mapOf(
                "codexRevision" to configuration.codexRevision,
                "codexArchiveSha256" to configuration.codexArchiveSha256,
                "cargoLockSha256" to configuration.codexCargoLockSha256,
                "preparedCargoLockSha256" to configuration.resolvedCargoLockSha256,
                "rustToolchain" to configuration.pinnedRustToolchain,
                "sqliteArchiveSha256" to configuration.pinnedSqliteArchiveSha256,
                "sqliteArchiveBytes" to configuration.sqliteArchiveBytes.toString(),
                "releaseLto" to configuration.pinnedReleaseLto,
                "releaseCodegenUnits" to configuration.pinnedReleaseCodegenUnits,
                "releaseRustFlags" to configuration.pinnedReleaseRustFlags,
            ),
        )
    }

    val testCodexIosBridge = tasks.register<PinnedCargoTask>("testCodexIosBridge") {
        dependsOn(prepareCodexIosSource)
        trackNativeInputs()
        toolchain.set(configuration.pinnedRustToolchain)
        workingDirectory.set(codexRustRoot)
        cargoTargetDirectory.set(layout.buildDirectory.dir("rust/host"))
        cargoArguments.set(listOf("test", "--locked", "-p", "codex-agent-ios-bridge", "--lib"))
    }

    val testCodexIosDirectToolMode = tasks.register<PinnedCargoTask>("testCodexIosDirectToolMode") {
        dependsOn(prepareCodexIosSource)
        trackNativeInputs()
        toolchain.set(configuration.pinnedRustToolchain)
        workingDirectory.set(codexRustRoot)
        cargoTargetDirectory.set(layout.buildDirectory.dir("rust/host"))
        cargoArguments.set(
            listOf(
                "test",
                "--locked",
                "-p",
                "codex-core",
                "--lib",
                "ios_runtime_forces_direct_tools_for_code_mode_only_models",
            ),
        )
    }

    fun registerRustBuild(name: String, target: String) = tasks.register<PinnedCargoTask>(name) {
        dependsOn(prepareCodexIosSource)
        trackNativeInputs()
        inputs.property("minimumIosVersion", configuration.minimumIosVersion)
        inputs.files(layout.projectDirectory.dir("native/bridge"), layout.projectDirectory.dir("native/patches"))
        toolchain.set(configuration.pinnedRustToolchain)
        workingDirectory.set(codexRustRoot)
        cargoTargetDirectory.set(layout.buildDirectory.dir("rust"))
        cargoArguments.set(
            listOf("build", "--locked", "-p", "codex-agent-ios-bridge", "--release", "--target", target),
        )
        extraEnvironment.put("CARGO_PROFILE_RELEASE_DEBUG", "0")
        extraEnvironment.put("CARGO_PROFILE_RELEASE_STRIP", "debuginfo")
        extraEnvironment.put("CARGO_PROFILE_RELEASE_LTO", configuration.pinnedReleaseLto)
        extraEnvironment.put("CARGO_PROFILE_RELEASE_CODEGEN_UNITS", configuration.pinnedReleaseCodegenUnits)
        extraEnvironment.put("IPHONEOS_DEPLOYMENT_TARGET", configuration.minimumIosVersion)
        extraEnvironment.put(
            "CARGO_TARGET_${target.uppercase().replace('-', '_')}_RUSTFLAGS",
            configuration.pinnedReleaseRustFlags,
        )
        extraEnvironment.put(
            "LIBSQLITE3_FLAGS",
            "SQLITE_ENABLE_LOCKING_STYLE=0 -DCODEX_AGENT_IOS_SQLITE_NO_FILESYSTEM_PROBES",
        )
        extraEnvironment.put("CFLAGS", "-include ${sqlitePatchFile.asFile.absolutePath}")
        outputs.file(layout.buildDirectory.file("rust/$target/release/${configuration.rustLibrary}"))
    }

    return IosNativeTasks(
        testCodexIosBridge = testCodexIosBridge,
        testCodexIosDirectToolMode = testCodexIosDirectToolMode,
        buildCodexIosArm64Rust = registerRustBuild("buildCodexIosArm64Rust", "aarch64-apple-ios"),
        buildCodexIosSimulatorArm64Rust =
            registerRustBuild("buildCodexIosSimulatorArm64Rust", "aarch64-apple-ios-sim"),
    )
}
