import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class PreparePinnedArchiveTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:Input
    abstract val expectedSha256: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localArchive: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun prepare() {
        val expected = expectedSha256.get()
        check(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid pinned archive SHA-256" }
        val output = outputFile.get().asFile
        if (output.isFile && output.sha256() == expected) return
        output.parentFile.mkdirs()
        val temporary = temporaryDir.resolve(output.name)
        if (localArchive.isPresent) {
            Files.copy(localArchive.get().asFile.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } else {
            val uri = URI(sourceUrl.get())
            check(uri.scheme == "https") { "Pinned archive URL must use HTTPS" }
            val request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build()
            val response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(60))
                .build()
                .send(request, HttpResponse.BodyHandlers.ofFile(temporary.toPath()))
            check(response.statusCode() in 200..299 && response.uri().scheme == "https") {
                "Pinned archive download failed"
            }
        }
        check(temporary.sha256() == expected) { "Pinned archive SHA-256 mismatch" }
        Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

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

@CacheableTask
abstract class VerifyMavenStagingTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    abstract val groupId: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val expectedArtifactIds: ListProperty<String>

    @get:Input
    abstract val rootMetadataArtifactIds: ListProperty<String>

    @get:Input
    abstract val requireSignatures: Property<Boolean>

    @get:OutputFile
    abstract val inventoryFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val repository = repositoryDirectory.get().asFile
        val groupRoot = repository.resolve(groupId.get().replace('.', '/'))
        val expected = expectedArtifactIds.get().toSortedSet()
        val actual = groupRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.resolve(version.get()).isDirectory }
            .map(File::getName)
            .toSortedSet()
        check(actual == expected) { "Maven publication set mismatch: expected=$expected actual=$actual" }

        expected.forEach { artifactId ->
            val directory = groupRoot.resolve("$artifactId/${version.get()}")
            val prefix = "$artifactId-${version.get()}"
            check(directory.resolve("$prefix.pom").isFile) { "$artifactId POM is missing" }
            if (artifactId in rootMetadataArtifactIds.get()) {
                check(directory.resolve("$prefix.module").isFile) { "$artifactId Gradle metadata is missing" }
            }
            val publishable = directory.listFiles().orEmpty().filter { file ->
                file.isFile && file.extension in setOf("pom", "module", "jar", "aar", "klib") &&
                    !file.name.contains("-sources") && !file.name.contains("-javadoc")
            }
            check(publishable.any { it.extension in setOf("jar", "aar", "klib") }) {
                "$artifactId target binary is missing"
            }
            publishable.forEach { file ->
                check(directory.resolve("${file.name}.md5").isFile) { "${file.name}.md5 is missing" }
                check(directory.resolve("${file.name}.sha1").isFile) { "${file.name}.sha1 is missing" }
                if (requireSignatures.get()) {
                    check(directory.resolve("${file.name}.asc").isFile) { "${file.name}.asc is missing" }
                }
            }
        }

        val files = repository.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(repository).path }
            .map { file ->
                linkedMapOf(
                    "path" to file.relativeTo(repository).invariantSeparatorsPath,
                    "bytes" to file.length(),
                    "sha256" to file.sha256(),
                )
            }.toList()
        inventoryFile.get().asFile.writeJson(
            linkedMapOf(
                "schemaVersion" to 1,
                "groupId" to groupId.get(),
                "version" to version.get(),
                "artifactIds" to expected,
                "signaturesRequired" to requireSignatures.get(),
                "files" to files,
            ),
        )
    }
}

@CacheableTask
abstract class GenerateBundleInventoryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundleFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mavenInventory: RegularFileProperty

    @get:Input
    abstract val maximumBytes: Property<Long>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val bundle = bundleFile.get().asFile
        check(bundle.length() <= maximumBytes.get()) {
            "Central bundle exceeds Portal limit: ${bundle.length()} > ${maximumBytes.get()}"
        }
        outputFile.get().asFile.writeJson(
            linkedMapOf(
                "schemaVersion" to 1,
                "bundleFile" to bundle.name,
                "bundleBytes" to bundle.length(),
                "portalMaximumBytes" to maximumBytes.get(),
                "bundleSha256" to bundle.sha256(),
                "mavenInventorySha256" to mavenInventory.get().asFile.sha256(),
            ),
        )
    }
}

