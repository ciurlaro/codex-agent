import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.register

fun Project.registerIosNativeTasks(configuration: IosNativeTaskConfiguration): IosNativeTasks {
    check(configuration.pinnedRustSrcComponent == "required") {
        "iOS release builds require the pinned Rust rust-src component"
    }
    val requiredRemapPolicy = setOf(
        "releaseRustFlagsTransport",
        "releaseRustPathRemapOrder",
        "releaseRustBuilderHomePrefix",
        "releaseRustCargoHomePrefix",
        "releaseRustSysrootPrefix",
        "releaseRustProjectRootPrefix",
        "releaseRustPreparedSourcePrefix",
    )
    check(configuration.pinnedReleaseRustPathRemapPolicy.keys == requiredRemapPolicy) {
        "iOS release Rust path-remap policy must contain exactly $requiredRemapPolicy"
    }
    check(
        configuration.pinnedReleaseRustPathRemapPolicy.getValue("releaseRustFlagsTransport") ==
            "CARGO_ENCODED_RUSTFLAGS",
    ) { "iOS release Rust flags must use CARGO_ENCODED_RUSTFLAGS" }
    check(
        configuration.pinnedReleaseRustPathRemapPolicy.getValue("releaseRustPathRemapOrder") ==
            "builderHome,cargoHome,rustSysroot,projectRoot,preparedCodexSource",
    ) { "iOS release Rust path-remap order is invalid" }
    val provenanceRecordFile = layout.projectDirectory.file("native/provenance.json")
    val rustSysroot = iosRustSysroot(configuration.pinnedRustToolchain)
    val rustSrcManifestFile = layout.file(rustSysroot.map(::requiredRustSrcManifest))
    val rustCompilerIdentityValue = appleRustCompilerIdentity(configuration.pinnedRustToolchain)
    val rustcWrapperEnvironment = providers.environmentVariable("RUSTC_WRAPPER").orElse("")
    val rustcWrapperFiles = rustcWrapperFiles(rustcWrapperEnvironment)
    val adapterPatchFile = layout.projectDirectory.file("native/patches/0001-uninitialized-in-process-host.patch")
    val lockPatchFile = layout.projectDirectory.file("native/patches/0002-locked-ios-bridge.patch")
    val sqliteWorkspacePatchFile = layout.projectDirectory.file("native/patches/0003-pinned-ios-sqlite.patch")
    val sqliteSourcePatchFile = layout.projectDirectory.file("native/sqlite/0001-ios-filesystem-probes.patch")
    val bridgeDirectory = layout.projectDirectory.dir("native/bridge")
    val bridgeSourceDirectory = layout.projectDirectory.dir("native/bridge/src")
    val cHeaderFile = layout.projectDirectory.file("native/include/codex_agent_ios.h")
    val releaseDebug = "0"
    val releaseStrip = "debuginfo"
    val sqliteCompileFlags = "SQLITE_ENABLE_LOCKING_STYLE=0 -DCODEX_AGENT_IOS_SQLITE_NO_FILESYSTEM_PROBES"
    val provenanceInputs = mapOf(
        "adapterPatchSha256" to adapterPatchFile,
        "lockPatchSha256" to lockPatchFile,
        "sqliteWorkspacePatchSha256" to sqliteWorkspacePatchFile,
        "sqliteSourcePatchSha256" to sqliteSourcePatchFile,
        "bridgeManifestSha256" to layout.projectDirectory.file("native/bridge/Cargo.toml"),
        "cHeaderSha256" to cHeaderFile,
    )
    val pinnedCodexArchive = tasks.register<PreparePinnedArchiveTask>("preparePinnedCodexIosArchive") {
        sourceUrl.set("https://github.com/openai/codex/archive/${configuration.codexRevision}.tar.gz")
        expectedSha256.set(configuration.codexArchiveSha256)
        providers.gradleProperty("codexAgent.codexIosArchiveFile").orNull?.let { path ->
            localArchive.set(rootProject.layout.projectDirectory.file(path))
        }
        outputFile.set(layout.buildDirectory.file("pinned-inputs/codex-${configuration.codexRevision}.tar.gz"))
    }
    val pinnedSqliteArchive = tasks.register<PreparePinnedArchiveTask>("preparePinnedSqliteArchive") {
        sourceUrl.set("https://static.crates.io/crates/libsqlite3-sys/libsqlite3-sys-0.37.0.crate")
        expectedSha256.set(configuration.pinnedSqliteArchiveSha256)
        providers.gradleProperty("codexAgent.sqliteArchiveFile").orNull?.let { path ->
            localArchive.set(rootProject.layout.projectDirectory.file(path))
        }
        outputFile.set(layout.buildDirectory.file("pinned-inputs/libsqlite3-sys-0.37.0.crate"))
    }

    val verifyCodexIosProvenance = tasks.register<VerifyCodexIosProvenanceTask>("verifyCodexIosProvenance") {
        dependsOn(pinnedCodexArchive, pinnedSqliteArchive)
        group = "verification"
        description = "Verifies the pinned iOS native source and bridge provenance."
        provenanceFile.set(provenanceRecordFile)
        adapterPatch.set(provenanceInputs.getValue("adapterPatchSha256"))
        this.lockPatch.set(provenanceInputs.getValue("lockPatchSha256"))
        sqliteWorkspacePatch.set(provenanceInputs.getValue("sqliteWorkspacePatchSha256"))
        sqliteSourcePatch.set(provenanceInputs.getValue("sqliteSourcePatchSha256"))
        bridgeManifest.set(provenanceInputs.getValue("bridgeManifestSha256"))
        bridgeSource.set(bridgeSourceDirectory)
        cHeader.set(provenanceInputs.getValue("cHeaderSha256"))
        codexArchive.set(pinnedCodexArchive.flatMap { it.outputFile })
        sqliteArchive.set(pinnedSqliteArchive.flatMap { it.outputFile })
        revision.set(configuration.codexRevision)
        archiveSha256.set(configuration.codexArchiveSha256)
        cargoLockSha256.set(configuration.codexCargoLockSha256)
        preparedCargoLockSha256.set(configuration.resolvedCargoLockSha256)
        rustToolchain.set(configuration.pinnedRustToolchain)
        rustSrcComponent.set(configuration.pinnedRustSrcComponent)
        rustSrcManifest.set(rustSrcManifestFile)
        sqliteVersion.set(configuration.libsqlite3SysVersion)
        sqliteArchiveSha256.set(configuration.libsqlite3SysArchiveSha256)
        sqliteSourceSha256.set(configuration.expectedSqliteSourceSha256)
        patchedSqliteSourceSha256.set(configuration.expectedPatchedSqliteSourceSha256)
        releaseLto.set(configuration.pinnedReleaseLto)
        releaseCodegenUnits.set(configuration.pinnedReleaseCodegenUnits)
        releaseRustFlags.set(configuration.pinnedReleaseRustFlags)
        releaseRustPathRemapPolicy.putAll(configuration.pinnedReleaseRustPathRemapPolicy)
        minimumIosVersion.set(configuration.minimumIosVersion)
        this.releaseDebug.set(releaseDebug)
        this.releaseStrip.set(releaseStrip)
        this.sqliteCompileFlags.set(sqliteCompileFlags)
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
        sourceArchive.set(pinnedCodexArchive.flatMap { it.outputFile })
        sqliteArchive.set(pinnedSqliteArchive.flatMap { it.outputFile })
        sqlitePatch.set(sqliteSourcePatchFile)
        patches.from(layout.projectDirectory.dir("native/patches").asFileTree.matching { include("*.patch") })
        bridgeSource.set(bridgeDirectory)
        outputDirectory.set(layout.buildDirectory.dir("codex-source"))
    }

    val codexRustRoot = layout.buildDirectory.dir("codex-source/codex-rs")
    val cargoEnvironment = iosCargoExecutionEnvironment()
    val externalCargoConfigurations = codexRustRoot.zip(cargoEnvironment) { directory, environment ->
        externalCargoConfigurationState(directory.asFile, environment.getValue("CARGO_HOME"))
    }
    val appleToolchainIdentities = appleRustSliceSpecs.associate { spec ->
        spec.target to appleSdkToolchainIdentity(spec.target)
    }
    val releaseAbsolutePathPrefixes = iosReleaseAbsolutePathPrefixes(rustSysroot)
    val releaseRustPathRemappings = releaseAbsolutePathPrefixes.map { prefixes ->
        remapIosReleasePaths(prefixes, configuration.pinnedReleaseRustPathRemapPolicy)
    }

    tasks.matching { it.name in setOf("commonizeCInterop", "compileIosMainKotlinMetadata") }.configureEach {
        notCompatibleWithConfigurationCache("Kotlin/Native commonization accesses project state at execution time")
    }

    fun PinnedCargoTask.trackNativeInputs() {
        sourceInputs.from(
            pinnedCodexArchive.flatMap { it.outputFile },
            pinnedSqliteArchive.flatMap { it.outputFile },
            adapterPatchFile,
            lockPatchFile,
            sqliteWorkspacePatchFile,
            sqliteSourcePatchFile,
            bridgeDirectory,
            cHeaderFile,
            provenanceRecordFile,
        )
        provenanceValues.putAll(
            mapOf(
                "codexRevision" to configuration.codexRevision,
                "codexArchiveSha256" to configuration.codexArchiveSha256,
                "cargoLockSha256" to configuration.codexCargoLockSha256,
                "preparedCargoLockSha256" to configuration.resolvedCargoLockSha256,
                "rustToolchain" to configuration.pinnedRustToolchain,
                "rustSrcComponent" to configuration.pinnedRustSrcComponent,
                "sqliteVersion" to configuration.libsqlite3SysVersion,
                "sqliteArchiveSha256" to configuration.pinnedSqliteArchiveSha256,
                "sqliteArchiveBytes" to configuration.sqliteArchiveBytes.toString(),
                "sqliteSourceSha256" to configuration.expectedSqliteSourceSha256,
                "patchedSqliteSourceSha256" to configuration.expectedPatchedSqliteSourceSha256,
                "minimumIosVersion" to configuration.minimumIosVersion,
                "releaseDebug" to releaseDebug,
                "releaseStrip" to releaseStrip,
                "releaseLto" to configuration.pinnedReleaseLto,
                "releaseCodegenUnits" to configuration.pinnedReleaseCodegenUnits,
                "releaseRustFlags" to configuration.pinnedReleaseRustFlags,
                "sqliteCompileFlags" to sqliteCompileFlags,
            ) + configuration.pinnedReleaseRustPathRemapPolicy,
        )
    }

    fun PinnedCargoTask.configureCargoEnvironment(incrementalDefault: String) {
        retainedEnvironment.putAll(cargoEnvironment)
        externalCargoConfigurationState.putAll(externalCargoConfigurations)
        rustcWrapperCommand.set(rustcWrapperEnvironment)
        rustcWrapperExecutable.from(rustcWrapperFiles)
        cargoIncremental.set(providers.environmentVariable("CARGO_INCREMENTAL").orElse(incrementalDefault))
    }

    val testCodexIosBridge = tasks.register<PinnedCargoTask>("testCodexIosBridge") {
        dependsOn(prepareCodexIosSource)
        trackNativeInputs()
        toolchain.set(configuration.pinnedRustToolchain)
        workingDirectory.set(codexRustRoot)
        cargoTargetDirectory.set(layout.buildDirectory.dir("rust/host"))
        cargoArguments.set(listOf("test", "--locked", "-p", "codex-agent-ios-bridge", "--lib"))
        configureCargoEnvironment("")
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
        configureCargoEnvironment("")
    }

    fun registerRustBuild(name: String, target: String) = tasks.register<CachedPinnedCargoTask>(name) {
        dependsOn(prepareCodexIosSource)
        trackNativeInputs()
        toolchain.set(configuration.pinnedRustToolchain)
        rustSrcComponent.set(configuration.pinnedRustSrcComponent)
        rustSrcManifest.set(rustSrcManifestFile)
        workingDirectory.set(codexRustRoot)
        cargoTargetDirectory.set(layout.buildDirectory.dir("rust"))
        cargoArguments.set(
            listOf("build", "--locked", "-p", "codex-agent-ios-bridge", "--release", "--target", target),
        )
        extraEnvironment.put("CARGO_PROFILE_RELEASE_DEBUG", releaseDebug)
        extraEnvironment.put("CARGO_PROFILE_RELEASE_STRIP", releaseStrip)
        extraEnvironment.put("CARGO_PROFILE_RELEASE_LTO", configuration.pinnedReleaseLto)
        extraEnvironment.put("CARGO_PROFILE_RELEASE_CODEGEN_UNITS", configuration.pinnedReleaseCodegenUnits)
        extraEnvironment.put("IPHONEOS_DEPLOYMENT_TARGET", configuration.minimumIosVersion)
        rustcArguments.set(listOf(configuration.pinnedReleaseRustFlags))
        rustPathRemappings.set(releaseRustPathRemappings)
        rustFlagsEnvironmentVariable.set(
            configuration.pinnedReleaseRustPathRemapPolicy.getValue("releaseRustFlagsTransport"),
        )
        extraEnvironment.put("LIBSQLITE3_FLAGS", sqliteCompileFlags)
        rustCompilerIdentity.set(rustCompilerIdentityValue)
        appleToolchainIdentity.set(appleToolchainIdentities.getValue(target))
        configureCargoEnvironment("0")
        archiveOutput.set(layout.buildDirectory.file("rust/$target/release/${configuration.rustLibrary}"))
    }

    val buildCodexIosArm64Rust = registerRustBuild("buildCodexIosArm64Rust", IOS_DEVICE_RUST_TARGET)
    val buildCodexIosSimulatorArm64Rust =
        registerRustBuild("buildCodexIosSimulatorArm64Rust", IOS_SIMULATOR_RUST_TARGET)
    val iosArm64RustArchive = layout.buildDirectory.file(
        "rust/$IOS_DEVICE_RUST_TARGET/release/${configuration.rustLibrary}",
    )
    val iosSimulatorArm64RustArchive = layout.buildDirectory.file(
        "rust/$IOS_SIMULATOR_RUST_TARGET/release/${configuration.rustLibrary}",
    )
    val importedEvidence = providers.gradleProperty("codexAgent.iosNativeEvidenceDirectory")
        .map { rootProject.file(it) }.let(layout::dir).takeIf { it.isPresent }
    val nativeEvidenceInputs = files(
        adapterPatchFile, lockPatchFile, sqliteWorkspacePatchFile, sqliteSourcePatchFile,
        bridgeDirectory, cHeaderFile, provenanceRecordFile,
    )
    val selected = registerAppleRustSliceReuse(AppleRustSliceRegistrationInputs(
        providers.gradleProperty("codexAgent.candidateCommit"), importedEvidence, nativeEvidenceInputs,
        providers.provider { provenanceRecordFile },
        mapOf(
            "rustToolchain" to configuration.pinnedRustToolchain,
            "rustSrcComponent" to configuration.pinnedRustSrcComponent,
            "minimumIosVersion" to configuration.minimumIosVersion,
            "releaseLto" to configuration.pinnedReleaseLto,
            "releaseCodegenUnits" to configuration.pinnedReleaseCodegenUnits,
            "releaseRustFlags" to configuration.pinnedReleaseRustFlags,
            "releaseDebug" to releaseDebug,
            "releaseStrip" to releaseStrip,
            "sqliteCompileFlags" to sqliteCompileFlags,
        ) + configuration.pinnedReleaseRustPathRemapPolicy,
        rustCompilerIdentityValue, appleToolchainIdentities,
        buildCodexIosArm64Rust, buildCodexIosSimulatorArm64Rust, testCodexIosBridge, testCodexIosDirectToolMode,
        iosArm64RustArchive, iosSimulatorArm64RustArchive,
    ))
    return IosNativeTasks(
        testCodexIosBridge = testCodexIosBridge,
        testCodexIosDirectToolMode = testCodexIosDirectToolMode,
        buildCodexIosArm64Rust = buildCodexIosArm64Rust,
        buildCodexIosSimulatorArm64Rust = buildCodexIosSimulatorArm64Rust,
        iosArm64RustArchive = selected.deviceArchive,
        iosSimulatorArm64RustArchive = selected.simulatorArchive,
        prepareCodexAgentIosArm64RustSlice = selected.prepareDevice,
        prepareCodexAgentIosSimulatorArm64RustSlice = selected.prepareSimulator,
    )
}
