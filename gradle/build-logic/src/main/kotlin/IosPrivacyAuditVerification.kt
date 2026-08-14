import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun verifyIosPrivacyAudit(
    policyFile: File,
    evidenceFile: File,
    reviewFile: File?,
    auditFile: File,
) {
    val expectedPolicy = releaseJson.encodeToString(JsonObject.serializer(), IosPrivacyPolicy.json()) + "\n"
    val evidence = evidenceFile.readReleaseObject()
    val errors = mutableListOf<String>()
    if (policyFile.readText() != expectedPolicy) errors += "privacy policy differs from the maintained policy"
    if (evidence.releaseString("policySha256") != policyFile.releaseDigest()) {
        errors += "privacy evidence policy hash mismatch"
    }
    val declarations = evidence.releaseObject("manifest").releaseArray("declaredRequiredReasonApis")
        .associate { item ->
            val value = item.jsonObject
            value.releaseString("category") to value.releaseArray("reasons").strings()
        }
    val detected = evidence.releaseArray("detectedRequiredReasonCategories").strings().toSortedSet()
    val categorySources = evidence.categorySources()
    val ambiguous = evidence.releaseArray("ambiguousRequiredReasonApis").associate { item ->
        val value = item.jsonObject
        value.releaseString("symbol") to value.releaseArray("possibleCategories").strings().toSet()
    }
    val manualFindings = buildList {
        detected.forEach { category ->
            if (IosPrivacyPolicy.categories.single { it.name == category }.approvedReasons.isEmpty()) {
                add("category:$category")
            }
        }
        ambiguous.keys.forEach { add("ambiguous:$it") }
    }.toSortedSet()
    val expectedReasons = IosPrivacyPolicy.categories.associate { category ->
        category.name to if (category.name in detected) category.approvedReasons.toMutableSet() else mutableSetOf()
    }.toMutableMap()
    val reviewedCategories = mutableSetOf<String>()
    val waivedCategories = mutableSetOf<String>()
    validateReviews(
        reviewFile,
        evidenceFile,
        policyFile,
        manualFindings,
        ambiguous,
        categorySources,
        expectedReasons,
        reviewedCategories,
        waivedCategories,
        errors,
    )
    val expectedCategories = ((detected - waivedCategories) + reviewedCategories).toSortedSet()
    errors += (expectedCategories - declarations.keys).map { "missing declaration: $it" }
    errors += (declarations.keys - expectedCategories).map { "unnecessary declaration: $it" }
    expectedCategories.intersect(declarations.keys).forEach { category ->
        val expected = expectedReasons.getValue(category).toSortedSet()
        if (declarations.getValue(category).toSortedSet() != expected) {
            errors += "reason mismatch: $category expected=$expected declared=${declarations.getValue(category)}"
        }
    }
    validatePlacement(evidence, errors)
    val audit = buildJsonObject {
        put("schemaVersion", JsonPrimitive(1))
        put("passed", JsonPrimitive(errors.isEmpty()))
        put("policySha256", JsonPrimitive(policyFile.releaseDigest()))
        put("evidenceSha256", JsonPrimitive(evidenceFile.releaseDigest()))
        reviewFile?.takeIf(File::isFile)?.let { put("reviewSha256", JsonPrimitive(it.releaseDigest())) }
        put("manualReviewFindings", manualFindings.jsonStrings())
        put("errors", errors.jsonStrings())
    }
    auditFile.atomicWriteJson(audit)
    check(errors.isEmpty()) { "iOS privacy audit failed: ${errors.joinToString()}" }
}

