import java.io.File

internal const val CANDIDATE_CI_PROVENANCE_FILE = "ci-provenance.json"

internal fun verifyCandidateCiProvenance(file: File, expectedCommit: String) {
    check(file.name == CANDIDATE_CI_PROVENANCE_FILE && file.isFile) {
        "Exact CI provenance receipt is required"
    }
    val receipt = file.readReleaseObject()
    check(receipt.keys == setOf(
        "schemaVersion", "repository", "workflowPath", "runId", "runAttempt",
        "event", "headBranch", "headSha", "conclusion",
    )) { "CI provenance receipt fields are invalid" }
    check(receipt.releaseInt("schemaVersion") == 1) { "CI provenance schema must be 1" }
    check(receipt.releaseString("repository") == "ciurlaro/codex-agent") { "CI repository mismatch" }
    check(receipt.releaseString("workflowPath") == ".github/workflows/ci.yml") { "CI workflow mismatch" }
    check(receipt.releaseLong("runId") > 0 && receipt.releaseInt("runAttempt") > 0) {
        "CI run identity is invalid"
    }
    check(receipt.releaseString("event") == "push" && receipt.releaseString("headBranch") == "main") {
        "CI run is not a main push"
    }
    check(expectedCommit.matches(Regex("[0-9a-f]{40}")) &&
        receipt.releaseString("headSha") == expectedCommit) { "CI commit mismatch" }
    check(receipt.releaseString("conclusion") == "success") { "CI run did not succeed" }
}
