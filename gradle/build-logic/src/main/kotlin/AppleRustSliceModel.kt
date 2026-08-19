import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal const val IOS_DEVICE_RUST_TARGET = "aarch64-apple-ios"
internal const val IOS_SIMULATOR_RUST_TARGET = "aarch64-apple-ios-sim"
internal const val IOS_RUST_LIBRARY = "libcodex_agent_ios_bridge.a"

internal data class AppleRustSliceSpec(val target: String, val archiveName: String, val proofName: String)

internal val appleRustSliceSpecs = listOf(
    AppleRustSliceSpec(IOS_DEVICE_RUST_TARGET, "codex-agent-ios-arm64.a", "codex-agent-ios-arm64-proof.json"),
    AppleRustSliceSpec(
        IOS_SIMULATOR_RUST_TARGET,
        "codex-agent-ios-simulator-arm64.a",
        "codex-agent-ios-simulator-arm64-proof.json",
    ),
)
internal const val IOS_NATIVE_TESTS_PROOF = "native-tests-proof.json"

internal data class AppleRustEvidenceIdentity(
    val commit: String,
    val tree: String,
    val nativeInputsSha256: String,
    val provenanceSha256: String,
    val compilerSettingsSha256: String,
    val rustToolchain: String,
    val rustSrcComponent: String,
    val rustCompilerIdentitySha256: String,
    val appleToolchainIdentitySha256: String,
    val xcodeVersionSha256: String,
    val swiftVersionSha256: String,
)

internal data class AppleNativeTestsIdentity(
    val commit: String,
    val tree: String,
    val nativeInputsSha256: String,
    val provenanceSha256: String,
    val rustToolchain: String,
    val rustSrcComponent: String,
)

internal data class AppleNativeTestCommand(val taskPath: String, val cargoArguments: List<String>)

internal fun appleNativeInputDigest(repository: File, inputs: Set<File>): String {
    val root = repository.canonicalFile.toPath()
    val records = linkedMapOf<String, File>()
    inputs.forEach { input ->
        val path = input.canonicalFile.toPath()
        check(path.startsWith(root)) { "Native evidence input escapes the repository: $input" }
        check(Files.exists(path) && !Files.isSymbolicLink(path)) { "Native evidence input is missing or unsafe: $input" }
        val files = if (Files.isDirectory(path)) Files.walk(path).use { it.filter(Files::isRegularFile).toList() }
        else listOf(path)
        files.forEach { file ->
            check(!Files.isSymbolicLink(file)) { "Native evidence input contains a symbolic link: $file" }
            val relative = root.relativize(file).joinToString("/")
            check(records.put(relative, file.toFile()) == null) { "Duplicate native evidence input: $relative" }
        }
    }
    check(records.isNotEmpty()) { "Native evidence inputs are empty" }
    return records.toSortedMap().entries.joinToString("") { (path, file) ->
        "$path\u0000${file.length()}\u0000${file.releaseDigest()}\n"
    }.byteInputStream().releaseDigest()
}

internal fun appleCompilerSettingsDigest(settings: Map<String, String>): String {
    check(settings.isNotEmpty()) { "Apple Rust compiler settings are empty" }
    return settings.toSortedMap().entries.joinToString("") { (key, value) -> "$key\u0000$value\n" }
        .byteInputStream().releaseDigest()
}

internal fun verifyStaticArchive(file: File) {
    check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() > 8) {
        "Apple Rust static archive is missing or unsafe: $file"
    }
    check(file.inputStream().use { it.readNBytes(8).contentEquals("!<arch>\n".toByteArray()) }) {
        "Apple Rust artifact is not a static archive: $file"
    }
}

