import java.io.File
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.tasks.Sync
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
private val resolvedCargoLockSha256 = "eaf0e5889447eaaaa0fd512219f8b1377ad5d848e3ef7644b7300e5f767c6351"
private val pinnedRustToolchain = "1.95.0"
private val rustLibrary = "libcodex_agent_ios_bridge.a"
private val minimumIosVersion = "15.0"
private val expectedSwiftTestCount = 12
private val expectedXcodeVersion = "26.6"
private val expectedXcodeBuild = "17F113"
private val expectedSwiftVersion = "6.3.3"

val provenanceRecordFile = layout.projectDirectory.file("native/provenance.json")
val provenanceInputs = mapOf(
    "adapterPatchSha256" to layout.projectDirectory.file("native/patches/0001-uninitialized-in-process-host.patch"),
    "lockPatchSha256" to layout.projectDirectory.file("native/patches/0002-locked-ios-bridge.patch"),
    "bridgeManifestSha256" to layout.projectDirectory.file("native/bridge/Cargo.toml"),
    "bridgeSourceSha256" to layout.projectDirectory.file("native/bridge/src/lib.rs"),
    "cHeaderSha256" to layout.projectDirectory.file("native/include/codex_agent_ios.h"),
)

val verifyCodexIosProvenance = tasks.register<VerifyCodexIosProvenanceTask>("verifyCodexIosProvenance") {
    group = "verification"
    description = "Verifies the pinned iOS native source and bridge provenance."
    provenanceFile.set(provenanceRecordFile)
    adapterPatch.set(provenanceInputs.getValue("adapterPatchSha256"))
    lockPatch.set(provenanceInputs.getValue("lockPatchSha256"))
    bridgeManifest.set(provenanceInputs.getValue("bridgeManifestSha256"))
    bridgeSource.set(provenanceInputs.getValue("bridgeSourceSha256"))
    cHeader.set(provenanceInputs.getValue("cHeaderSha256"))
    revision.set(codexRevision)
    archiveSha256.set(codexArchiveSha256)
    cargoLockSha256.set(codexCargoLockSha256)
    preparedCargoLockSha256.set(resolvedCargoLockSha256)
    rustToolchain.set(pinnedRustToolchain)
}

val prepareCodexIosSource = tasks.register<PrepareCodexIosSourceTask>("prepareCodexIosSource") {
    dependsOn(verifyCodexIosProvenance)
    revision.set(codexRevision)
    archiveSha256.set(codexArchiveSha256)
    cargoLockSha256.set(codexCargoLockSha256)
    preparedCargoLockSha256.set(resolvedCargoLockSha256)
    providers.gradleProperty("codexAgent.codexIosArchiveFile").orNull?.let { path ->
        localArchive.set(rootProject.layout.projectDirectory.file(path))
    }
    patches.from(layout.projectDirectory.dir("native/patches").asFileTree.matching { include("*.patch") })
    bridgeSource.set(layout.projectDirectory.dir("native/bridge"))
    outputDirectory.set(layout.buildDirectory.dir("codex-source"))
}

val codexRustRoot = layout.buildDirectory.dir("codex-source/codex-rs")

val testCodexIosBridge = tasks.register<PinnedCargoTask>("testCodexIosBridge") {
    dependsOn(prepareCodexIosSource)
    inputs.property("codexRevision", codexRevision)
    inputs.files(layout.projectDirectory.dir("native/bridge"), layout.projectDirectory.dir("native/patches"))
    toolchain.set(pinnedRustToolchain)
    workingDirectory.set(codexRustRoot)
    cargoTargetDirectory.set(layout.buildDirectory.dir("rust/host"))
    cargoArguments.set(listOf("test", "--locked", "-p", "codex-agent-ios-bridge", "--lib"))
}

