import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
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
): CentralDeployment {
    if (state !in verifiableCentralStates || isRemoteBundleVerified(identity)) return this
    check(remoteBundleVerifiedSha256 == null) { "Central remote bundle proof mismatch" }
    ZipFile(bundle).use { archive ->
        val files = centralBundleFiles(archive)
        // The Portal cannot enumerate remote files. Exact PURLs reject extra/missing components;
        // these downloads prove every file in the exact locally verified atomic upload bundle.
        files.sortedBy { it.name }.forEach { entry ->
            val expected = archive.getInputStream(entry).use { it.readBytes() }
            val path = entry.name.split('/').joinToString("/") {
                URLEncoder.encode(it, UTF_8).replace("+", "%20")
            }
            val actual = sender.checkedBytes(CentralPortalRequest(
                "GET", "$api/deployment/$id/download/$path", headers,
            ))
            check(actual.contentEquals(expected)) { "Central deployment file mismatch: ${entry.name}" }
        }
    }
    return copy(remoteBundleVerifiedSha256 = identity.bundleSha256)
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
): CentralDeployment? {
    val matches = mutableListOf<CentralDeployment>()
    var page = 0
    var pageCount: Int
    do {
        val name = URLEncoder.encode(identity.name, UTF_8)
        val result = releaseJson.parseToJsonElement(sender.checked(CentralPortalRequest(
            "GET", "$api/deployments?deploymentName=$name&page=$page&size=20&sortField=createTimestamp&sortDirection=desc", headers,
        ))) as? JsonObject ?: error("Central deployment list is not a JSON object")
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
