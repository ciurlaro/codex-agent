import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

internal class CandidateManifestFixture(
    val root: File,
    private val version: String,
    private val commit: String,
) {
    private val nodeRelease = CandidateNodeReleaseFixture(root.resolve("node-release"), version, commit)
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
    val desktop = nodeRelease.desktopEvidence
    val nodeEvidence = nodeRelease.nodeEvidence
    val iosNative = root.resolve("ios-native-evidence.json").apply {
        writeTestCandidateIosNativeEvidence(this, commit)
    }
    val mavenInventory = root.resolve("maven-inventory.json").apply { atomicWriteJson(buildJsonObject {
        put("version", JsonPrimitive(version))
        put("primaryArtifactCount", JsonPrimitive(expectedMavenPrimaryPaths(version).size))
        put("files", buildJsonArray { nodeRelease.mavenRecords().forEach(::add) })
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
    val resources = root.resolve("resources.json").apply {
        atomicWriteJson(buildJsonObject { put("exitCode", JsonPrimitive(0)) })
    }
    val artifactMetrics = root.resolve("artifact-metrics.json").apply { atomicWriteJson(buildJsonObject {
        put("compressedXcframeworkBytes", JsonPrimitive(1)); put("deviceFrameworkBytes", JsonPrimitive(1))
        put("sampleAppInstallBytes", JsonPrimitive(1))
    }) }
    val desktopManifest = nodeRelease.distributionManifest
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
        version = version, releaseTag = "v$version", commit = commit, swiftZip = swiftZip,
        swiftChecksum = swiftChecksum, swiftPmProof = swiftPmProof, centralBundle = centralBundle,
        centralInventory = centralInventory, mavenInventory = mavenInventory, kmpConsumer = consumer,
        desktopEvidence = desktop, nodeEvidence = nodeEvidence,
        nodeClassifierArchives = nodeRelease.classifiers.values.toList(),
        nodeRuntimeRunner = nodeRelease.runner, windowsSupervisorPackage = nodeRelease.supervisorPackage,
        windowsSupervisorIdentity = nodeRelease.supervisorIdentity,
        windowsSupervisorExecutable = nodeRelease.supervisorExecutable,
        windowsSupervisorSource = nodeRelease.supervisorSource, iosNativeEvidence = iosNative,
        privacyAudit = privacyAudit, artifactMetrics = artifactMetrics, resourceReports = listOf(resources),
        approvals = approvals, privacyManifest = privacyManifest, privacyDataFlowReview = privacyReview,
        privacyRequiredReasonReviews = requiredReasons.takeIf(File::isFile), packageSwift = packageSwift,
        desktopDistributionManifest = desktopManifest, desktopBundledLicense = desktopLicense,
        desktopBundledNotice = desktopNotice,
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
            swiftZip, swiftPmProof, centralBundle, centralInventory, mavenInventory, consumer,
            *desktop.toTypedArray(), *nodeEvidence.toTypedArray(), nodeRelease.runner,
            nodeRelease.supervisorIdentity,
            iosNative, privacyAudit, artifactMetrics, resources,
        ).plus(policyFiles.values).forEach { it.copyTo(payload.resolve(it.name), overwrite = true) }
    }

    private fun File.writePrivacyAudit(reviewHash: String?) = atomicWriteJson(buildJsonObject {
        put("passed", JsonPrimitive(true)); reviewHash?.let { put("reviewSha256", JsonPrimitive(it)) }
    })
}

internal class CandidateNodeReleaseFixture(
    root: File,
    private val version: String,
    private val commit: String,
) {
    private val appServer = "official app server".encodeToByteArray()
    private val appServerSha = appServer.inputStream().releaseDigest()
    val distributionManifest = writeTestDesktopDistributionManifest(
        root.resolve("codex-app-server-distributions.json"), appServerSha,
    )
    val classifiers = desktopRuntimeEvidenceTargets.mapValues { (target, spec) ->
        root.resolve("codex-agent-runtime-desktop-$version-${spec.classifier}.zip").apply {
            parentFile.mkdirs()
            nodeEvidenceWriteZip(linkedMapOf(
                (if (target == "mingwX64") "codex-app-server.exe" else "codex-app-server") to appServer,
                "openai-codex-LICENSE.txt" to "license".encodeToByteArray(),
                "openai-codex-NOTICE.txt" to "notice".encodeToByteArray(),
            ))
        }
    }
    val desktopEvidence = desktopRuntimeEvidenceTargets.keys.map { target ->
        root.resolve(desktopRuntimeEvidenceFileName(target)).apply {
            atomicWriteJson(buildDesktopRuntimeEvidence(DesktopRuntimeEvidenceValues(
                commit, target, appServerSha, classifiers.getValue(target).releaseDigest(),
            )))
        }
    }
    val runner = root.resolve(NODE_RUNTIME_RUNNER_ARCHIVE).apply {
        nodeEvidenceWriteZip(mapOf(
            NODE_RUNTIME_RUNNER_ENTRY to "compiled Node runtime evidence runner".encodeToByteArray(),
        ))
    }
    val supervisorSource = root.resolve("windows-supervisor-source").apply {
        mkdirs()
        resolve("CMakeLists.txt").writeText("project(codex_agent_node_windows_supervisor C)\n")
        resolve("supervisor.c").writeText("int main(void) { return 0; }\n")
    }
    val supervisorExecutable = root.resolve(WINDOWS_SUPERVISOR_FILE_NAME).apply {
        writeText("verified Windows supervisor")
    }
    val supervisorIdentity = root.resolve(WINDOWS_SUPERVISOR_IDENTITY_FILE_NAME).apply {
        writeWindowsSupervisorIdentity(this, WindowsSupervisorIdentity(
            fileName = WINDOWS_SUPERVISOR_FILE_NAME,
            sha256 = supervisorExecutable.windowsSupervisorSha256(),
            bytes = supervisorExecutable.length(),
            sourceSha256 = windowsSupervisorSourceSha256(supervisorSource),
            compiler = WindowsSupervisorCompiler("MSVC", "19.40.33811.0", "3.30.5"),
        ))
    }
    val supervisorPackage = root.resolve("codex-agent-runtime-node-$version-windows-supervisor.zip").apply {
        writeWindowsSupervisorPackage(this, supervisorExecutable, supervisorIdentity)
    }
    val nodeEvidence = desktopRuntimeEvidenceTargets.keys.map { target ->
        root.resolve(nodeRuntimeEvidenceFileName(target)).apply {
            val proof = inspectNodeClassifier(
                target, readDesktopCodexManifest(distributionManifest), classifiers.getValue(target),
            )
            atomicWriteJson(buildNodeRuntimeEvidence(NodeRuntimeEvidenceValues(
                commit, target, proof, runner,
                supervisorExecutable.windowsSupervisorSha256().takeIf { target == "mingwX64" },
            )))
        }
    }

    fun mavenRecords(): List<JsonObject> = buildList {
        desktopRuntimeEvidenceTargets.forEach { (target, spec) ->
            val archive = classifiers.getValue(target)
            add(buildJsonObject {
                put("path", JsonPrimitive(
                    "io/github/ciurlaro/codex-agent-runtime-desktop/$version/" +
                        "codex-agent-runtime-desktop-$version-${spec.classifier}.zip",
                ))
                put("bytes", JsonPrimitive(archive.length()))
                put("sha256", JsonPrimitive(archive.releaseDigest()))
            })
        }
        add(buildJsonObject {
            put("path", JsonPrimitive(
                "io/github/ciurlaro/codex-agent-runtime-node/$version/" +
                    "codex-agent-runtime-node-$version-windows-supervisor-x64.zip",
            ))
            put("bytes", JsonPrimitive(supervisorPackage.length()))
            put("sha256", JsonPrimitive(supervisorPackage.releaseDigest()))
        })
    }
}

internal fun writeTestCandidateIosNativeEvidence(file: File, commit: String) {
    file.atomicWriteJson(buildJsonObject {
        put("schemaVersion", JsonPrimitive(1)); put("protocol", JsonPrimitive("codex-agent-ios-native-evidence-v1"))
        put("result", JsonPrimitive("passed")); put("candidateCommit", JsonPrimitive(commit))
        put("candidateTree", JsonPrimitive("d".repeat(40))); put("cleanCheckout", JsonPrimitive(true))
        listOf(
            "nativeInputsSha256", "nativeProvenanceSha256", "compilerSettingsSha256", "xcodeVersionSha256",
            "swiftVersionSha256", "nativeTestsProofSha256",
        ).forEach { put(it, JsonPrimitive("c".repeat(64))) }
        put("rustToolchain", JsonPrimitive("1.95.0")); put("rustSrcComponent", JsonPrimitive("required"))
        put("slices", buildJsonArray { appleRustSliceSpecs.forEach { spec -> add(buildJsonObject {
            put("target", JsonPrimitive(spec.target)); put("archive", buildJsonObject {
                put("fileName", JsonPrimitive(spec.archiveName)); put("bytes", JsonPrimitive(9))
                put("sha256", JsonPrimitive("e".repeat(64)))
            }); put("proofSha256", JsonPrimitive("f".repeat(64)))
        }) } })
    })
}
