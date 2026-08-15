import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.serialization.json.JsonObject

internal val verifiableCentralStates = setOf("VALIDATED", "PUBLISHING", "PUBLISHED")

internal fun CentralDeployment.isRemoteBundleVerified(identity: CentralIdentity): Boolean =
    remoteBundleVerifiedSha256 == identity.bundleSha256

internal fun CentralDeployment.verifyRemoteBundle(
    bundle: File,
    identity: CentralIdentity,
    api: String,
    headers: Map<String, String>,
    sender: (CentralPortalRequest) -> CentralPortalResponse,
    sleeper: (Long) -> Unit = Thread::sleep,
): CentralDeployment {
    if (state !in verifiableCentralStates || isRemoteBundleVerified(identity)) return this
    check(remoteBundleVerifiedSha256 == null) { "Central remote bundle proof mismatch" }
    val files = ZipFile(bundle).use { archive ->
        // The Portal cannot enumerate remote files. Exact PURLs reject extra/missing components;
        // these downloads prove every file in the exact locally verified atomic upload bundle.
        centralBundleFiles(archive).sortedBy { it.name }.map { entry ->
            check(entry.size >= 0) { "Central bundle file size is unknown: ${entry.name}" }
            CentralExpectedFile(
                entry.name,
                entry.size,
                archive.getInputStream(entry).use(InputStream::releaseDigest),
            )
        }
    }
    parallelCentralMap(files) { expected ->
        val path = expected.name.split('/').joinToString("/") {
            URLEncoder.encode(it, UTF_8).replace("+", "%20")
        }
        val response = sender.checkedResponse(CentralPortalRequest(
            "GET",
            "$api/deployment/$id/download/$path",
            headers,
            responseByteLimit = expected.bytes,
            digestResponse = true,
        ), sleeper)
        val actualSha256 = response.contentSha256 ?: response.bytes.inputStream().use(InputStream::releaseDigest)
        check(response.contentLength == expected.bytes && actualSha256 == expected.sha256) {
            "Central deployment file mismatch: ${expected.name}"
        }
    }
    return copy(remoteBundleVerifiedSha256 = identity.bundleSha256)
}

private data class CentralExpectedFile(val name: String, val bytes: Long, val sha256: String)

internal fun <T, R> parallelCentralMap(values: List<T>, transform: (T) -> R): List<R> {
    if (values.isEmpty()) return emptyList()
    val executor = Executors.newFixedThreadPool(minOf(4, values.size))
    var interrupted: InterruptedException? = null
    fun await(future: Future<R>): Result<R> {
        while (true) {
            try {
                return Result.success(future.get())
            } catch (failure: InterruptedException) {
                if (interrupted == null) interrupted = failure
            } catch (failure: ExecutionException) {
                return Result.failure(failure.cause ?: failure)
            }
        }
    }
    val outcomes = try {
        val futures = values.map { value -> executor.submit<R> { transform(value) } }
        futures.map(::await)
    } finally {
        executor.shutdown()
        while (!executor.isTerminated) {
            try {
                executor.awaitTermination(1, TimeUnit.DAYS)
            } catch (failure: InterruptedException) {
                if (interrupted == null) interrupted = failure
            }
        }
    }
    interrupted?.let { Thread.currentThread().interrupt(); throw it }
    val failures = outcomes.mapNotNull { it.exceptionOrNull() }
    failures.firstOrNull()?.let { first ->
        failures.drop(1).filter { it !== first }.forEach(first::addSuppressed)
        throw first
    }
    return outcomes.map { it.getOrThrow() }
}

internal fun File.centralPurls(): Set<String> = ZipFile(this).use { archive ->
    val purls = centralBundleFiles(archive).filter { it.name.endsWith(".pom") }.map { entry ->
        val parts = entry.name.split('/')
        check(parts.size >= 4) { "Central bundle POM path is not a Maven GAV: ${entry.name}" }
        val artifact = parts[parts.lastIndex - 2]
        val version = parts[parts.lastIndex - 1]
        check(parts.last() == "$artifact-$version.pom") {
            "Central bundle POM path is not a base Maven GAV: ${entry.name}"
        }
        val group = parts.dropLast(3).joinToString(".")
        "pkg:maven/${purlPart(group)}/${purlPart(artifact)}@${purlPart(version)}"
    }.toSet()
    check(purls.isNotEmpty()) { "Central bundle contains no Maven GAVs" }
    purls
}

private fun centralBundleFiles(archive: ZipFile): List<java.util.zip.ZipEntry> {
    val names = mutableSetOf<String>()
    val files = mutableListOf<java.util.zip.ZipEntry>()
    val entries = archive.entries()
    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        val normalized = safeCentralZipPath(entry.name, entry.isDirectory)
        check(names.add(normalized)) { "Central bundle contains a duplicate ZIP entry: ${entry.name}" }
        if (!entry.isDirectory) files += entry
    }
    check(files.isNotEmpty()) { "Central bundle contains no files" }
    return files
}

private fun purlPart(value: String): String = URLEncoder.encode(value, UTF_8).replace("+", "%20")

private fun safeCentralZipPath(name: String, directory: Boolean): String {
    check(name.isNotEmpty() && !name.startsWith('/') && !name.startsWith('\\') && '\\' !in name) {
        "Central bundle contains an unsafe ZIP path: $name"
    }
    val normalized = if (directory) name.removeSuffix("/") else name
    check(normalized.isNotEmpty() && normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
        "Central bundle contains an unsafe ZIP path: $name"
    }
    return normalized
}

internal fun findCentralDeployment(
    identity: CentralIdentity,
    api: String,
    headers: Map<String, String>,
    sender: (CentralPortalRequest) -> CentralPortalResponse,
    sleeper: (Long) -> Unit = Thread::sleep,
): CentralDeployment? {
    val matches = mutableListOf<CentralDeployment>()
    var page = 0
    var pageCount: Int
    do {
        val name = URLEncoder.encode(identity.name, UTF_8)
        val result = releaseJson.parseToJsonElement(sender.checked(CentralPortalRequest(
            "GET", "$api/deployments?deploymentName=$name&page=$page&size=20&sortField=createTimestamp&sortDirection=desc", headers,
        ), sleeper)) as? JsonObject ?: error("Central deployment list is not a JSON object")
        check(result.releaseInt("page") == page) { "Central deployment list page mismatch" }
        pageCount = result.releaseInt("pageCount")
        check(pageCount in 0..100) { "Central deployment list page count is invalid" }
        result.releaseArray("deployments").forEach { value ->
            val item = value as? JsonObject ?: error("Central deployment list item is not an object")
            if (item.releaseString("deploymentName") == identity.name) matches += CentralDeployment(
                item.releaseString("deploymentId"), identity.name, item.releaseString("deploymentState"),
                identity.candidateSha256, identity.bundleSha256, null,
            )
        }
        page++
    } while (page < pageCount)
    check(matches.size <= 1) { "Central deployment recovery is ambiguous" }
    return matches.singleOrNull()?.also {
        check(runCatching { UUID.fromString(it.id) }.isSuccess) { "Central deployment list returned an invalid deployment ID" }
        check(it.state in verifiableCentralStates + setOf("PENDING", "VALIDATING", "FAILED")) {
            "Unsupported Central deployment state: ${it.state}"
        }
    }
}
