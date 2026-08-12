import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal class CandidateManifestFixture(
    val root: File,
    private val version: String,
    private val commit: String,
) {
    val swiftZip = root.resolve("CodexAgent-$version.xcframework.zip").apply {
        ZipOutputStream(outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("CodexAgent.xcframework/file").apply {
                setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0))
            })
            zip.write("swift".encodeToByteArray())
            zip.closeEntry()
        }
    }
    val swiftChecksum = root.resolve("swift.sha256").apply { writeText(swiftZip.releaseDigest()) }
    val packageSwift = root.resolve("Package.swift").apply { writeText("package") }
    val swiftPmProof = root.resolve("swiftpm-proof.json").also {
        writeTestSwiftPackageProof(it, swiftZip, swiftChecksum, packageSwift, commit, version, root)
    }
    val centralBundle = root.resolve("codex-agent-$version-central.zip").apply { writeText("central") }
    private val desktopArchiveSha = "e".repeat(64)
    val desktop = desktopRuntimeEvidenceTargets.keys.map { target ->
        root.resolve(desktopRuntimeEvidenceFileName(target)).apply {
            atomicWriteJson(buildDesktopRuntimeEvidence(DesktopRuntimeEvidenceValues(
                commit, target, "f".repeat(64), desktopArchiveSha,
            )))
        }
    }
    val mavenInventory = root.resolve("maven-inventory.json").apply { atomicWriteJson(buildJsonObject {
        put("version", JsonPrimitive(version))
        put("primaryArtifactCount", JsonPrimitive(expectedMavenPrimaryPaths(version).size))
        put("files", buildJsonArray { desktopRuntimeEvidenceTargets.values.forEach { target ->
            add(buildJsonObject {
                put("path", JsonPrimitive(
                    "io/github/ciurlaro/codex-agent-runtime-desktop/$version/" +
                        "codex-agent-runtime-desktop-$version-${target.classifier}.zip",
                ))
                put("sha256", JsonPrimitive(desktopArchiveSha))
            })
        } })
    }) }
    val centralInventory = root.resolve("central-bundle.json").apply { atomicWriteJson(buildJsonObject {
        put("belowCentralPortalUploadLimit", JsonPrimitive(true))
        put("mavenInventorySha256", JsonPrimitive(mavenInventory.releaseDigest()))
        put("bundle", centralBundle.releaseRecord())
    }) }
    val consumer = root.resolve("kmp-consumer.json").apply { atomicWriteJson(buildJsonObject {
        put("result", JsonPrimitive("passed")); put("version", JsonPrimitive(version))
        put("mavenInventorySha256", JsonPrimitive(mavenInventory.releaseDigest()))
    }) }
    val android = root.resolve("android-evidence.json").apply {
        atomicWriteJson(buildAndroidRuntimeEvidence(AndroidRuntimeEvidenceValues(
            commit, "arm64-v8a", 35, "a".repeat(64), "b".repeat(64),
            ANDROID_TEST_APPLICATION_ID, ANDROID_TEST_APPLICATION_ID,
            "codex-agent-runtime-android-release.aar", "c".repeat(64), "d".repeat(64), "d".repeat(64),
        )))
    }
    val resources = root.resolve("resources.json").apply {
        atomicWriteJson(buildJsonObject { put("exitCode", JsonPrimitive(0)) })
    }
    val artifactMetrics = root.resolve("artifact-metrics.json").apply { atomicWriteJson(buildJsonObject {
        put("compressedXcframeworkBytes", JsonPrimitive(1)); put("deviceFrameworkBytes", JsonPrimitive(1))
        put("sampleAppInstallBytes", JsonPrimitive(1))
    }) }
    val desktopManifest = writeTestDesktopDistributionManifest(
        root.resolve("codex-app-server-distributions.json"), "f".repeat(64),
    )
    val desktopLicense = root.resolve("openai-codex-LICENSE.txt").apply { writeText("license") }
    val desktopNotice = root.resolve("openai-codex-NOTICE.txt").apply { writeText("notice") }
    val approvals = writeTestPublicationApprovals(
        root.resolve("publication-approvals.json"), desktopManifest, desktopLicense, desktopNotice,
    )
    val privacyManifest = root.resolve("PrivacyInfo.xcprivacy").apply { writeText("manifest") }
    val privacyReview = root.resolve("privacy-data-flow-review.json").apply { writeText("{}") }
    val requiredReasons = root.resolve("privacy-required-reason-reviews.json").apply { writeText("{}") }
    val privacyAudit = root.resolve("privacy-audit.json").apply { writePrivacyAudit(requiredReasons.releaseDigest()) }
    val manifest = root.resolve("candidate-manifest.json")
    val payload = root.resolve("payload").apply { mkdirs() }
    val inputs get() = CandidateInputFiles(
        version, "v$version", commit, swiftZip, swiftChecksum, swiftPmProof, centralBundle, centralInventory,
        mavenInventory, consumer, android, desktop, privacyAudit, artifactMetrics, listOf(resources), approvals,
        privacyManifest, privacyReview, requiredReasons.takeIf(File::isFile), packageSwift,
        desktopManifest, desktopLicense, desktopNotice,
    )
    val policyFiles get() = buildMap {
        put("approvals", approvals); put("privacyManifest", privacyManifest)
        put("privacyDataFlowReview", privacyReview)
        requiredReasons.takeIf(File::isFile)?.let { put("privacyRequiredReasonReviews", it) }
        put("packageSwift", packageSwift); put("desktopDistributionManifest", desktopManifest)
        put("desktopBundledLicense", desktopLicense); put("desktopBundledNotice", desktopNotice)
    }

    fun removeRequiredReasonReview() {
        requiredReasons.delete()
        privacyAudit.writePrivacyAudit(null)
    }

    fun copyPayloadFiles() {
        listOf(
            swiftZip, swiftPmProof, centralBundle, centralInventory, mavenInventory, consumer, android,
            *desktop.toTypedArray(), privacyAudit, artifactMetrics, resources,
        ).plus(policyFiles.values).forEach { it.copyTo(payload.resolve(it.name), overwrite = true) }
    }

    private fun File.writePrivacyAudit(reviewHash: String?) = atomicWriteJson(buildJsonObject {
        put("passed", JsonPrimitive(true)); reviewHash?.let { put("reviewSha256", JsonPrimitive(it)) }
    })
}
