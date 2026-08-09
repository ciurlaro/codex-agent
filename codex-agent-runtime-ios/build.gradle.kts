import java.io.File
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

private val codexRevision = "25af12f7e61572b0bc18ddb1008be543b91519b0"
private val codexArchiveSha256 = "42f627a7b32db41582c73a8eafd9ec4b35d6c3ff81bd3d4455cfd6224d79d329"
private val codexCargoLockSha256 = "e0843448b5767ff36a2a3b15212feb480cd4eaafe8a0c0ca08547e3c7da03a05"
private val resolvedCargoLockSha256 = "2af535168f77ce538bf9fc797914eb20cabe4f4d05a8d10266be6e52fd0bb1f3"
private val libsqlite3SysVersion = "0.37.0"
private val libsqlite3SysArchiveSha256 = "b1f111c8c41e7c61a49cd34e44c7619462967221a6443b0ec299e0ac30cfb9b1"
private val expectedSqliteSourceSha256 = "9512509b1bccb7461f79bea8aad6280ae4699e925fa4804381b71f59e7efb0c5"
private val expectedPatchedSqliteSourceSha256 = "a0b50ae286c86c1890c2144641682820a42aa38021ad5fa9457d99c636f0d057"
private val pinnedRustToolchain = "1.95.0"
private val rustLibrary = "libcodex_agent_ios_bridge.a"
private val minimumIosVersion = "15.0"
private val expectedSwiftTestCount = 23
private val pinnedSqliteArchiveSha256 = "b1f111c8c41e7c61a49cd34e44c7619462967221a6443b0ec299e0ac30cfb9b1"
private val sqliteArchiveBytes = 5_295_554L
private val pinnedReleaseLto = "fat"
private val pinnedReleaseCodegenUnits = "1"
private val pinnedReleaseRustFlags = "-Cdebuginfo=0"
private val expectedXcodeVersion = "26.6"
private val expectedXcodeBuild = "17F113"
private val expectedSwiftVersion = "6.3.3"

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
    expectedSha256.set(pinnedSqliteArchiveSha256)
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
    lockPatch.set(provenanceInputs.getValue("lockPatchSha256"))
    sqliteWorkspacePatch.set(provenanceInputs.getValue("sqliteWorkspacePatchSha256"))
    sqliteSourcePatch.set(provenanceInputs.getValue("sqliteSourcePatchSha256"))
    bridgeManifest.set(provenanceInputs.getValue("bridgeManifestSha256"))
    bridgeSource.set(provenanceInputs.getValue("bridgeSourceSha256"))
    cHeader.set(provenanceInputs.getValue("cHeaderSha256"))
    sqliteArchive.set(pinnedSqliteArchive.flatMap { it.outputFile })
    sqlitePatch.set(sqlitePatchFile)
    workspaceCargoPatch.set(workspaceCargoPatchFile)
    revision.set(codexRevision)
    archiveSha256.set(codexArchiveSha256)
    cargoLockSha256.set(codexCargoLockSha256)
    preparedCargoLockSha256.set(resolvedCargoLockSha256)
    rustToolchain.set(pinnedRustToolchain)
    sqliteVersion.set(libsqlite3SysVersion)
    sqliteArchiveSha256.set(libsqlite3SysArchiveSha256)
    sqliteSourceSha256.set(expectedSqliteSourceSha256)
    patchedSqliteSourceSha256.set(expectedPatchedSqliteSourceSha256)
    releaseLto.set(pinnedReleaseLto)
    releaseCodegenUnits.set(pinnedReleaseCodegenUnits)
    releaseRustFlags.set(pinnedReleaseRustFlags)
}

