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
    private val runtimes = CandidateRuntimeReleaseFixture(root.resolve("runtime-release"), version, commit)
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
    val centralBundle = runtimes.writeCentralBundle(root.resolve("codex-agent-$version-central.zip"))
    val desktop = runtimes.desktopEvidence
    val jvmEvidence = runtimes.jvmEvidence
    val nodeEvidence = runtimes.nodeEvidence
    val nodeWasmEvidence = runtimes.nodeWasmEvidence
    val androidEvidence = runtimes.androidEvidence
    val iosNative = root.resolve("ios-native-evidence.json").apply {
        writeTestCandidateIosNativeEvidence(this, commit)
    }
    val mavenInventory = root.resolve("maven-inventory.json").apply { atomicWriteJson(buildJsonObject {
        put("version", JsonPrimitive(version))
        put("primaryArtifactCount", JsonPrimitive(expectedMavenPrimaryPaths(version).size))
        put("files", buildJsonArray { runtimes.mavenRecords().forEach(::add) })
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
    val ciProvenance = writeTestCandidateCiProvenance(root.resolve(CANDIDATE_CI_PROVENANCE_FILE), commit)
    val runtimeMetrics = writeTestIosRuntimeMetrics(root.resolve("runtime-metrics.json"))
    val artifactMetrics = root.resolve("artifact-metrics.json").apply { atomicWriteJson(buildJsonObject {
        put("compressedXcframeworkBytes", JsonPrimitive(1)); put("deviceFrameworkBytes", JsonPrimitive(1))
        put("sampleAppInstallBytes", JsonPrimitive(1))
    }) }
    val desktopManifest = runtimes.distributionManifest
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
        version, "v$version", commit, swiftZip, swiftChecksum, swiftPmProof, centralBundle,
        centralInventory, mavenInventory, consumer, ciProvenance, desktop, runtimes.classifiers.values.toList(),
        jvmEvidence, runtimes.jvmRunner, nodeEvidence, runtimes.nodeRunner,
        nodeWasmEvidence, runtimes.nodeWasmRunner, androidEvidence, iosNative, privacyAudit,
        artifactMetrics, runtimeMetrics, approvals, privacyManifest, privacyReview,
        requiredReasons.takeIf(File::isFile), packageSwift, desktopManifest, desktopLicense, desktopNotice,
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

    fun replaceCentralAndroidRuntime(bytes: ByteArray) {
        runtimes.writeCentralBundle(centralBundle, bytes)
    }

    fun copyPayloadFiles() {
        listOf(
            swiftZip, swiftPmProof, centralBundle, centralInventory, mavenInventory, consumer, ciProvenance,
            *desktop.toTypedArray(), *jvmEvidence.toTypedArray(), runtimes.jvmRunner,
            *nodeEvidence.toTypedArray(), runtimes.nodeRunner,
            *nodeWasmEvidence.toTypedArray(), runtimes.nodeWasmRunner,
            *androidEvidence.toTypedArray(), iosNative, privacyAudit, artifactMetrics, runtimeMetrics,
        ).plus(policyFiles.values).forEach { it.copyTo(payload.resolve(it.name), overwrite = true) }
    }

    private fun File.writePrivacyAudit(reviewHash: String?) = atomicWriteJson(buildJsonObject {
        put("passed", JsonPrimitive(true)); reviewHash?.let { put("reviewSha256", JsonPrimitive(it)) }
    })
}

internal fun writeTestIosRuntimeMetrics(file: File, startup: Long = 10): File = file.apply {
    atomicWriteJson(buildJsonObject {
        put("warmupCycles", JsonPrimitive(1)); put("measuredCycles", JsonPrimitive(5))
        put("coldStartupMilliseconds", JsonPrimitive(10))
        put("startupMilliseconds", buildJsonArray { repeat(5) { add(JsonPrimitive(startup)) } })
        put("startupMedianMilliseconds", JsonPrimitive(startup))
        put("startupMaximumMilliseconds", JsonPrimitive(startup))
        put("shutdownMilliseconds", buildJsonArray { repeat(5) { add(JsonPrimitive(10)) } })
        put("shutdownMedianMilliseconds", JsonPrimitive(10))
        put("shutdownMaximumMilliseconds", JsonPrimitive(10))
        put("memoryMeasurement", JsonPrimitive("mach_task_basic_info.current_resident_size"))
        put("idleCurrentResidentBytes", JsonPrimitive(1))
        put("recursiveSearchCurrentResidentBytes", JsonPrimitive(2))
        put("authenticatedTurnPeakResidentBytes", kotlinx.serialization.json.JsonNull)
    })
}

internal fun writeTestCandidateCiProvenance(file: File, commit: String): File = file.apply {
    atomicWriteJson(buildJsonObject {
        put("schemaVersion", JsonPrimitive(1)); put("repository", JsonPrimitive("ciurlaro/codex-agent"))
        put("workflowPath", JsonPrimitive(".github/workflows/ci.yml")); put("runId", JsonPrimitive(123L))
        put("runAttempt", JsonPrimitive(2)); put("event", JsonPrimitive("push"))
        put("headBranch", JsonPrimitive("main")); put("headSha", JsonPrimitive(commit))
        put("conclusion", JsonPrimitive("success"))
    })
}

internal fun writeTestCandidateIosNativeEvidence(file: File, commit: String) {
    file.atomicWriteJson(buildJsonObject {
        put("schemaVersion", JsonPrimitive(2)); put("protocol", JsonPrimitive("codex-agent-ios-native-evidence-v2"))
        put("result", JsonPrimitive("passed")); put("candidateCommit", JsonPrimitive(commit))
        put("candidateTree", JsonPrimitive("d".repeat(40))); put("cleanCheckout", JsonPrimitive(true))
        listOf(
            "nativeInputsSha256", "nativeProvenanceSha256", "compilerSettingsSha256",
            "rustCompilerIdentitySha256", "xcodeVersionSha256", "swiftVersionSha256", "nativeTestsProofSha256",
        ).forEach { put(it, JsonPrimitive("c".repeat(64))) }
        put("rustToolchain", JsonPrimitive("1.95.0")); put("rustSrcComponent", JsonPrimitive("required"))
        put("slices", buildJsonArray { appleRustSliceSpecs.forEach { spec -> add(buildJsonObject {
            put("target", JsonPrimitive(spec.target)); put("archive", buildJsonObject {
                put("fileName", JsonPrimitive(spec.archiveName)); put("bytes", JsonPrimitive(9))
                put("sha256", JsonPrimitive("e".repeat(64)))
            }); put("proofSha256", JsonPrimitive("f".repeat(64)))
            put("appleToolchainIdentitySha256", JsonPrimitive("a".repeat(64)))
        }) } })
    })
}