private fun validateReviews(
    reviewFile: File?,
    evidenceFile: File,
    policyFile: File,
    findings: Set<String>,
    ambiguous: Map<String, Set<String>>,
    categorySources: Map<String, Set<String>>,
    expectedReasons: MutableMap<String, MutableSet<String>>,
    reviewedCategories: MutableSet<String>,
    waivedCategories: MutableSet<String>,
    errors: MutableList<String>,
) {
    if (reviewFile == null || !reviewFile.isFile) {
        if (findings.isNotEmpty()) errors += findings.map { "blocking manual review required: $it" }
        return
    }
    val record = reviewFile.readReleaseObject()
    if (record.releaseStringOrNull("reviewStatus") != "approved") errors += "required-reason review is not approved"
    if (record.releaseStringOrNull("evidenceSha256") != evidenceFile.releaseDigest()) errors += "review evidence hash mismatch"
    if (record.releaseStringOrNull("policySha256") != policyFile.releaseDigest()) errors += "review policy hash mismatch"
    val reviews = record.releaseArray("reviews").map { it.jsonObject }
    val ids = reviews.map { it.releaseString("findingId") }
    if (ids.size != ids.distinct().size) errors += "duplicate required-reason review finding"
    errors += (findings - ids.toSet()).map { "missing required-reason review: $it" }
    errors += (ids.toSet() - findings).map { "stale required-reason review: $it" }
    reviews.filter { it.releaseString("findingId") in findings }.forEach { review ->
        val id = review.releaseString("findingId")
        if (review.releaseStringOrNull("reviewStatus") != "approved") errors += "review is not approved: $id"
        if (review.releaseStringOrNull("rationale").isNullOrBlank()) errors += "review rationale is missing: $id"
        val declarations = review["declarations"] as? JsonArray ?: JsonArray(emptyList())
        when (review.releaseStringOrNull("disposition")) {
            "notRequired" -> validateNotRequiredReview(
                id, declarations, categorySources, waivedCategories, errors,
            )
            "declared" -> validateReviewedDeclarations(id, declarations, ambiguous, expectedReasons, reviewedCategories, errors)
            else -> errors += "invalid review disposition: $id"
        }
    }
}

private fun validateNotRequiredReview(
    finding: String,
    declarations: JsonArray,
    categorySources: Map<String, Set<String>>,
    waivedCategories: MutableSet<String>,
    errors: MutableList<String>,
) {
    if (declarations.isNotEmpty()) {
        errors += "notRequired review is invalid: $finding"
        return
    }
    if (finding.startsWith("ambiguous:")) return
    val category = finding.removePrefix("category:")
    val sources = categorySources[category].orEmpty()
    if (!finding.startsWith("category:") || sources != setOf("string")) {
        errors += "notRequired review requires string-only category evidence: $finding sources=${sources.sorted()}"
        return
    }
    waivedCategories += category
}

private fun validateReviewedDeclarations(
    finding: String,
    declarations: JsonArray,
    ambiguous: Map<String, Set<String>>,
    expectedReasons: MutableMap<String, MutableSet<String>>,
    reviewedCategories: MutableSet<String>,
    errors: MutableList<String>,
) {
    if (declarations.isEmpty()) {
        errors += "declared review has no declarations: $finding"
        return
    }
    val allowedCategories = if (finding.startsWith("category:")) {
        setOf(finding.removePrefix("category:"))
    } else {
        ambiguous[finding.removePrefix("ambiguous:")].orEmpty()
    }
    declarations.map { it.jsonObject }.forEach { declaration ->
        val category = declaration.releaseString("category")
        val reasons = declaration.releaseArray("reasons").strings().toSet()
        val policy = IosPrivacyPolicy.categories.find { it.name == category }
        if (category !in allowedCategories) errors += "review category is invalid for $finding: $category"
        if (reasons.isEmpty() || policy == null || !policy.allowedReasons.containsAll(reasons)) {
            errors += "review reasons are invalid for $finding: $category"
        } else {
            expectedReasons.getValue(category).addAll(reasons)
            reviewedCategories += category
        }
    }
}

private fun validatePlacement(evidence: JsonObject, errors: MutableList<String>) {
    val manifestHash = evidence.releaseObject("manifest").releaseObject("file").releaseString("sha256")
    val frameworks = evidence.releaseArray("frameworkManifests").map { it.jsonObject }
    val expectedPaths = setOf(
        "ios-arm64/CodexAgent.framework/PrivacyInfo.xcprivacy",
        "ios-arm64-simulator/CodexAgent.framework/PrivacyInfo.xcprivacy",
    )
    if (frameworks.map { it.releaseString("path") }.toSet() != expectedPaths) {
        errors += "XCFramework must contain exactly the two expected privacy manifests"
    }
    if (frameworks.any { it.releaseString("sha256") != manifestHash }) {
        errors += "XCFramework privacy manifest differs from the reviewed manifest"
    }
    val archived = evidence.releaseArray("archivedSampleApplicationManifests").map { it.jsonObject }
    if (archived.none { it.releaseString("sha256") == manifestHash }) {
        errors += "reviewed privacy manifest is absent from the archived sample application"
    }
}

private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }

private fun JsonObject.categorySources(): Map<String, Set<String>> = releaseArray("slices")
    .flatMap { slice -> slice.jsonObject.releaseArray("members") }
    .flatMap { member -> member.jsonObject.releaseArray("requiredReasonApis") }
    .map { hit ->
        val value = hit.jsonObject
        value.releaseString("category") to value.releaseString("source")
    }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, sources) -> sources.toSet() }