val prepareCodexIosSource = tasks.register<PrepareCodexIosSourceTask>("prepareCodexIosSource") {
    dependsOn(verifyCodexIosProvenance)
    revision.set(codexRevision)
    archiveSha256.set(codexArchiveSha256)
    cargoLockSha256.set(codexCargoLockSha256)
    preparedCargoLockSha256.set(resolvedCargoLockSha256)
    sqliteVersion.set(libsqlite3SysVersion)
    sqliteArchiveSha256.set(libsqlite3SysArchiveSha256)
    sqliteSourceSha256.set(expectedSqliteSourceSha256)
    patchedSqliteSourceSha256.set(expectedPatchedSqliteSourceSha256)
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
            "codexRevision" to codexRevision,
            "codexArchiveSha256" to codexArchiveSha256,
            "cargoLockSha256" to codexCargoLockSha256,
            "preparedCargoLockSha256" to resolvedCargoLockSha256,
            "rustToolchain" to pinnedRustToolchain,
            "sqliteArchiveSha256" to pinnedSqliteArchiveSha256,
            "sqliteArchiveBytes" to sqliteArchiveBytes.toString(),
            "releaseLto" to pinnedReleaseLto,
            "releaseCodegenUnits" to pinnedReleaseCodegenUnits,
            "releaseRustFlags" to pinnedReleaseRustFlags,
        ),
    )
}

val testCodexIosBridge = tasks.register<PinnedCargoTask>("testCodexIosBridge") {
    dependsOn(prepareCodexIosSource)
    trackNativeInputs()
    toolchain.set(pinnedRustToolchain)
    workingDirectory.set(codexRustRoot)
    cargoTargetDirectory.set(layout.buildDirectory.dir("rust/host"))
    cargoArguments.set(listOf("test", "--locked", "-p", "codex-agent-ios-bridge", "--lib"))
}

val testCodexIosDirectToolMode = tasks.register<PinnedCargoTask>("testCodexIosDirectToolMode") {
    dependsOn(prepareCodexIosSource)
    trackNativeInputs()
    toolchain.set(pinnedRustToolchain)
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
    inputs.property("minimumIosVersion", minimumIosVersion)
    inputs.files(layout.projectDirectory.dir("native/bridge"), layout.projectDirectory.dir("native/patches"))
    toolchain.set(pinnedRustToolchain)
    workingDirectory.set(codexRustRoot)
    cargoTargetDirectory.set(layout.buildDirectory.dir("rust"))
    cargoArguments.set(
        listOf("build", "--locked", "-p", "codex-agent-ios-bridge", "--release", "--target", target),
    )
    extraEnvironment.put("CARGO_PROFILE_RELEASE_DEBUG", "0")
    extraEnvironment.put("CARGO_PROFILE_RELEASE_STRIP", "debuginfo")
    extraEnvironment.put("CARGO_PROFILE_RELEASE_LTO", pinnedReleaseLto)
    extraEnvironment.put("CARGO_PROFILE_RELEASE_CODEGEN_UNITS", pinnedReleaseCodegenUnits)
    extraEnvironment.put("IPHONEOS_DEPLOYMENT_TARGET", minimumIosVersion)
    extraEnvironment.put(
        "CARGO_TARGET_${target.uppercase().replace('-', '_')}_RUSTFLAGS",
        pinnedReleaseRustFlags,
    )
    extraEnvironment.put(
        "LIBSQLITE3_FLAGS",
        "SQLITE_ENABLE_LOCKING_STYLE=0 -DCODEX_AGENT_IOS_SQLITE_NO_FILESYSTEM_PROBES",
    )
    extraEnvironment.put(
        "CFLAGS",
        "-include ${sqlitePatchFile.asFile.absolutePath}",
    )
    outputs.file(layout.buildDirectory.file("rust/$target/release/$rustLibrary"))
}

val buildCodexIosArm64Rust = registerRustBuild("buildCodexIosArm64Rust", "aarch64-apple-ios")
val buildCodexIosSimulatorArm64Rust =
    registerRustBuild("buildCodexIosSimulatorArm64Rust", "aarch64-apple-ios-sim")

val xcframework = XCFramework("CodexAgent")

