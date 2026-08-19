import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

private data class ReviewedPrivacyApi(val source: String, val token: String) : Comparable<ReviewedPrivacyApi> {
    override fun compareTo(other: ReviewedPrivacyApi) = compareValuesBy(this, other, { it.source }, { it.token })
}

internal fun generateIosPrivacyRequiredReasonReview(
    templateFile: File,
    policyFile: File,
    evidenceFile: File,
    outputFile: File,
) {
    val template = templateFile.readReleaseObject()
    val evidence = evidenceFile.readReleaseObject()
    validateReviewTemplate(template, policyFile, evidence)
    val exact = JsonObject(template + ("evidenceSha256" to JsonPrimitive(evidenceFile.releaseDigest())))
    outputFile.atomicWriteJson(exact)
    outputFile.parentFile.mkdirs()
    val audit = Files.createTempFile(outputFile.parentFile.toPath(), ".privacy-rebind-", ".json").toFile()
    try {
        verifyIosPrivacyAudit(policyFile, evidenceFile, outputFile, audit)
    } catch (failure: Throwable) {
        outputFile.delete()
        throw failure
    } finally {
        audit.delete()
    }
}

internal fun verifyBoundIosPrivacyReview(templateFile: File, exactFile: File, auditFile: File) {
    val template = templateFile.readReleaseObject()
    val exact = exactFile.readReleaseObject()
    val audit = auditFile.readReleaseObject()
    val evidenceHash = exact.releaseString("evidenceSha256")
    check(evidenceHash.matches(Regex("[0-9a-f]{64}"))) { "Exact privacy review evidence hash is invalid" }
    check(exact == JsonObject(template + ("evidenceSha256" to JsonPrimitive(evidenceHash)))) {
        "Exact privacy review differs from the approved decision template"
    }
    check(audit.releaseBoolean("passed")) { "Candidate privacy audit did not pass" }
    check(audit.releaseArray("errors").isEmpty()) { "Candidate privacy audit contains errors" }
    check(audit.releaseString("evidenceSha256") == evidenceHash) { "Candidate privacy audit evidence hash mismatch" }
    check(audit.releaseString("policySha256") == exact.releaseString("policySha256")) {
        "Candidate privacy audit policy hash mismatch"
    }
    check(audit.releaseString("reviewSha256") == exactFile.releaseDigest()) {
        "Candidate privacy audit review hash mismatch"
    }
    val reviewed = exact.releaseArray("reviews").map { it.jsonObject.releaseString("findingId") }.toSet()
    check(audit.releaseArray("manualReviewFindings").map { it.toString().trim('"') }.toSet() == reviewed) {
        "Candidate privacy audit findings differ from the approved decision template"
    }
}

private fun validateReviewTemplate(template: JsonObject, policyFile: File, evidence: JsonObject) {
    check(template.keys == setOf("schemaVersion", "reviewStatus", "evidenceSha256", "policySha256", "reviews")) {
        "Required-reason decision template has unexpected fields"
    }
    check(template.releaseInt("schemaVersion") == 1 && template.releaseString("reviewStatus") == "approved") {
        "Required-reason decision template is not approved schema 1"
    }
    check(template.releaseString("evidenceSha256").matches(Regex("[0-9a-f]{64}"))) {
        "Required-reason decision template evidence hash is invalid"
    }
    val policyHash = policyFile.releaseDigest()
    check(template.releaseString("policySha256") == policyHash) { "Required-reason decision policy drift" }
    check(evidence.releaseString("policySha256") == policyHash) { "Privacy evidence policy drift" }

    val reviews = template.releaseArray("reviews").map { it.jsonObject }
    val ids = reviews.map { it.releaseString("findingId") }
    check(ids.size == ids.distinct().size) { "Duplicate required-reason decision finding" }
    val current = evidence.reviewedApiInventory()
    check(ids.toSet() == current.keys) {
        "Current privacy findings differ from approved decisions: approved=${ids.sorted()} current=${current.keys.sorted()}"
    }
    reviews.forEach { review ->
        check(review.keys == setOf(
            "findingId", "reviewStatus", "disposition", "declarations", "rationale", "reviewedApis",
        )) { "Required-reason decision has unexpected fields: ${review.releaseString("findingId")}" }
        val id = review.releaseString("findingId")
        check(review.releaseString("reviewStatus") == "approved" && review.releaseString("rationale").isNotBlank()) {
            "Required-reason decision is incomplete: $id"
        }
        check(review.releaseString("disposition") in setOf("declared", "notRequired")) {
            "Required-reason decision disposition is invalid: $id"
        }
        val reviewed = review.releaseArray("reviewedApis").map { item ->
            val api = item.jsonObject
            check(api.keys == setOf("source", "token")) { "Reviewed API entry is invalid: $id" }
            ReviewedPrivacyApi(api.releaseString("source"), api.releaseString("token"))
        }
        check(reviewed.size == reviewed.distinct().size) { "Duplicate reviewed API entry: $id" }
        check(reviewed.toSet() == current.getValue(id)) { "Reviewed API inventory changed: $id" }
        if (review.releaseString("disposition") == "notRequired") {
            check(id.startsWith("ambiguous:") || current.getValue(id).all { it.source == "string" }) {
                "notRequired decision escalated beyond string-only evidence: $id"
            }
            check((review["declarations"] as? JsonArray).orEmpty().isEmpty()) {
                "notRequired decision cannot declare reasons: $id"
            }
        }
    }
}

private fun JsonObject.reviewedApiInventory(): Map<String, Set<ReviewedPrivacyApi>> {
    val detected = releaseArray("detectedRequiredReasonCategories").map { it.toString().trim('"') }.toSet()
    val manualCategories = IosPrivacyPolicy.categories
        .filter { it.name in detected && it.approvedReasons.isEmpty() }.mapTo(sortedSetOf()) { it.name }
    val hits = releaseArray("slices").flatMap { slice -> slice.jsonObject.releaseArray("members") }
        .flatMap { member -> member.jsonObject.releaseArray("requiredReasonApis") }
        .map { hit -> hit.jsonObject }
    return buildMap {
        manualCategories.forEach { category ->
            put("category:$category", hits.filter { it.releaseString("category") == category }
                .mapTo(sortedSetOf()) { ReviewedPrivacyApi(it.releaseString("source"), it.releaseString("token")) })
        }
        releaseArray("ambiguousRequiredReasonApis").forEach { item ->
            val symbol = item.jsonObject.releaseString("symbol")
            put("ambiguous:$symbol", setOf(ReviewedPrivacyApi("ambiguous-undefined-symbol", symbol)))
        }
    }
}

@CacheableTask
abstract class GenerateIosPrivacyRequiredReasonReviewTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val templateFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val policyFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val evidenceFile: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() = generateIosPrivacyRequiredReasonReview(
        templateFile.get().asFile,
        policyFile.get().asFile,
        evidenceFile.get().asFile,
        outputFile.get().asFile,
    )
}
