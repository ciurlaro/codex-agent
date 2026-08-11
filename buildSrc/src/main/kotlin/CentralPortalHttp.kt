import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64

internal data class CentralPortalRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray = byteArrayOf(),
    val bodyFile: File? = null,
    val suffix: ByteArray = byteArrayOf(),
)

internal data class CentralPortalResponse(val statusCode: Int, val body: String)

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
            HttpResponse.BodyHandlers.ofString(),
        )
        return CentralPortalResponse(response.statusCode(), response.body())
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

internal fun ((CentralPortalRequest) -> CentralPortalResponse).checked(request: CentralPortalRequest): String {
    val response = invoke(request)
    check(response.statusCode in 200..299) { "Central Portal HTTP ${response.statusCode}: ${response.body}" }
    return response.body
}

internal const val DEFAULT_CENTRAL_PORTAL_API = "https://central.sonatype.com/api/v1/publisher"
