import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal fun writeTestDesktopDistributionManifest(file: File, binarySha256: String): File = file.apply {
    atomicWriteJson(buildJsonObject {
        put("version", JsonPrimitive("0.145.0"))
        put("releaseTag", JsonPrimitive("rust-v0.145.0"))
        put("distributions", buildJsonArray {
            desktopRuntimeEvidenceTargets.forEach { (target, evidence) ->
                add(buildJsonObject {
                    put("target", JsonPrimitive(target))
                    put("classifier", JsonPrimitive(evidence.classifier))
                    put("asset", JsonPrimitive("$target.tar.gz"))
                    put("archiveSha256", JsonPrimitive("a".repeat(64)))
                    put("archiveEntry", JsonPrimitive("codex-app-server"))
                    put("binarySha256", JsonPrimitive(binarySha256))
                    put("executableName", JsonPrimitive(if (target == "mingwX64") "codex-app-server.exe" else "codex-app-server"))
                })
            }
        })
    })
}

internal fun writeTestPublicationApprovals(
    file: File,
    desktopManifest: File,
    desktopLicense: File,
    desktopNotice: File,
    privacyApproved: Boolean = false,
    staticIosGplApproved: Boolean = false,
    desktopGplApproved: Boolean = true,
    privacyManifest: File? = null,
    privacyInventory: File? = null,
): File = file.apply {
    atomicWriteJson(buildJsonObject {
        put("schemaVersion", JsonPrimitive(2))
        put("privacyCollectedDataReviewApproved", JsonPrimitive(privacyApproved))
        put("staticFrameworkGplDistributionApproved", JsonPrimitive(staticIosGplApproved))
        put("privacyManifestSha256", privacyManifest?.let { JsonPrimitive(it.releaseDigest()) } ?: JsonPrimitive(null as String?))
        put("privacyDataFlowReviewSha256", privacyInventory?.let { JsonPrimitive(it.releaseDigest()) } ?: JsonPrimitive(null as String?))
        put("desktopBundledGplDistributionApproved", JsonPrimitive(desktopGplApproved))
        put("desktopDistributionManifestSha256", JsonPrimitive(desktopManifest.releaseDigest()))
        put("desktopBundledLicenseSha256", JsonPrimitive(desktopLicense.releaseDigest()))
        put("desktopBundledNoticeSha256", JsonPrimitive(desktopNotice.releaseDigest()))
    })
}
