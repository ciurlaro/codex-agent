import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

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
private val minimumIosVersion = "14.0"

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

val stageCodexAgentAppleDistribution = tasks.register<Sync>("stageCodexAgentAppleDistribution") {
    dependsOn("assembleCodexAgentReleaseXCFramework")
    into(appleDistributionDirectory)
    from(layout.projectDirectory.file("apple/Package.swift")) {
        into("CodexAgentPackage")
    }
    from(layout.projectDirectory.dir("apple/Sources")) {
        into("CodexAgentPackage/Sources")
    }
    from(layout.buildDirectory.dir("XCFrameworks/release/CodexAgent.xcframework")) {
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
        "-destination", "generic/platform=iOS Simulator",
        "-derivedDataPath", layout.buildDirectory.dir("swift-consumer-derived-data").get().asFile.absolutePath,
        "ARCHS=arm64",
        "ONLY_ACTIVE_ARCH=YES",
        "CODE_SIGNING_ALLOWED=NO",
        "clean",
        "build",
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
    dependsOn("assembleCodexAgentReleaseXCFramework")
    archiveFileName.set(swiftPackageArchiveName)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(layout.buildDirectory.dir("XCFrameworks/release/CodexAgent.xcframework")) {
        into("CodexAgent.xcframework")
    }
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
        testCodexIosBridge,
        testCodexIosDirectToolMode,
        "compileKotlinIosArm64",
        "iosSimulatorArm64Test",
        packageCodexAgentAppleDistribution,
        verifyCodexAgentRemoteSwiftPackage,
        verifyCodexAgentSwiftPackage,
    )
}