val testCodexIosDirectToolMode = tasks.register<PinnedCargoTask>("testCodexIosDirectToolMode") {
    dependsOn(prepareCodexIosSource)
    inputs.property("codexRevision", codexRevision)
    inputs.files(layout.projectDirectory.dir("native/patches"))
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
    inputs.property("codexRevision", codexRevision)
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
    extraEnvironment.put("IPHONEOS_DEPLOYMENT_TARGET", minimumIosVersion)
    extraEnvironment.put(
        "CARGO_TARGET_${target.uppercase().replace('-', '_')}_RUSTFLAGS",
        "-Cdebuginfo=0",
    )
    extraEnvironment.put(
        "CFLAGS",
        "-include ${layout.projectDirectory.file("native/bridge/sqlite-ios-privacy.h").asFile.absolutePath}",
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
val privacyManifest =
    layout.projectDirectory.file("apple/Sources/CodexAgentAuthentication/PrivacyInfo.xcprivacy")

val prepareCodexAgentReleaseXCFramework =
    tasks.register<Sync>("prepareCodexAgentReleaseXCFramework") {
        dependsOn("assembleCodexAgentReleaseXCFramework")
        into(releaseXCFrameworkDirectory)
        from(assembledXCFrameworkDirectory)
        listOf("ios-arm64", "ios-arm64-simulator").forEach { slice ->
            from(privacyManifest) {
                into("$slice/CodexAgent.framework")
            }
        }
    }

val stageCodexAgentAppleDistribution = tasks.register<Sync>("stageCodexAgentAppleDistribution") {
    dependsOn(prepareCodexAgentReleaseXCFramework)
    into(appleDistributionDirectory)
    from(layout.projectDirectory.file("apple/Package.swift")) {
        into("CodexAgentPackage")
    }
    from(layout.projectDirectory.dir("apple/Sources")) {
        into("CodexAgentPackage/Sources")
    }
    from(layout.projectDirectory.dir("apple/Tests")) {
        into("CodexAgentPackage/Tests")
    }
    from(releaseXCFrameworkDirectory) {
        into("CodexAgentPackage/CodexAgent.xcframework")
    }
    from(rootProject.layout.projectDirectory.file("LICENSE")) {
        into("CodexAgentPackage")
        rename { "LICENSE.txt" }
    }
    from(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md")) {
        into("CodexAgentPackage")
    }
    from(rootProject.layout.projectDirectory.dir("codex-agent-runtime-android/src/main/assets")) {
        include("openai-codex-LICENSE.txt", "openai-codex-NOTICE.txt")
        into("CodexAgentPackage")
    }
    from(layout.projectDirectory.dir("apple/TestApp")) {
        into("CodexAgentTestApp")
    }
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

val verifyIosPrivacyManifest = tasks.register<Exec>("verifyIosPrivacyManifest") {
    dependsOn(prepareCodexAgentReleaseXCFramework, verifyCodexAgentSwiftPackage)
    val reportDirectory = layout.buildDirectory.dir("reports/ios-release/privacy")
    outputs.dir(reportDirectory)
    commandLine(
        "/bin/bash",
        "-c",
        """
            set -euo pipefail
            report="${reportDirectory.get().asFile.absolutePath}"
            mkdir -p "${'$'}report"
            /usr/bin/plutil -lint "${privacyManifest.asFile.absolutePath}" | tee "${'$'}report/manifest-lint.txt"
            framework_count=${'$'}(find "${releaseXCFrameworkDirectory.get().asFile.absolutePath}" -name PrivacyInfo.xcprivacy -type f | wc -l | tr -d ' ')
            test "${'$'}framework_count" -eq 2
            archive="${layout.buildDirectory.file("CodexAgentTestApp.xcarchive").get().asFile.absolutePath}"
            app_count=${'$'}(find "${'$'}archive/Products/Applications" -name PrivacyInfo.xcprivacy -type f | wc -l | tr -d ' ')
            test "${'$'}app_count" -ge 1
            binary="${'$'}archive/Products/Applications/CodexAgentTestApp.app/CodexAgentTestApp"
            nm -u "${'$'}binary" > "${'$'}report/undefined-symbols.txt"
            ! grep -Eq '_(statfs|fstatfs)${'$'}' "${'$'}report/undefined-symbols.txt"
            grep -Eq '_(stat|fstat|fstatat|lstat)${'$'}' "${'$'}report/undefined-symbols.txt"
            grep -q 'NSPrivacyAccessedAPICategoryFileTimestamp' "${privacyManifest.asFile.absolutePath}"
            grep -q 'C617.1' "${privacyManifest.asFile.absolutePath}"
            printf '{"frameworkManifests":%s,"archivedAppManifests":%s,"diskSpaceSymbols":false,"fileTimestampReason":"C617.1"}\n' \
                "${'$'}framework_count" "${'$'}app_count" > "${'$'}report/audit.json"
        """.trimIndent(),
    )
}

val verifyIosReleaseBudgets = tasks.register<Exec>("verifyIosReleaseBudgets") {
    dependsOn(packageCodexAgentSwiftPackageBinary, verifyCodexAgentSwiftPackage)
    val budgets = rootProject.layout.projectDirectory.file("release/ios-budgets-0.2.0.json")
    val report = layout.buildDirectory.file("reports/ios-release/artifact-metrics.json")
    inputs.file(budgets)
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
        verifyIosPrivacyManifest,
        verifyIosReleaseBudgets,
    )
}
