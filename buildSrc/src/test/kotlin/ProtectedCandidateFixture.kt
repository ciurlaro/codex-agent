import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal fun withPayloadFixture(includeReview: Boolean = true, block: (ProtectedCandidatePayloadFixture) -> Unit) {
    val fixture = ProtectedCandidatePayloadFixture("a".repeat(40), "b".repeat(64), includeReview)
    try { block(fixture) } finally { fixture.close() }
}

internal class ProtectedCandidatePayloadFixture(
    private val commit: String,
    private val sha: String,
    includeReview: Boolean,
) : AutoCloseable {
    val root = createTempDirectory("candidate-payload").toFile()
    val swiftZip = root.resolve("CodexAgent-0.2.0.xcframework.zip").also(::writeZip)
    val swiftChecksum = root.resolve("swift.sha256").apply { writeText(swiftZip.releaseDigest()) }
    val centralBundle = root.resolve("central.zip").apply { writeText("central") }
    val desktop = writeDesktopEvidence(root, commit, sha)
    val maven = root.resolve("maven.json").apply { atomicWriteJson(buildJsonObject {
        put("version", JsonPrimitive("0.2.0"))
        put("primaryArtifactCount", JsonPrimitive(expectedMavenPrimaryPaths("0.2.0").size))
        put("files", buildJsonArray { desktopRuntimeEvidenceTargets.values.forEach { target ->
            add(buildJsonObject {
                put("path", JsonPrimitive(
                    "io/github/ciurlaro/codex-agent-runtime-desktop/0.2.0/" +
                        "codex-agent-runtime-desktop-0.2.0-${target.classifier}.zip",
                ))
                put("sha256", JsonPrimitive(sha))
            })
        } })
    }) }
    val central = root.resolve("central.json").apply { atomicWriteJson(buildJsonObject {
        put("belowCentralPortalUploadLimit", JsonPrimitive(true)); put("bundle", centralBundle.releaseRecord())
        put("mavenInventorySha256", JsonPrimitive(maven.releaseDigest()))
    }) }
    val consumer = root.resolve("consumer.json").apply { atomicWriteJson(buildJsonObject {
        put("result", JsonPrimitive("passed")); put("version", JsonPrimitive("0.2.0"))
        put("mavenInventorySha256", JsonPrimitive(maven.releaseDigest()))
    }) }
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
    val desktopManifest = writeTestDesktopDistributionManifest(root.resolve("desktop.json"), sha)
    val desktopLicense = policy("openai-codex-LICENSE.txt")
    val desktopNotice = policy("openai-codex-NOTICE.txt")
    val approvals = writeTestPublicationApprovals(
        root.resolve("approvals.json"), desktopManifest, desktopLicense, desktopNotice,
    )
    val swiftPmProof = root.resolve("swiftpm-proof.json").also {
        writeTestSwiftPackageProof(it, swiftZip, swiftChecksum, packageSwift, commit, "0.2.0", root)
    }
    val inputs = CandidateInputFiles(
        version = "0.2.0", releaseTag = "v0.2.0", commit = commit,
        swiftZip = swiftZip, swiftChecksum = swiftChecksum, swiftPmProof = swiftPmProof,
        centralBundle = centralBundle, centralInventory = central, mavenInventory = maven,
        kmpConsumer = consumer, desktopEvidence = desktop, privacyAudit = privacy,
        artifactMetrics = artifactMetrics, resourceReports = listOf(resources), approvals = approvals,
        privacyManifest = privacyManifest, privacyDataFlowReview = dataFlow,
        privacyRequiredReasonReviews = reviews, packageSwift = packageSwift,
        desktopDistributionManifest = desktopManifest, desktopBundledLicense = desktopLicense,
        desktopBundledNotice = desktopNotice,
    )
    val manifest = root.resolve("candidate-manifest.json").apply { atomicWriteJson(buildCandidateManifest(inputs)) }
    val sources = listOf(
        swiftZip, swiftPmProof, centralBundle, central, maven, consumer, *desktop.toTypedArray(),
        privacy, artifactMetrics, resources, approvals, privacyManifest, dataFlow, packageSwift,
        desktopManifest, desktopLicense, desktopNotice,
    ) + listOfNotNull(reviews)
    val payload = root.resolve("payload")
    val verification = root.resolve("reports/payload-verification.json")

    fun stage(files: Collection<File> = sources) = stageProtectedCandidatePayload(
        manifest, files, root, payload, verification, "0.2.0", "v0.2.0", commit,
    )

    override fun close() { root.deleteRecursively() }
}

internal class PreflightFixture(candidateCommit: String, hash: String) : AutoCloseable {
    val repository = createTempDirectory("candidate-repository").toFile()
    val external = createTempDirectory("candidate-inputs").toFile()
    val candidate = repository.resolve("build/protected-candidate/$candidateCommit")
    val desktop = writeDesktopEvidence(external, candidateCommit, hash)
    val input = ProtectedCandidatePreflight(
        "0.2.0", "v0.2.0", candidateCommit, candidateCommit, "", false,
        repository, candidate, desktop,
    )

    override fun close() { repository.deleteRecursively(); external.deleteRecursively() }
}

private fun writeZip(file: File) = ZipOutputStream(file.outputStream()).use {
    it.putNextEntry(ZipEntry("member")); it.write("contents".encodeToByteArray()); it.closeEntry()
}

private fun writeDesktopEvidence(directory: File, candidate: String, hash: String): List<File> =
    desktopRuntimeEvidenceTargets.keys.map { target ->
        directory.resolve(desktopRuntimeEvidenceFileName(target)).apply {
            atomicWriteJson(buildDesktopRuntimeEvidence(DesktopRuntimeEvidenceValues(candidate, target, hash, hash)))
        }
    }
