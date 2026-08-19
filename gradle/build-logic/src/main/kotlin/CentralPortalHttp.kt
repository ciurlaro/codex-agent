import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.security.MessageDigest

private const val CENTRAL_RESPONSE_LIMIT_BYTES = 2L * 1024 * 1024
private const val CENTRAL_RETRY_ATTEMPTS = 4
private const val CENTRAL_RETRY_FALLBACK_MILLIS = 1_000L
private const val CENTRAL_RETRY_MAX_MILLIS = 300_000L

internal data class CentralPortalRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray = byteArrayOf(),
    val bodyFile: File? = null,
    val suffix: ByteArray = byteArrayOf(),
    val responseByteLimit: Long = CENTRAL_RESPONSE_LIMIT_BYTES,
    val digestResponse: Boolean = false,
)

internal data class CentralPortalResponse(
    val statusCode: Int,
    val bytes: ByteArray,
    val headers: Map<String, List<String>> = emptyMap(),
    val contentLength: Long = bytes.size.toLong(),
    val contentSha256: String? = null,
) {
    constructor(
        statusCode: Int,
        body: String,
        headers: Map<String, List<String>> = emptyMap(),
    ) : this(statusCode, body.toByteArray(UTF_8), headers)
    val body: String get() = bytes.toString(UTF_8)
}

internal class JdkCentralPortalSender {
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(60))
        .build()

    fun send(request: CentralPortalRequest): CentralPortalResponse {
        val builder = HttpRequest.newBuilder(URI.create(request.url)).timeout(Duration.ofMinutes(5))
        request.headers.forEach(builder::header)
        val publisher = request.bodyFile?.let { file ->
            HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofByteArray(request.body),
                HttpRequest.BodyPublishers.ofFile(file.toPath()),
                HttpRequest.BodyPublishers.ofByteArray(request.suffix),
            )
        } ?: HttpRequest.BodyPublishers.ofByteArray(request.body)
        val response = client.send(
            builder.method(request.method, publisher).build(),
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        val headers = response.headers().map()
        return response.body().use { body ->
            if (request.digestResponse && response.statusCode() in 200..299) {
                val (length, sha256) = body.centralDigest(request.responseByteLimit)
                CentralPortalResponse(response.statusCode(), byteArrayOf(), headers, length, sha256)
            } else {
                CentralPortalResponse(
                    response.statusCode(),
                    readCentralResponseBytes(body, request.effectiveResponseByteLimit(response.statusCode())),
                    headers,
                )
            }
        }
    }
}

internal fun centralAuthorization(username: String, password: String): String =
    "Bearer " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(UTF_8))

internal fun centralMultipartUpload(
    bundle: File,
    headers: Map<String, String>,
    api: String,
    deploymentName: String,
): CentralPortalRequest {
    val boundary = "CodexAgentCentralPortalBoundary"
    val prefix = (
        "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"bundle\"; filename=\"${bundle.name}\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n"
    ).toByteArray(UTF_8)
    return CentralPortalRequest(
        method = "POST",
        url = "$api/upload?publishingType=USER_MANAGED&name=${URLEncoder.encode(deploymentName, UTF_8)}",
        headers = headers + ("Content-Type" to "multipart/form-data; boundary=$boundary"),
        body = prefix,
        bodyFile = bundle,
        suffix = "\r\n--$boundary--\r\n".toByteArray(UTF_8),
    )
}

internal fun ((CentralPortalRequest) -> CentralPortalResponse).checked(
    request: CentralPortalRequest,
    sleeper: (Long) -> Unit = Thread::sleep,
): String = checkedResponse(request, sleeper).body

internal fun ((CentralPortalRequest) -> CentralPortalResponse).checkedResponse(
    request: CentralPortalRequest,
    sleeper: (Long) -> Unit = Thread::sleep,
): CentralPortalResponse {
    repeat(CENTRAL_RETRY_ATTEMPTS) { attempt ->
        val response = invoke(request)
        if (response.statusCode == 429 && attempt + 1 < CENTRAL_RETRY_ATTEMPTS) {
            sleeper(response.centralRetryAfterMillis())
        } else {
            check(response.statusCode in 200..299) {
                "Central Portal HTTP ${response.statusCode}: ${response.body}"
            }
            return response
        }
    }
    error("Central Portal retry attempts exhausted")
}

internal fun readCentralResponseBytes(input: InputStream, maximumBytes: Long): ByteArray {
    check(maximumBytes >= 0) { "Central response byte limit is invalid" }
    val output = ByteArrayOutputStream(minOf(maximumBytes, 8_192).toInt())
    val buffer = ByteArray(8_192)
    var total = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        check(total <= maximumBytes) { "Central Portal response exceeds $maximumBytes bytes" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

internal fun CentralPortalRequest.effectiveResponseByteLimit(statusCode: Int): Long =
    if (digestResponse && statusCode !in 200..299) CENTRAL_RESPONSE_LIMIT_BYTES else responseByteLimit

private fun InputStream.centralDigest(maximumBytes: Long): Pair<Long, String> {
    check(maximumBytes >= 0) { "Central response byte limit is invalid" }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        check(total <= maximumBytes) { "Central Portal response exceeds $maximumBytes bytes" }
        digest.update(buffer, 0, read)
    }
    return total to digest.digest().joinToString("") { "%02x".format(it) }
}

private fun CentralPortalResponse.centralRetryAfterMillis(): Long {
    val value = headers.entries.firstOrNull { it.key.equals("Retry-After", ignoreCase = true) }
        ?.value?.firstOrNull()?.trim().orEmpty()
    val seconds = value.toLongOrNull()
    val millis = if (seconds != null) {
        if (seconds > Long.MAX_VALUE / 1_000) Long.MAX_VALUE else seconds.coerceAtLeast(0) * 1_000
    } else {
        runCatching {
            Duration.between(
                Instant.now(),
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant(),
            ).toMillis().coerceAtLeast(0)
        }.getOrDefault(CENTRAL_RETRY_FALLBACK_MILLIS)
    }
    return millis.coerceAtMost(CENTRAL_RETRY_MAX_MILLIS)
}

internal const val DEFAULT_CENTRAL_PORTAL_API = "https://central.sonatype.com/api/v1/publisher"