@CacheableTask
abstract class GenerateCandidateManifestTask : DefaultTask() {
    @get:Input abstract val candidateVersion: Property<String>
    @get:Input abstract val candidateCommit: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftZip: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val swiftChecksum: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val centralBundle: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val centralInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val mavenInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val approvalsFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyManifest: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyInventory: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyAudit: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyReviews: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val androidEvidence: RegularFileProperty
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val evidenceFile = androidEvidence.get().asFile
        val evidenceErrors = validateAndroidEvidence(evidenceFile, candidateCommit.get())
        val files = linkedMapOf(
            "swiftZip" to swiftZip.get().asFile,
            "centralBundle" to centralBundle.get().asFile,
            "centralInventory" to centralInventory.get().asFile,
            "mavenInventory" to mavenInventory.get().asFile,
            "approvals" to approvalsFile.get().asFile,
            "privacyManifest" to privacyManifest.get().asFile,
            "privacyInventory" to privacyInventory.get().asFile,
            "privacyAudit" to privacyAudit.get().asFile,
            "privacyReviews" to privacyReviews.get().asFile,
            "androidRuntimeEvidence" to evidenceFile,
        ).mapValues { (_, file) -> mapOf("bytes" to file.length(), "sha256" to file.sha256()) }
        outputFile.get().asFile.writeJson(
            linkedMapOf(
                "schemaVersion" to 1,
                "version" to candidateVersion.get(),
                "candidateCommit" to candidateCommit.get(),
                "protectedCandidate" to evidenceErrors.isEmpty(),
                "swiftPmChecksum" to swiftChecksum.get().asFile.readText().trim(),
                "androidRuntimeEvidenceErrors" to evidenceErrors,
                "artifacts" to files,
            ),
        )
    }
}

@CacheableTask
abstract class VerifyAndroidRuntimeEvidenceTask : DefaultTask() {
    @get:Input abstract val expectedCommit: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val evidenceFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val errors = validateAndroidEvidence(evidenceFile.get().asFile, expectedCommit.get())
        check(errors.isEmpty()) { "Android real-runtime evidence is invalid: ${errors.joinToString()}" }
    }
}

@CacheableTask
abstract class VerifyCandidateManifestTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val manifestFile: RegularFileProperty

    @TaskAction
    fun verify() {
        check(manifestFile.get().asFile.jsonObject().boolean("protectedCandidate")) {
            "Candidate is not protected by exact Android real-runtime evidence"
        }
    }
}

internal fun validateAndroidEvidence(file: File, expectedCommit: String): List<String> {
    val evidence = file.jsonObject()
    return buildList {
        if (!expectedCommit.matches(Regex("[0-9a-f]{40}"))) add("candidate commit is not immutable")
        if (evidence.stringOrNull("commitSha") != expectedCommit) add("commit SHA mismatch")
        if (evidence.stringOrNull("testCommand").isNullOrBlank()) add("test command missing")
        if (evidence.stringOrNull("deviceArchitecture") !in setOf("arm64-v8a", "aarch64")) {
            add("device architecture is not ARM64")
        }
        if ((evidence["deviceApi"] as? Number)?.toInt()?.let { it >= 26 } != true) add("device API missing")
        if (evidence.stringOrNull("result") != "passed") add("result is not passed")
        listOf("testApkSha256", "targetApkSha256", "runtimeSha256").forEach { key ->
            if (!evidence.stringOrNull(key).orEmpty().matches(Regex("[0-9a-f]{64}"))) add("$key missing")
        }
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
