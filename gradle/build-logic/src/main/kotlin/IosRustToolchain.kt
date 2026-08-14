import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import org.gradle.api.Project
import org.gradle.api.provider.Provider

internal fun Project.commandIdentity(vararg command: String): Provider<String> = providers.exec {
    commandLine(*command)
}.standardOutput.asText.map(String::trim)

internal fun Project.appleRustCompilerIdentity(toolchain: String): Provider<String> =
    commandIdentity("rustup", "run", toolchain, "rustc", "-vV")

internal fun Project.appleSdkToolchainIdentity(target: String): Provider<String> {
    val sdk = when (target) {
        IOS_DEVICE_RUST_TARGET -> "iphoneos"
        IOS_SIMULATOR_RUST_TARGET -> "iphonesimulator"
        else -> error("Unsupported Apple Rust target: $target")
    }
    val xcode = commandIdentity("xcodebuild", "-version")
    val sdkVersion = commandIdentity("xcrun", "--sdk", sdk, "--show-sdk-version")
    val sdkBuild = commandIdentity("xcrun", "--sdk", sdk, "--show-sdk-build-version")
    return xcode.zip(sdkVersion) { xcodeValue, version -> "xcode=$xcodeValue\nsdk=$sdk\nversion=$version" }
        .zip(sdkBuild) { identity, build -> "$identity\nbuild=$build" }
}

internal fun Project.rustcWrapperFiles(command: Provider<String>): Provider<List<File>> {
    val searchPath = providers.environmentVariable("PATH").orElse("")
    return command.zip(searchPath) { value, path ->
        if (value.isBlank()) emptyList() else listOf(resolveRustcWrapper(value, path))
    }
}

internal fun Project.iosCargoExecutionEnvironment(): Provider<Map<String, String>> {
    val path = providers.environmentVariable("PATH").orElse("")
    val cargoHome = providers.environmentVariable("CARGO_HOME")
        .orElse(providers.systemProperty("user.home").map { "$it/.cargo" })
    val developerDirectory = providers.environmentVariable("DEVELOPER_DIR").orElse("")
    val sccacheEnabled = providers.environmentVariable("SCCACHE_GHA_ENABLED").orElse("")
    val sccacheVersion = providers.environmentVariable("SCCACHE_GHA_VERSION").orElse("")
    val sccacheRwMode = providers.environmentVariable("SCCACHE_GHA_RW_MODE").orElse("")
    return path.zip(cargoHome) { pathValue, cargoHomeValue ->
        linkedMapOf("PATH" to pathValue, "CARGO_HOME" to File(cargoHomeValue).canonicalPath)
    }.zip(developerDirectory) { values, value ->
        values + value.takeIf(String::isNotBlank)?.let { mapOf("DEVELOPER_DIR" to File(it).canonicalPath) }.orEmpty()
    }.zip(sccacheEnabled) { values, value ->
        values + value.takeIf(String::isNotBlank)?.let { mapOf("SCCACHE_GHA_ENABLED" to it) }.orEmpty()
    }.zip(sccacheVersion) { values, value ->
        values + value.takeIf(String::isNotBlank)?.let { mapOf("SCCACHE_GHA_VERSION" to it) }.orEmpty()
    }.zip(sccacheRwMode) { values, value ->
        values + sccacheGhaRwModeEnvironment(value)
    }
}

internal fun externalCargoConfigurationState(workingDirectory: File, cargoHome: String): Map<String, String> {
    val candidates = buildList {
        generateSequence(workingDirectory.canonicalFile.parentFile) { it.parentFile }.forEach { ancestor ->
            add(ancestor.resolve(".cargo/config")); add(ancestor.resolve(".cargo/config.toml"))
        }
        add(File(cargoHome, "config")); add(File(cargoHome, "config.toml"))
    }.distinctBy { it.absolutePath }
    return candidates.associate { candidate ->
        candidate.absolutePath to if (Files.exists(candidate.toPath(), NOFOLLOW_LINKS)) "present" else "missing"
    }
}

internal fun Project.iosRustSysroot(rustToolchain: String): Provider<String> = providers.exec {
    commandLine("rustup", "run", rustToolchain, "rustc", "--print", "sysroot")
}.standardOutput.asText.map { File(it.trim()).canonicalPath }

internal fun requiredRustSrcManifest(rustSysroot: String): File =
    File(rustSysroot, "lib/rustlib/src/rust/library/Cargo.toml").also {
        check(it.isFile) { "Pinned Rust rust-src component is missing: ${it.path}" }
    }

internal fun Project.iosReleaseAbsolutePathPrefixes(rustToolchain: String): Provider<List<String>> =
    iosReleaseAbsolutePathPrefixes(iosRustSysroot(rustToolchain))

internal fun Project.iosReleaseAbsolutePathPrefixes(rustSysroot: Provider<String>): Provider<List<String>> {
    val builderHome = providers.systemProperty("user.home").map { File(it).canonicalPath }
    val cargoHome = providers.environmentVariable("CARGO_HOME")
        .orElse(providers.systemProperty("user.home").map { "$it/.cargo" })
        .map { File(it).canonicalPath }
    val projectRoot = rootProject.layout.projectDirectory.asFile.canonicalPath
    val preparedSource = layout.buildDirectory.dir("codex-source/codex-rs").map { it.asFile.canonicalPath }
    return builderHome.zip(cargoHome) { home, cargo -> listOf(home, cargo) }
        .zip(rustSysroot) { prefixes, sysroot -> prefixes + sysroot }
        .map { prefixes -> prefixes + projectRoot }
        .zip(preparedSource) { prefixes, source -> prefixes + source }
}

internal fun remapIosReleasePaths(prefixes: List<String>, policy: Map<String, String>): List<String> {
    val virtualPrefixKeys = listOf(
        "releaseRustBuilderHomePrefix",
        "releaseRustCargoHomePrefix",
        "releaseRustSysrootPrefix",
        "releaseRustProjectRootPrefix",
        "releaseRustPreparedSourcePrefix",
    )
    check(prefixes.size == virtualPrefixKeys.size) { "iOS release path-remap roots are incomplete" }
    return prefixes.zip(virtualPrefixKeys).map { (prefix, key) -> "$prefix=${policy.getValue(key)}" }
}