internal fun buildAppleRustSliceProof(
    spec: AppleRustSliceSpec,
    archive: File,
    identity: AppleRustEvidenceIdentity,
): JsonObject = buildJsonObject {
    put("schemaVersion", JsonPrimitive(2)); put("protocol", JsonPrimitive("codex-agent-ios-rust-slice-v2"))
    put("result", JsonPrimitive("passed")); put("candidateCommit", JsonPrimitive(identity.commit))
    put("candidateTree", JsonPrimitive(identity.tree)); put("cleanCheckout", JsonPrimitive(true))
    put("target", JsonPrimitive(spec.target)); put("archive", archive.releaseRecord(spec.archiveName))
    put("nativeInputsSha256", JsonPrimitive(identity.nativeInputsSha256))
    put("nativeProvenanceSha256", JsonPrimitive(identity.provenanceSha256))
    put("compilerSettingsSha256", JsonPrimitive(identity.compilerSettingsSha256))
    put("rustToolchain", JsonPrimitive(identity.rustToolchain))
    put("rustSrcComponent", JsonPrimitive(identity.rustSrcComponent))
    put("rustCompilerIdentitySha256", JsonPrimitive(identity.rustCompilerIdentitySha256))
    put("appleToolchainIdentitySha256", JsonPrimitive(identity.appleToolchainIdentitySha256))
    put("xcodeVersionSha256", JsonPrimitive(identity.xcodeVersionSha256))
    put("swiftVersionSha256", JsonPrimitive(identity.swiftVersionSha256))
}

internal fun buildAppleNativeTestsProof(
    identity: AppleNativeTestsIdentity,
    commands: List<AppleNativeTestCommand>,
): JsonObject = buildJsonObject {
    put("schemaVersion", JsonPrimitive(2)); put("protocol", JsonPrimitive("codex-agent-ios-native-tests-v2"))
    put("result", JsonPrimitive("passed")); put("candidateCommit", JsonPrimitive(identity.commit))
    put("candidateTree", JsonPrimitive(identity.tree)); put("cleanCheckout", JsonPrimitive(true))
    put("commandCount", JsonPrimitive(commands.size)); put("passedCommandCount", JsonPrimitive(commands.size))
    put("commands", buildJsonArray {
        commands.forEach { command -> add(buildJsonObject {
            put("taskPath", JsonPrimitive(command.taskPath))
            put("cargoArguments", JsonArray(command.cargoArguments.map(::JsonPrimitive)))
            put("result", JsonPrimitive("passed"))
        }) }
    })
    put("nativeInputsSha256", JsonPrimitive(identity.nativeInputsSha256))
    put("nativeProvenanceSha256", JsonPrimitive(identity.provenanceSha256))
    put("rustToolchain", JsonPrimitive(identity.rustToolchain)); put("rustSrcComponent", JsonPrimitive(identity.rustSrcComponent))
}

internal fun verifyAppleRustEvidenceDirectory(
    directory: File,
    expected: Map<String, AppleRustEvidenceIdentity>,
    expectedNativeTests: AppleNativeTestsIdentity,
    expectedCommands: List<AppleNativeTestCommand>,
) {
    check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "iOS native evidence directory is missing" }
    check(expected.keys == appleRustSliceSpecs.map(AppleRustSliceSpec::target).toSet()) {
        "Apple native evidence expected-identity targets mismatch"
    }
    val expectedNames = appleRustSliceSpecs.flatMap { listOf(it.archiveName, it.proofName) }.toSet() + IOS_NATIVE_TESTS_PROOF
    val actual = directory.listFiles()?.onEach {
        check(it.isFile && !Files.isSymbolicLink(it.toPath())) { "iOS native evidence contains an unsafe entry: ${it.name}" }
    }?.map(File::getName)?.toSet() ?: emptySet()
    check(actual == expectedNames) { "iOS native evidence files mismatch: expected $expectedNames, found $actual" }
    appleRustSliceSpecs.forEach { spec ->
        val archive = directory.resolve(spec.archiveName); verifyStaticArchive(archive)
        verifySliceProof(directory.resolve(spec.proofName).readReleaseObject(), spec, archive, expected.getValue(spec.target))
    }
    verifyTestsProof(directory.resolve(IOS_NATIVE_TESTS_PROOF).readReleaseObject(), expectedNativeTests, expectedCommands)
}

private val sliceKeys = setOf(
    "schemaVersion", "protocol", "result", "candidateCommit", "candidateTree", "cleanCheckout", "target", "archive",
    "nativeInputsSha256", "nativeProvenanceSha256", "compilerSettingsSha256", "rustToolchain", "rustSrcComponent",
    "rustCompilerIdentitySha256", "appleToolchainIdentitySha256",
    "xcodeVersionSha256", "swiftVersionSha256",
)

