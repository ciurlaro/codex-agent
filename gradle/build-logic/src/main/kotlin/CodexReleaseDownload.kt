import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal fun downloadHttps(url: URI, target: Path) {
    check(url.scheme == "https") { "Download must use HTTPS" }
    val request = HttpRequest.newBuilder(url).timeout(Duration.ofMinutes(5)).GET().build()
    val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(60))
        .build()
    var failure: java.io.IOException? = null
    repeat(3) { attempt ->
        Files.deleteIfExists(target)
        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofFile(target))
            check(response.statusCode() in 200..299) {
                "Download failed with HTTP ${response.statusCode()}"
            }
            check(response.uri().scheme == "https") { "Download redirected outside HTTPS" }
            return
        } catch (error: java.io.IOException) {
            failure = error
            if (attempt < 2) Thread.sleep(1_000L)
        }
    }
    throw checkNotNull(failure)
}
