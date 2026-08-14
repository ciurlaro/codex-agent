import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal fun withPayloadFixture(includeReview: Boolean = true, block: (ProtectedCandidatePayloadFixture) -> Unit) {
    val fixture = ProtectedCandidatePayloadFixture("a".repeat(40), includeReview)
    try { block(fixture) } finally { fixture.close() }
}

internal class ProtectedCandidatePayloadFixture(
    private val commit: String,
    includeReview: Boolean,
) : AutoCloseable {
    val root = createTempDirectory("candidate-payload").toFile()
    private val runtimes = CandidateRuntimeReleaseFixture(root.resolve("runtime-release"), "0.2.0", commit)
    val swiftZip = root.resolve("CodexAgent-0.2.0.xcframework.zip").also(::writeZip)
    val swiftChecksum = root.resolve("swift.sha256").apply { writeText(swiftZip.releaseDigest()) }
    val centralBundle = runtimes.writeCentralBundle(root.resolve("central.zip"))
    val desktop = runtimes.desktopEvidence
    val iosNative = root.resolve("ios-native-evidence.json").also { writeTestCandidateIosNativeEvidence(it, commit) }
    val maven = root.resolve("maven.json").apply { atomicWriteJson(buildJsonObject {
        put("version", JsonPrimitive("0.2.0"))
        put("primaryArtifactCount", JsonPrimitive(expectedMavenPrimaryPaths("0.2.0").size))
        put("files", buildJsonArray { runtimes.mavenRecords().forEach(::add) })
    }) }
    val central = root.resolve("central.json").apply { atomicWriteJson(buildJsonObject {
        put("belowCentralPortalUploadLimit", JsonPrimitive(true)); put("bundle", centralBundle.releaseRecord())
        put("mavenInventorySha256", JsonPrimitive(maven.releaseDigest()))
    }) }
    val consumer = root.resolve("consumer.json").apply { atomicWriteJson(buildJsonObject {
        put("result", JsonPrimitive("passed")); put("version", JsonPrimitive("0.2.0"))
        put("mavenInventorySha256", JsonPrimitive(maven.releaseDigest()))
    }) }
    val ciProvenance = writeTestCandidateCiProvenance(root.resolve(CANDIDATE_CI_PROVENANCE_FILE), commit)
    val reviews = root.resolve("reviews.json").takeIf { includeReview }?.apply { writeText("reviews.json") }
    val privacy = root.resolve("privacy.json").apply { atomicWriteJson(buildJsonObject {
        put("passed", JsonPrimitive(true)); reviews?.let { put("reviewSha256", JsonPrimitive(it.releaseDigest())) }
    }) }
    val resources = root.resolve("resources.json").apply {
        atomicWriteJson(buildJsonObject { put("exitCode", JsonPrimitive(0)) })
    }
    val artifactMetrics = root.resolve("artifact-metrics.json").apply { atomicWriteJson(buildJsonObject {
        put("compressedXcframeworkBytes", JsonPrimitive(1)); put("deviceFrameworkBytes", JsonPrimitive(1))
        put("sampleAppInstallBytes", JsonPrimitive(1))
    }) }
    private fun policy(name: String) = root.resolve(name).apply { writeText(name) }
    val privacyManifest = policy("PrivacyInfo.xcprivacy")
    val dataFlow = policy("data-flow.json")
    val packageSwift = policy("Package.swift")
    val desktopManifest = runtimes.distributionManifest
    val desktopLicense = policy("openai-codex-LICENSE.txt")
    val desktopNotice = policy("openai-codex-NOTICE.txt")
    val approvals = writeTestPublicationApprovals(
        root.resolve("approvals.json"), desktopManifest, desktopLicense, desktopNotice,
    )
    val swiftPmProof = root.resolve("swiftpm-proof.json").also {
        writeTestSwiftPackageProof(it, swiftZip, swiftChecksum, packageSwift, commit, "0.2.0", root)
    }
    val inputs = CandidateInputFiles(
        "0.2.0", "v0.2.0", commit, swiftZip, swiftChecksum, swiftPmProof, centralBundle,
        central, maven, consumer, ciProvenance, desktop, runtimes.classifiers.values.toList(),
        runtimes.jvmEvidence, runtimes.jvmRunner, runtimes.nodeEvidence, runtimes.nodeRunner,
        runtimes.nodeWasmEvidence, runtimes.nodeWasmRunner, runtimes.androidEvidence,
        iosNative, privacy, artifactMetrics, listOf(resources), approvals, privacyManifest, dataFlow,
        reviews, packageSwift, desktopManifest, desktopLicense, desktopNotice,
    )
    val manifest = root.resolve("candidate-manifest.json").apply { atomicWriteJson(buildCandidateManifest(inputs)) }
    val sources = listOf(
        swiftZip, swiftPmProof, centralBundle, central, maven, consumer, ciProvenance, *desktop.toTypedArray(),
        *runtimes.jvmEvidence.toTypedArray(), runtimes.jvmRunner,
        *runtimes.nodeEvidence.toTypedArray(), runtimes.nodeRunner,
        *runtimes.nodeWasmEvidence.toTypedArray(), runtimes.nodeWasmRunner,
        *runtimes.androidEvidence.toTypedArray(), iosNative, privacy, artifactMetrics, resources,
        approvals, privacyManifest, dataFlow, packageSwift, desktopManifest, desktopLicense, desktopNotice,
    ) + listOfNotNull(reviews)
    val payload = root.resolve("payload")
    val verification = root.resolve("reports/payload-verification.json")

    fun stage(files: Collection<File> = sources) = stageProtectedCandidatePayload(
        manifest, files, root, payload, verification, "0.2.0", "v0.2.0", commit,
    )

    override fun close() { root.deleteRecursively() }
}

internal class PreflightFixture(candidateCommit: String, @Suppress("UNUSED_PARAMETER") hash: String) : AutoCloseable {
    val repository = createTempDirectory("candidate-repository").toFile()
    val external = createTempDirectory("candidate-inputs").toFile()
    val candidate = repository.resolve("build/protected-candidate/$candidateCommit")
    private val runtimes = CandidateRuntimeReleaseFixture(external.resolve("runtime-release"), "0.2.0", candidateCommit)
    val desktop = runtimes.desktopEvidence
    val iosNative = external.resolve("ios-native").apply { mkdirs() }
    val input = ProtectedCandidatePreflight(
        version = "0.2.0", releaseTag = "v0.2.0", commit = candidateCommit, head = candidateCommit,
        trackedStatus = "", parallel = false, repository = repository, candidateDirectory = candidate,
        desktopEvidence = desktop, jvmEvidence = runtimes.jvmEvidence, jvmRuntimeRunner = runtimes.jvmRunner,
        nodeEvidence = runtimes.nodeEvidence, nodeRuntimeRunner = runtimes.nodeRunner,
        nodeWasmEvidence = runtimes.nodeWasmEvidence, nodeWasmRuntimeRunner = runtimes.nodeWasmRunner,
        androidEvidence = runtimes.androidEvidence.filterNot {
            it.name == FIREBASE_ANDROID_VERIFICATION_RECEIPT_FILE
        },
        iosNativeEvidenceDirectory = iosNative,
    )

    override fun close() { repository.deleteRecursively(); external.deleteRecursively() }
}

private fun writeZip(file: File) = ZipOutputStream(file.outputStream()).use {
    it.putNextEntry(ZipEntry("member")); it.write("contents".encodeToByteArray()); it.closeEntry()
}