private fun verifySliceProof(proof: JsonObject, spec: AppleRustSliceSpec, archive: File, expected: AppleRustEvidenceIdentity) {
    check(proof.keys == sliceKeys && proof.releaseInt("schemaVersion") == 2 &&
        proof.releaseString("protocol") == "codex-agent-ios-rust-slice-v2" && proof.releaseString("result") == "passed") {
        "Invalid Apple Rust slice proof schema"
    }
    verifyIdentity(proof, expected)
    check(proof.releaseString("target") == spec.target) { "Apple Rust slice target mismatch" }
    val record = proof.releaseObject("archive")
    check(record.keys == setOf("fileName", "bytes", "sha256") && record.releaseString("fileName") == spec.archiveName) {
        "Apple Rust slice archive record mismatch"
    }
    verifyReleaseRecord(archive, record)
}

private fun verifyTestsProof(proof: JsonObject, expected: AppleNativeTestsIdentity, commands: List<AppleNativeTestCommand>) {
    val keys = setOf(
        "schemaVersion", "protocol", "result", "candidateCommit", "candidateTree", "cleanCheckout",
        "commandCount", "passedCommandCount", "commands", "nativeInputsSha256", "nativeProvenanceSha256",
        "rustToolchain", "rustSrcComponent",
    )
    check(proof.keys == keys && proof.releaseInt("schemaVersion") == 2 &&
        proof.releaseString("protocol") == "codex-agent-ios-native-tests-v2" && proof.releaseString("result") == "passed") {
        "Invalid Apple native-test proof schema"
    }
    verifyCheckoutIdentity(proof, expected.commit, expected.tree)
    mapOf(
        "nativeInputsSha256" to expected.nativeInputsSha256,
        "nativeProvenanceSha256" to expected.provenanceSha256,
        "rustToolchain" to expected.rustToolchain,
        "rustSrcComponent" to expected.rustSrcComponent,
    ).forEach { (key, value) -> check(proof.releaseString(key) == value) { "Apple native-test $key mismatch" } }
    check(proof.releaseInt("commandCount") == commands.size && proof.releaseInt("passedCommandCount") == commands.size) {
        "Apple native-test pass count mismatch"
    }
    val actual = proof.releaseArray("commands").map { element ->
        val command = element as? JsonObject ?: error("Apple native-test command is not an object")
        check(command.keys == setOf("taskPath", "cargoArguments", "result") && command.releaseString("result") == "passed") {
            "Invalid Apple native-test command"
        }
        AppleNativeTestCommand(command.releaseString("taskPath"), command.releaseArray("cargoArguments").map {
            (it as JsonPrimitive).content
        })
    }
    check(actual == commands) { "Apple native-test commands mismatch" }
}

private fun verifyIdentity(proof: JsonObject, expected: AppleRustEvidenceIdentity) {
    verifyCheckoutIdentity(proof, expected.commit, expected.tree)
    mapOf(
        "nativeInputsSha256" to expected.nativeInputsSha256, "nativeProvenanceSha256" to expected.provenanceSha256,
        "compilerSettingsSha256" to expected.compilerSettingsSha256, "rustToolchain" to expected.rustToolchain,
        "rustSrcComponent" to expected.rustSrcComponent, "xcodeVersionSha256" to expected.xcodeVersionSha256,
        "swiftVersionSha256" to expected.swiftVersionSha256,
        "rustCompilerIdentitySha256" to expected.rustCompilerIdentitySha256,
        "appleToolchainIdentitySha256" to expected.appleToolchainIdentitySha256,
    ).forEach { (key, value) -> check(proof.releaseString(key) == value) { "Apple native evidence $key mismatch" } }
}

private fun verifyCheckoutIdentity(proof: JsonObject, commit: String, tree: String) {
    check(commit.matches(Regex("[0-9a-f]{40}")) && proof.releaseString("candidateCommit") == commit) {
        "Apple native evidence commit mismatch"
    }
    check(proof.releaseString("candidateTree") == tree && proof.releaseBoolean("cleanCheckout")) {
        "Apple native evidence tree/clean-state mismatch"
    }
}
