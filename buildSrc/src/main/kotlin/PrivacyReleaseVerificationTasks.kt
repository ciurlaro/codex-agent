import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class VerifyPublicationReadinessTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val approvalsFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyInventory: RegularFileProperty

    @TaskAction
    fun verify() = verifyPublicationReadiness(
        approvalsFile.get().asFile,
        privacyManifest.get().asFile,
        privacyInventory.get().asFile,
    )
}

internal fun verifyPublicationReadiness(approvalsFile: File, manifest: File, inventoryFile: File) {
    val approvals = approvalsFile.jsonObject()
    val inventory = inventoryFile.jsonObject()
    check(approvals.string("privacyManifestSha256") == manifest.sha256()) {
        "Privacy approval manifest hash mismatch"
    }
    check(approvals.string("privacyDataFlowInventorySha256") == inventoryFile.sha256()) {
        "Privacy approval inventory hash mismatch"
    }

    val reviewStatus = inventory.string("reviewStatus")
    check(reviewStatus == "pending" || reviewStatus == "approved") {
        "Privacy inventory reviewStatus must be pending or approved"
    }
    val privacyApproved = approvals.boolean("privacyCollectedDataReviewApproved")
    if (privacyApproved) {
        check(reviewStatus == "approved") { "Approved privacy review requires reviewStatus=approved" }
        when (inventory.stringOrNull("terminalCollectedDataDecision")) {
            "declare" -> {
                val dataTypes = inventory.objectList("appleCollectedDataTypes")
                check(dataTypes.isNotEmpty()) { "Approved declaration requires Apple data types" }
                dataTypes.forEach { type ->
                    check(type.string("appleDataType").isNotBlank()) { "Apple data type is missing" }
                    check(type.stringList("purposes").isNotEmpty()) { "Apple data type purposes are missing" }
                }
            }
            "noSdkDeclaration" -> check(
                !inventory.stringOrNull("reviewedNoSdkDeclarationRationale").isNullOrBlank(),
            ) { "No-SDK-declaration decision requires a reviewed rationale" }
            else -> error("Approved privacy review requires an explicit terminal collected-data decision")
        }
    }

    val blockers = buildList {
        if (!privacyApproved) add("privacyCollectedDataReviewApproved=false")
        if (!approvals.boolean("staticFrameworkGplDistributionApproved")) {
            add("staticFrameworkGplDistributionApproved=false")
        }
    }
    check(blockers.isEmpty()) { "External approvals pending: ${blockers.joinToString()}" }
}

@CacheableTask
abstract class VerifyPrivacyRequiredReasonTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val privacyManifest: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val undefinedSymbols: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val reviewsFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packagingEvidence: RegularFileProperty

    @get:OutputFile
    abstract val auditFile: RegularFileProperty

    @TaskAction
    fun verify() = verifyPrivacyRequiredReasons(
        privacyManifest.get().asFile,
        undefinedSymbols.get().asFile,
        reviewsFile.get().asFile,
        packagingEvidence.get().asFile,
        auditFile.get().asFile,
    )
}

internal fun verifyPrivacyRequiredReasons(
    manifestFile: File,
    symbolsFile: File,
    reviewsFile: File,
    packagingEvidenceFile: File,
    auditFile: File,
) {
    val manifest = manifestFile.readText()
    val symbols = symbolsFile.readLines()
        .map { it.trim().substringAfterLast(' ') }
        .filter(String::isNotBlank)
        .map { it.substringBefore('$') }
        .toSortedSet()
    val known = mapOf(
        "_stat" to ("NSPrivacyAccessedAPICategoryFileTimestamp" to "C617.1"),
        "_fstat" to ("NSPrivacyAccessedAPICategoryFileTimestamp" to "C617.1"),
        "_fstatat" to ("NSPrivacyAccessedAPICategoryFileTimestamp" to "C617.1"),
        "_lstat" to ("NSPrivacyAccessedAPICategoryFileTimestamp" to "C617.1"),
    )
    val ambiguous = setOf("_getattrlist", "_fgetattrlist", "_getattrlistbulk")
    val detected = symbols.filter { it in known || it in ambiguous }
    val declared = mutableListOf<String>()
    val reviewed = mutableListOf<String>()
    val findings = mutableListOf<Map<String, String>>()

    detected.filter { it in known }.forEach { symbol ->
        val (category, reason) = known.getValue(symbol)
        if (category in manifest && reason in manifest) {
            declared += symbol
        } else {
            findings += mapOf("symbol" to symbol, "finding" to "missing declaration $category/$reason")
        }
    }

    val reviews = reviewsFile.jsonObject().objectList("reviews").associateBy { it.string("symbol") }
    detected.filter { it in ambiguous }.forEach { symbol ->
        val review = reviews[symbol]
        val rationale = review?.stringOrNull("rationale")
        val valid = review?.stringOrNull("reviewStatus") == "approved" && !rationale.isNullOrBlank() &&
            when (review.stringOrNull("disposition")) {
                "notRequired" -> true
                "declared" -> {
                    val category = review.stringOrNull("category")
                    val reasons = review.stringList("reasons")
                    !category.isNullOrBlank() && category in manifest && reasons.isNotEmpty() &&
                        reasons.all { it in manifest }
                }
                else -> false
            }
        if (valid) reviewed += symbol else findings += mapOf(
            "symbol" to symbol,
            "finding" to "blocking manual review required",
        )
    }

    val audit = linkedMapOf<String, Any?>(
        "packaging" to packagingEvidenceFile.jsonObject(),
        "detectedApiSymbols" to detected,
        "declaredApiSymbols" to declared,
        "explicitlyReviewedApiSymbols" to reviewed,
        "manualReviewFindings" to findings,
    )
    auditFile.writeJson(audit)
    check(findings.isEmpty()) {
        "Privacy required-reason manual review is incomplete: ${findings.joinToString { it.getValue("symbol") }}"
    }
}

@Suppress("UNCHECKED_CAST")
private fun File.jsonObject(): Map<String, Any?> = JsonSlurper().parse(this) as? Map<String, Any?>
    ?: error("Expected JSON object: $path")

private fun Map<String, Any?>.string(name: String): String = stringOrNull(name)
    ?: error("Missing JSON string: $name")
private fun Map<String, Any?>.stringOrNull(name: String): String? = this[name] as? String
private fun Map<String, Any?>.boolean(name: String): Boolean = this[name] as? Boolean
    ?: error("Missing JSON boolean: $name")
private fun Map<String, Any?>.stringList(name: String): List<String> =
    (this[name] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.objectList(name: String): List<Map<String, Any?>> =
    (this[name] as? List<*>)?.mapNotNull { it as? Map<String, Any?> }.orEmpty()

private fun File.writeJson(value: Any?) {
    parentFile.mkdirs()
    writeText(JsonOutput.prettyPrint(JsonOutput.toJson(value)) + "\n")
}

private fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
