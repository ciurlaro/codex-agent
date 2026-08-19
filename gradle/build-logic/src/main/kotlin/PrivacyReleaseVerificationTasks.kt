import java.io.File
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
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

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val desktopDistributionManifest: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val desktopBundledLicense: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val desktopBundledNotice: RegularFileProperty

    @TaskAction
    fun verify() = verifyPublicationReadiness(
        approvalsFile.get().asFile,
        privacyManifest.get().asFile,
        privacyInventory.get().asFile,
        desktopDistributionManifest.get().asFile,
        desktopBundledLicense.get().asFile,
        desktopBundledNotice.get().asFile,
    )
}

internal fun verifyPublicationReadiness(
    approvalsFile: File,
    manifest: File,
    inventoryFile: File,
    desktopManifest: File,
    desktopLicense: File,
    desktopNotice: File,
) {
    val approvals = approvalsFile.readReleaseObject()
    check(approvals.releaseInt("schemaVersion") == 2) { "Publication approval schema must be 2" }
    val inventory = inventoryFile.readReleaseObject()
    val reviewStatus = inventory.releaseString("reviewStatus")
    check(reviewStatus == "pending" || reviewStatus == "approved") {
        "Privacy inventory reviewStatus must be pending or approved"
    }
    val privacyApproved = approvals.releaseBoolean("privacyCollectedDataReviewApproved")
    if (privacyApproved) {
        check(approvals.releaseString("privacyManifestSha256") == manifest.releaseDigest()) {
            "Privacy approval manifest hash mismatch"
        }
        check(approvals.releaseString("privacyDataFlowReviewSha256") == inventoryFile.releaseDigest()) {
            "Privacy approval review hash mismatch"
        }
        check(reviewStatus == "approved") { "Approved privacy review requires reviewStatus=approved" }
        when (inventory.releaseStringOrNull("terminalCollectedDataDecision")) {
            "declare" -> {
                val dataTypes = inventory.releaseArray("appleCollectedDataTypes").map { it.jsonObject }
                check(dataTypes.isNotEmpty()) { "Approved declaration requires Apple data types" }
                dataTypes.forEach { type ->
                    check(type.releaseString("appleDataType").isNotBlank()) { "Apple data type is missing" }
                    check(type.releaseArray("purposes").map { it.jsonPrimitive.content }.isNotEmpty()) {
                        "Apple data type purposes are missing"
                    }
                }
            }
            "noSdkDeclaration" -> check(
                !inventory.releaseStringOrNull("reviewedNoSdkDeclarationRationale").isNullOrBlank(),
            ) { "No-SDK-declaration decision requires a reviewed rationale" }
            else -> error("Approved privacy review requires an explicit terminal collected-data decision")
        }
    }

    val desktopApproved = approvals.releaseBoolean("desktopBundledGplDistributionApproved")
    if (desktopApproved) verifyDesktopBundledGplApproval(approvalsFile, desktopManifest, desktopLicense, desktopNotice)
    val blockers = buildList {
        if (!privacyApproved) add("privacyCollectedDataReviewApproved=false")
        if (!approvals.releaseBoolean("staticFrameworkGplDistributionApproved")) {
            add("staticFrameworkGplDistributionApproved=false")
        }
        if (!desktopApproved) add("desktopBundledGplDistributionApproved=false")
    }
    check(blockers.isEmpty()) { "External approvals pending: ${blockers.joinToString()}" }
}

internal fun verifyDesktopBundledGplApproval(
    approvalsFile: File,
    manifest: File,
    license: File,
    notice: File,
) {
    val approvals = approvalsFile.readReleaseObject()
    check(approvals.releaseBoolean("desktopBundledGplDistributionApproved")) {
        "Desktop bundled GPL distribution is not approved"
    }
    listOf(
        "desktopDistributionManifestSha256" to manifest,
        "desktopBundledLicenseSha256" to license,
        "desktopBundledNoticeSha256" to notice,
    ).forEach { (field, file) ->
        check(approvals.releaseString(field) == file.releaseDigest()) { "Desktop GPL approval $field mismatch" }
    }
}
