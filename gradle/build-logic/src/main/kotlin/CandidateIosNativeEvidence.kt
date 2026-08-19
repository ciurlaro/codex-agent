import java.io.File
import kotlinx.serialization.json.JsonObject

internal fun verifyCandidateIosNativeEvidence(file: File, expectedCommit: String) {
    check(file.name == "ios-native-evidence.json" && file.isFile) { "Canonical iOS native evidence is missing" }
    val evidence = file.readReleaseObject()
    check(evidence.keys == setOf(
        "schemaVersion", "protocol", "result", "candidateCommit", "candidateTree", "cleanCheckout",
        "nativeInputsSha256", "nativeProvenanceSha256", "compilerSettingsSha256", "rustToolchain",
        "rustSrcComponent", "rustCompilerIdentitySha256", "xcodeVersionSha256", "swiftVersionSha256",
        "nativeTestsProofSha256", "slices",
    )) { "iOS native evidence schema fields are invalid" }
    check(evidence.releaseInt("schemaVersion") == 2 &&
        evidence.releaseString("protocol") == "codex-agent-ios-native-evidence-v2") {
        "Unsupported iOS native evidence schema"
    }
    check(evidence.releaseString("result") == "passed" && evidence.releaseBoolean("cleanCheckout")) {
        "iOS native evidence did not pass from a clean checkout"
    }
    check(evidence.releaseString("candidateCommit") == expectedCommit &&
        evidence.releaseString("candidateTree").matches(Regex("[0-9a-f]{40}"))) {
        "iOS native evidence identity mismatch"
    }
    listOf(
        "nativeInputsSha256", "nativeProvenanceSha256", "compilerSettingsSha256",
        "rustCompilerIdentitySha256", "xcodeVersionSha256", "swiftVersionSha256", "nativeTestsProofSha256",
    ).forEach { field ->
        check(evidence.releaseString(field).matches(Regex("[0-9a-f]{64}"))) {
            "iOS native evidence has an invalid $field"
        }
    }
    check(evidence.releaseString("rustToolchain").isNotBlank() && evidence.releaseString("rustSrcComponent") == "required") {
        "iOS native evidence Rust toolchain is invalid"
    }
    val slices = evidence.releaseArray("slices").map { it as? JsonObject ?: error("Invalid iOS native slice") }
    check(slices.size == appleRustSliceSpecs.size) { "iOS native evidence slice set is incomplete" }
    val byTarget = slices.associateBy { slice ->
        check(slice.keys == setOf("target", "archive", "proofSha256", "appleToolchainIdentitySha256")) {
            "iOS native slice fields are invalid"
        }
        slice.releaseString("target")
    }
    check(byTarget.keys == appleRustSliceSpecs.map(AppleRustSliceSpec::target).toSet()) {
        "iOS native evidence targets are invalid"
    }
    appleRustSliceSpecs.forEach { spec ->
        val slice = byTarget.getValue(spec.target)
        val archive = slice.releaseObject("archive")
        check(archive.keys == setOf("fileName", "bytes", "sha256") &&
            archive.releaseString("fileName") == spec.archiveName && archive.releaseLong("bytes") > 8 &&
            archive.releaseString("sha256").matches(Regex("[0-9a-f]{64}")) &&
            slice.releaseString("proofSha256").matches(Regex("[0-9a-f]{64}")) &&
            slice.releaseString("appleToolchainIdentitySha256").matches(Regex("[0-9a-f]{64}"))) {
            "iOS native evidence archive binding is invalid for ${spec.target}"
        }
    }
}