kotlin {
    val device = iosArm64()
    val simulator = iosSimulatorArm64()

    listOf(device, simulator).forEach { target ->
        val rustTarget = if (target == device) "aarch64-apple-ios" else "aarch64-apple-ios-sim"
        val rustTask = if (target == device) buildCodexIosArm64Rust else buildCodexIosSimulatorArm64Rust
        val rustArchive = layout.buildDirectory.file("rust/$rustTarget/release/$rustLibrary")
        target.compilations.getByName("main").cinterops.create("codexAgentIos") {
            defFile(layout.projectDirectory.file("src/nativeInterop/cinterop/codex_agent_ios.def"))
            includeDirs(layout.projectDirectory.dir("native/include"))
            extraOpts(
                "-libraryPath",
                layout.buildDirectory.dir("rust/$rustTarget/release").get().asFile.absolutePath,
                "-staticLibrary",
                rustLibrary,
            )
            tasks.named(interopProcessingTaskName).configure {
                dependsOn(rustTask)
                inputs.file(rustArchive)
            }
        }
        target.binaries.all {
            freeCompilerArgs +=
                "-Xoverride-konan-properties=osVersionMin.${target.konanTarget.name}=$minimumIosVersion"
        }
        target.binaries.framework {
            baseName = "CodexAgent"
            isStatic = true
            export(project(":codex-agent-client"))
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":codex-agent-client"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val iosRuntimeMetricsPath =
    layout.buildDirectory.file("reports/ios-release/runtime-metrics.json").get().asFile.absolutePath
File(iosRuntimeMetricsPath).parentFile.mkdirs()
tasks.named<KotlinNativeTest>("iosSimulatorArm64Test") {
    environment("CODEX_AGENT_IOS_METRICS_PATH", iosRuntimeMetricsPath)
    environment("SIMCTL_CHILD_CODEX_AGENT_IOS_METRICS_PATH", iosRuntimeMetricsPath)
    outputs.file(iosRuntimeMetricsPath)
    doLast("verifyIosRuntimeMetrics") {
        val metrics = outputs.files.files.single { it.name == "runtime-metrics.json" }
        check(metrics.isFile) { "iOS runtime metrics were not recorded" }
    }
}

val verifyAppleToolchain = tasks.register<Exec>("verifyAppleToolchain") {
    val reportDirectory = layout.buildDirectory.dir("reports/ios-release/toolchain")
    outputs.dir(reportDirectory)
    commandLine(
        "/bin/bash",
        "-c",
        """
            set -euo pipefail
            mkdir -p "${reportDirectory.get().asFile.absolutePath}"
            xcodebuild -version | tee "${reportDirectory.get().file("xcode.txt").asFile.absolutePath}"
            swift --version | tee "${reportDirectory.get().file("swift.txt").asFile.absolutePath}"
            grep -Fx 'Xcode $expectedXcodeVersion' "${reportDirectory.get().file("xcode.txt").asFile.absolutePath}"
            grep -Fx 'Build version $expectedXcodeBuild' "${reportDirectory.get().file("xcode.txt").asFile.absolutePath}"
            grep -F 'Apple Swift version $expectedSwiftVersion' "${reportDirectory.get().file("swift.txt").asFile.absolutePath}"
        """.trimIndent(),
    )
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
        ),
    )
    coordinates("io.github.ciurlaro", "codex-agent-runtime-ios", project.version.toString())
    publishToMavenCentral(automaticRelease = true)
    if (
        providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.secretKeyRingFile").isPresent
    ) {
        signAllPublications()
    }
    pom {
        name.set("Codex Agent Runtime for iOS")
        description.set("Embedded in-process iOS runtime for Codex Agent.")
        inceptionYear.set("2026")
        url.set("https://github.com/ciurlaro/codex-agent")
        licenses {
            license {
                name.set("GNU General Public License v3.0 or later")
                url.set("https://www.gnu.org/licenses/gpl-3.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("ciurlaro")
                name.set("Cesare Iurlaro")
                url.set("https://github.com/ciurlaro")
            }
        }
        scm {
            url.set("https://github.com/ciurlaro/codex-agent")
            connection.set("scm:git:https://github.com/ciurlaro/codex-agent.git")
            developerConnection.set("scm:git:ssh://git@github.com/ciurlaro/codex-agent.git")
        }
    }
}

publishing {
    providers.gradleProperty("codexAgent.localRepository").orNull?.let { path ->
        repositories.maven {
            name = "migration"
            url = uri(path)
        }
    }
}

dependencyLocking {
    lockAllConfigurations()
}

val appleDistributionDirectory = layout.buildDirectory.dir("apple-distribution")
val assembledXCFrameworkDirectory =
    layout.buildDirectory.dir("XCFrameworks/release/CodexAgent.xcframework")
val releaseXCFrameworkDirectory =
    layout.buildDirectory.dir("release-xcframework/CodexAgent.xcframework")
val privacyManifestFile =
    layout.projectDirectory.file("apple/Sources/CodexAgentAuthentication/PrivacyInfo.xcprivacy")

val prepareCodexAgentReleaseXCFramework =
    tasks.register<Exec>("prepareCodexAgentReleaseXCFramework") {
        dependsOn("assembleCodexAgentReleaseXCFramework")
        inputs.dir(assembledXCFrameworkDirectory)
        inputs.file(privacyManifestFile)
        outputs.dir(releaseXCFrameworkDirectory)
        doFirst {
            project.delete(releaseXCFrameworkDirectory)
        }
        commandLine(
            "/bin/bash",
            "-c",
            """
                set -euo pipefail
                mkdir -p "${releaseXCFrameworkDirectory.get().asFile.parentFile.absolutePath}"
                /bin/cp -cR \
                    "${assembledXCFrameworkDirectory.get().asFile.absolutePath}" \
                    "${releaseXCFrameworkDirectory.get().asFile.absolutePath}"
                for slice in ios-arm64 ios-arm64-simulator; do
                    /bin/cp \
                        "${privacyManifestFile.asFile.absolutePath}" \
                        "${releaseXCFrameworkDirectory.get().asFile.absolutePath}/${'$'}slice/CodexAgent.framework/PrivacyInfo.xcprivacy"
                    archive="${releaseXCFrameworkDirectory.get().asFile.absolutePath}/${'$'}slice/CodexAgent.framework/CodexAgent"
                    normalized="${'$'}archive.normalized"
                    rm -f "${'$'}normalized"
                    /usr/bin/xcrun libtool -static -D -no_warning_for_no_symbols \
                        "${'$'}archive" -o "${'$'}normalized"
                    /bin/mv "${'$'}normalized" "${'$'}archive"
                done
                info_plist="${releaseXCFrameworkDirectory.get().asFile.absolutePath}/Info.plist"
                sorted=${'$'}(/usr/bin/plutil -extract AvailableLibraries json -o - "${'$'}info_plist" | /usr/bin/env jq -c 'sort_by(.LibraryIdentifier)')
                /usr/bin/plutil -replace AvailableLibraries -json "${'$'}sorted" "${'$'}info_plist"
            """.trimIndent(),
        )
    }

val stageCodexAgentAppleDistribution = tasks.register<Exec>("stageCodexAgentAppleDistribution") {
    dependsOn(prepareCodexAgentReleaseXCFramework)
    inputs.files(
        layout.projectDirectory.file("apple/Package.swift"),
        layout.projectDirectory.dir("apple/Sources"),
        layout.projectDirectory.dir("apple/Tests"),
        releaseXCFrameworkDirectory,
        rootProject.layout.projectDirectory.file("LICENSE"),
        rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"),
        rootProject.layout.projectDirectory.file(
            "codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt",
        ),
        rootProject.layout.projectDirectory.file(
            "codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt",
        ),
        layout.projectDirectory.dir("apple/TestApp"),
    )
    outputs.dir(appleDistributionDirectory)
    doFirst {
        project.delete(appleDistributionDirectory)
    }
    commandLine(
        "/bin/bash",
        "-c",
        """
            set -euo pipefail
            distribution="${appleDistributionDirectory.get().asFile.absolutePath}"
            package="${appleDistributionDirectory.get().dir("CodexAgentPackage").asFile.absolutePath}"
            mkdir -p "${'$'}package"
            /bin/cp "${layout.projectDirectory.file("apple/Package.swift").asFile.absolutePath}" "${'$'}package/"
            /bin/cp -R "${layout.projectDirectory.dir("apple/Sources").asFile.absolutePath}" "${'$'}package/Sources"
            /bin/cp -R "${layout.projectDirectory.dir("apple/Tests").asFile.absolutePath}" "${'$'}package/Tests"
            /bin/cp -cR "${releaseXCFrameworkDirectory.get().asFile.absolutePath}" "${'$'}package/CodexAgent.xcframework"
            /bin/cp "${rootProject.layout.projectDirectory.file("LICENSE").asFile.absolutePath}" "${'$'}package/LICENSE.txt"
            /bin/cp "${rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md").asFile.absolutePath}" "${'$'}package/"
            /bin/cp "${rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt").asFile.absolutePath}" "${'$'}package/"
            /bin/cp "${rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt").asFile.absolutePath}" "${'$'}package/"
            /bin/cp -R "${layout.projectDirectory.dir("apple/TestApp").asFile.absolutePath}" "${'$'}distribution/CodexAgentTestApp"
        """.trimIndent(),
    )
}

val verifyCodexAgentSwiftPackage = tasks.register<Exec>("verifyCodexAgentSwiftPackage") {
    dependsOn(stageCodexAgentAppleDistribution)
    workingDir(appleDistributionDirectory.map { it.dir("CodexAgentTestApp") })
    commandLine(
        "xcodebuild",
        "-project", "CodexAgentTestApp.xcodeproj",
        "-scheme", "CodexAgentTestApp",
        "-configuration", "Release",
        "-destination", "generic/platform=iOS",
        "-derivedDataPath", layout.buildDirectory.dir("swift-consumer-derived-data").get().asFile.absolutePath,
        "-archivePath", layout.buildDirectory.file("CodexAgentTestApp.xcarchive").get().asFile.absolutePath,
        "ARCHS=arm64",
        "CODE_SIGNING_ALLOWED=NO",
        "SKIP_INSTALL=NO",
        "clean",
        "archive",
    )
}

val verifyCodexAgentSwiftAuthenticationTests =
    tasks.register<Exec>("verifyCodexAgentSwiftAuthenticationTests") {
        dependsOn(stageCodexAgentAppleDistribution)
        workingDir(appleDistributionDirectory.map { it.dir("CodexAgentPackage") })
        commandLine(
            "/bin/bash",
            "-c",
            """
                set -euo pipefail
                runtime_id=${'$'}(xcrun simctl list -j runtimes | jq -er '.runtimes[] | select(.name == "iOS 26.5" and .isAvailable == true) | .identifier' | head -n 1)
                device_type='com.apple.CoreSimulator.SimDeviceType.iPhone-17'
                devices_json="${layout.buildDirectory.file("simulator-devices.json").get().asFile.absolutePath}"
                xcrun simctl list -j devices available > "${'$'}devices_json"
                destination_id=${'$'}(jq -er --arg runtime "${'$'}runtime_id" --arg type "${'$'}device_type" '.devices[${'$'}runtime][] | select(.isAvailable == true and .deviceTypeIdentifier == ${'$'}type) | .udid' "${'$'}devices_json" | head -n 1)
                test -n "${'$'}destination_id"
                state=${'$'}(jq -er --arg runtime "${'$'}runtime_id" --arg id "${'$'}destination_id" '.devices[${'$'}runtime][] | select(.udid == ${'$'}id) | .state' "${'$'}devices_json")
                if [ "${'$'}state" != Booted ]; then xcrun simctl boot "${'$'}destination_id"; fi
                xcrun simctl bootstatus "${'$'}destination_id" -b
                result_bundle="${layout.buildDirectory.file("swift-authentication-tests.xcresult").get().asFile.absolutePath}"
                summary="${layout.buildDirectory.file("swift-authentication-tests-summary.json").get().asFile.absolutePath}"
                rm -rf "${'$'}result_bundle"
                xcodebuild \
                    -scheme CodexAgent-Package \
                    -destination "platform=iOS Simulator,id=${'$'}destination_id" \
                    -derivedDataPath "${layout.buildDirectory.dir("swift-tests-derived-data").get().asFile.absolutePath}" \
                    -resultBundlePath "${'$'}result_bundle" \
                    CODE_SIGNING_ALLOWED=NO \
                    test
                xcrun xcresulttool get test-results summary --path "${'$'}result_bundle" --format json > "${'$'}summary"
                executed=${'$'}(jq -er '.totalTestCount' "${'$'}summary")
                failed=${'$'}(jq -er '.failedTests // 0' "${'$'}summary")
                test "${'$'}executed" -eq $expectedSwiftTestCount
                test "${'$'}failed" -eq 0
                echo "Swift authentication tests executed: ${'$'}executed"
            """.trimIndent(),
        )
    }

val packageCodexAgentAppleDistribution = tasks.register<Zip>("packageCodexAgentAppleDistribution") {
    dependsOn(stageCodexAgentAppleDistribution)
    archiveFileName.set("CodexAgentPackage-${project.version}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(appleDistributionDirectory.map { it.dir("CodexAgentPackage") })
}

val verifyIosLicensePackaging = tasks.register<Exec>("verifyIosLicensePackaging") {
    dependsOn(stageCodexAgentAppleDistribution)
    val packageDirectory = appleDistributionDirectory.map { it.dir("CodexAgentPackage") }
    val report = layout.buildDirectory.file("reports/ios-release/license-packaging.txt")
    inputs.files(
        rootProject.layout.projectDirectory.file("LICENSE"),
        rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"),
        rootProject.layout.projectDirectory.file(
            "codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt",
        ),
        rootProject.layout.projectDirectory.file(
            "codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt",
        ),
    )
    outputs.file(report)
    commandLine(
        "/bin/bash",
        "-c",
        """
            set -euo pipefail
            package="${packageDirectory.get().asFile.absolutePath}"
            cmp "${rootProject.layout.projectDirectory.file("LICENSE").asFile.absolutePath}" "${'$'}package/LICENSE.txt"
            cmp "${rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md").asFile.absolutePath}" "${'$'}package/THIRD_PARTY_NOTICES.md"
            cmp "${rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-LICENSE.txt").asFile.absolutePath}" "${'$'}package/openai-codex-LICENSE.txt"
            cmp "${rootProject.layout.projectDirectory.file("codex-agent-runtime-android/src/main/assets/openai-codex-NOTICE.txt").asFile.absolutePath}" "${'$'}package/openai-codex-NOTICE.txt"
            grep -F 'GNU General Public License v3.0 or later' "${layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath}"
            mkdir -p "${report.get().asFile.parentFile.absolutePath}"
            shasum -a 256 \
                "${'$'}package/LICENSE.txt" \
                "${'$'}package/THIRD_PARTY_NOTICES.md" \
                "${'$'}package/openai-codex-LICENSE.txt" \
                "${'$'}package/openai-codex-NOTICE.txt" > "${report.get().asFile.absolutePath}"
        """.trimIndent(),
    )
}

val swiftPackageArchiveName = "CodexAgent-${project.version}.xcframework.zip"
val packageCodexAgentSwiftPackageBinary = tasks.register<Zip>("packageCodexAgentSwiftPackageBinary") {
    dependsOn(prepareCodexAgentReleaseXCFramework)
    archiveFileName.set(swiftPackageArchiveName)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(releaseXCFrameworkDirectory) {
        into("CodexAgent.xcframework")
    }
}

val verifyIosDeploymentTargets = tasks.register<Exec>("verifyIosDeploymentTargets") {
    dependsOn(prepareCodexAgentReleaseXCFramework)
    val report = layout.buildDirectory.file("reports/ios-release/deployment-targets.txt")
    outputs.file(report)
    commandLine(
        "/bin/bash",
        "-c",
        """
            set -euo pipefail
            mkdir -p "${report.get().asFile.parentFile.absolutePath}"
            : > "${report.get().asFile.absolutePath}"
            records_file=${'$'}(mktemp)
            trap 'rm -f "${'$'}records_file"' EXIT
            count=0
            for binary in "${releaseXCFrameworkDirectory.get().asFile.absolutePath}"/*/CodexAgent.framework/CodexAgent; do
                count=${'$'}((count + 1))
                echo "== ${'$'}binary ==" | tee -a "${report.get().asFile.absolutePath}"
                case "${'$'}binary" in
                    */ios-arm64-simulator/*) expected_platform=7 ;;
                    */ios-arm64/*) expected_platform=2 ;;
                    *) exit 1 ;;
                esac
                xcrun otool -l "${'$'}binary" | awk '
                    /^[^[:space:]].*\):${'$'}/ {
                        member = ${'$'}0
                        sub(/^.*\(/, "", member)
                        sub(/\):${'$'}/, "", member)
                    }
                    ${'$'}1 == "cmd" && ${'$'}2 == "LC_BUILD_VERSION" { build = 1; next }
                    build && ${'$'}1 == "platform" { platform = ${'$'}2; next }
                    build && ${'$'}1 == "minos" { print member "|" platform ":" ${'$'}2; build = 0 }
                ' > "${'$'}records_file"
                test -s "${'$'}records_file"
                cut -d'|' -f2 "${'$'}records_file" | sort -u | tee -a "${report.get().asFile.absolutePath}"
                grep -Eq '^CodexAgent\.framework\.o\|[27]:15\.0${'$'}' "${'$'}records_file"
                awk -F'[|:]' -v platform="${'$'}expected_platform" '
                    ${'$'}2 != platform { exit 1 }
                    ${'$'}3 == "15.0" { next }
                    ${'$'}3 == "14.0" && ${'$'}1 ~ /^(std|panic_unwind|object|memchr|addr2line|gimli|cfg_if|rustc_demangle|std_detect|hashbrown|rustc_std_workspace_alloc|miniz_oxide|adler2|unwind|libc|rustc_std_workspace_core|alloc|core|compiler_builtins)-/ { next }
                    ${'$'}3 == "14.0" && ${'$'}1 ~ /^(ad3ac4dcdcbf93cb|b6006474dd997b0d|f3c5cc7ab326d4d0)-/ { next }
                    { print "Unexpected deployment target: " ${'$'}0 > "/dev/stderr"; exit 1 }
                ' "${'$'}records_file"
                /usr/libexec/PlistBuddy -c 'Print :MinimumOSVersion' "${'$'}{binary%/CodexAgent}/Info.plist" | grep -Fx '15.0'
            done
            test "${'$'}count" -eq 2
            grep -q '^2:15.0${'$'}' "${report.get().asFile.absolutePath}"
            grep -q '^7:15.0${'$'}' "${report.get().asFile.absolutePath}"
        """.trimIndent(),
    )
}

val collectIosPrivacyEvidence = tasks.register<Exec>("collectIosPrivacyEvidence") {
    dependsOn(prepareCodexAgentReleaseXCFramework, verifyCodexAgentSwiftPackage)
    val reportDirectory = layout.buildDirectory.dir("reports/ios-release/privacy")
    val symbols = reportDirectory.map { it.file("undefined-symbols.txt") }
    val packaging = reportDirectory.map { it.file("packaging.json") }
    outputs.files(symbols, packaging)
    commandLine(
        "/bin/bash",
        "-c",
        """
            set -euo pipefail
            report="${reportDirectory.get().asFile.absolutePath}"
            mkdir -p "${'$'}report"
            /usr/bin/plutil -lint "${privacyManifestFile.asFile.absolutePath}" | tee "${'$'}report/manifest-lint.txt"
            framework_count=${'$'}(find "${releaseXCFrameworkDirectory.get().asFile.absolutePath}" -name PrivacyInfo.xcprivacy -type f | wc -l | tr -d ' ')
            test "${'$'}framework_count" -eq 2
            archive="${layout.buildDirectory.file("CodexAgentTestApp.xcarchive").get().asFile.absolutePath}"
            app_count=${'$'}(find "${'$'}archive/Products/Applications" -name PrivacyInfo.xcprivacy -type f | wc -l | tr -d ' ')
            test "${'$'}app_count" -ge 1
            binary="${'$'}archive/Products/Applications/CodexAgentTestApp.app/CodexAgentTestApp"
            nm -u "${'$'}binary" > "${'$'}report/undefined-symbols.txt"
            ! grep -Eq '_(statfs|fstatfs)${'$'}' "${'$'}report/undefined-symbols.txt"
            printf '{"frameworkManifests":%s,"archivedAppManifests":%s}\n' \
                "${'$'}framework_count" "${'$'}app_count" > "${'$'}report/packaging.json"
        """.trimIndent(),
    )
}

val verifyIosPrivacyManifest = tasks.register<VerifyPrivacyRequiredReasonTask>("verifyIosPrivacyManifest") {
    dependsOn(collectIosPrivacyEvidence)
    privacyManifest.set(privacyManifestFile)
    undefinedSymbols.set(layout.buildDirectory.file("reports/ios-release/privacy/undefined-symbols.txt"))
    reviewsFile.set(rootProject.layout.projectDirectory.file("release/privacy-required-reason-reviews-0.2.0.json"))
    packagingEvidence.set(layout.buildDirectory.file("reports/ios-release/privacy/packaging.json"))
    auditFile.set(layout.buildDirectory.file("reports/ios-release/privacy/audit.json"))
}

val verifyIosReleaseBudgets = tasks.register<Exec>("verifyIosReleaseBudgets") {
    dependsOn(packageCodexAgentSwiftPackageBinary, verifyCodexAgentSwiftPackage)
    val budgets = rootProject.layout.projectDirectory.file("release/ios-budgets-0.2.0.json")
    val report = layout.buildDirectory.file("reports/ios-release/artifact-metrics.json")
    inputs.file(budgets)
    inputs.file(packageCodexAgentSwiftPackageBinary.flatMap { it.archiveFile })
    inputs.dir(releaseXCFrameworkDirectory)
    inputs.dir(layout.buildDirectory.dir("CodexAgentTestApp.xcarchive"))
    outputs.file(report)
    commandLine(
        "/bin/bash",
        "-c",
        """
            set -euo pipefail
            mkdir -p "${report.get().asFile.parentFile.absolutePath}"
            zip_bytes=${'$'}(stat -f %z "${packageCodexAgentSwiftPackageBinary.get().archiveFile.get().asFile.absolutePath}")
            device_bytes=${'$'}(stat -f %z "${releaseXCFrameworkDirectory.get().dir("ios-arm64/CodexAgent.framework").file("CodexAgent").asFile.absolutePath}")
            app="${layout.buildDirectory.file("CodexAgentTestApp.xcarchive/Products/Applications/CodexAgentTestApp.app").get().asFile.absolutePath}"
            app_bytes=${'$'}(find "${'$'}app" -type f -exec stat -f %z {} + | awk '{ total += ${'$'}1 } END { print total + 0 }')
            test "${'$'}zip_bytes" -le ${'$'}(jq -er '.artifactBytes.compressedXcframeworkMaximum' "${budgets.asFile.absolutePath}")
            test "${'$'}device_bytes" -le ${'$'}(jq -er '.artifactBytes.deviceFrameworkMaximum' "${budgets.asFile.absolutePath}")
            test "${'$'}app_bytes" -le ${'$'}(jq -er '.artifactBytes.sampleAppInstallMaximum' "${budgets.asFile.absolutePath}")
            printf '{"compressedXcframeworkBytes":%s,"deviceFrameworkBytes":%s,"sampleAppInstallBytes":%s}\n' \
                "${'$'}zip_bytes" "${'$'}device_bytes" "${'$'}app_bytes" > "${report.get().asFile.absolutePath}"
        """.trimIndent(),
    )
}

val swiftPackageChecksumFile = layout.buildDirectory.file("distributions/$swiftPackageArchiveName.sha256")
val generateCodexAgentSwiftPackageChecksum =
    tasks.register<GenerateSha256Task>("generateCodexAgentSwiftPackageChecksum") {
    dependsOn(packageCodexAgentSwiftPackageBinary)
    inputFile.set(packageCodexAgentSwiftPackageBinary.flatMap { it.archiveFile })
    outputFile.set(swiftPackageChecksumFile)
}

val verifyCodexAgentRemoteSwiftPackage =
    tasks.register<VerifySwiftPackageBinaryTask>("verifyCodexAgentRemoteSwiftPackage") {
    group = "verification"
    description = "Verifies the public SwiftPM manifest URL and binary checksum."
    dependsOn(generateCodexAgentSwiftPackageChecksum)
    manifest.set(rootProject.layout.projectDirectory.file("Package.swift"))
    checksumFile.set(swiftPackageChecksumFile)
    expectedUrl.set("https://github.com/ciurlaro/codex-agent/releases/download/v${project.version}/$swiftPackageArchiveName")
}

tasks.register("verifyIosRuntime") {
    group = "verification"
    description = "Builds and tests the embedded iOS runtime and clean Swift Package consumer."
    dependsOn(
        verifyAppleToolchain,
        testCodexIosBridge,
        testCodexIosDirectToolMode,
        "compileKotlinIosArm64",
        "iosSimulatorArm64Test",
        packageCodexAgentAppleDistribution,
        verifyCodexAgentRemoteSwiftPackage,
        verifyCodexAgentSwiftPackage,
        verifyCodexAgentSwiftAuthenticationTests,
        verifyIosDeploymentTargets,
        verifyIosLicensePackaging,
        verifyIosPrivacyManifest,
        verifyIosReleaseBudgets,
    )
}
