import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Base64
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "This task mutates and polls a remote deployment")
abstract class CentralPortalTask : DefaultTask() {
    @get:Input
    abstract val mode: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val bundleFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val candidateManifest: RegularFileProperty

    @get:Internal
    abstract val deploymentRecord: RegularFileProperty

    @get:Input
    abstract val apiBaseUrl: Property<String>

    @get:Internal
    abstract val username: Property<String>

    @get:Internal
    abstract val password: Property<String>

    init {
        apiBaseUrl.convention(
            project.providers.environmentVariable("CENTRAL_PORTAL_API").orElse(DEFAULT_CENTRAL_PORTAL_API),
        )
        username.convention(project.providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
        password.convention(project.providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
    }

    @TaskAction
    fun publish() = executeCentralPortal(
        mode = mode.get(),
        bundle = bundleFile.get().asFile,
        candidate = candidateManifest.get().asFile,
        record = deploymentRecord.get().asFile,
        api = apiBaseUrl.get(),
        username = username.orNull ?: error("missing Central username"),
        password = password.orNull ?: error("missing Central password"),
    )
}

internal data class CentralPortalRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray = byteArrayOf(),
    val bodyFile: File? = null,
    val suffix: ByteArray = byteArrayOf(),
)

internal data class CentralPortalResponse(val statusCode: Int, val body: String)

internal fun executeCentralPortal(
    mode: String,
    bundle: File,
    candidate: File,
    record: File,
    api: String,
    username: String,
    password: String,
    sender: (CentralPortalRequest) -> CentralPortalResponse = JdkCentralPortalSender()::send,
    sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    check(mode == "validate" || mode == "release") { "mode must be validate or release" }
    check(bundle.isFile && candidate.isFile) { "bundle and candidate manifest must be files" }
    check(username.isNotEmpty()) { "missing Central username" }
    check(password.isNotEmpty()) { "missing Central password" }

    val bundleSha = bundle.sha256()
    val candidateSha = candidate.sha256()
    val candidateJson = candidate.jsonObject()
    val version = candidateJson.requiredString("version")
    val commit = candidateJson.requiredString("candidateCommit")
    val name = "codex-agent-$version-${commit.take(12)}-${bundleSha.take(12)}"
    val authorization = "Bearer " + Base64.getEncoder().encodeToString("$username:$password".toByteArray(UTF_8))
    val headers = mapOf("Authorization" to authorization)

    val deploymentId = if (record.isFile) {
        val stored = record.jsonObject()
        check(stored.requiredString("deploymentName") == name) { "Central deployment name mismatch" }
        check(stored.requiredString("candidateManifestSha256") == candidateSha) {
            "Central candidate manifest SHA-256 mismatch"
        }
        check(stored.requiredString("bundleSha256") == bundleSha) { "Central bundle SHA-256 mismatch" }
        stored.requiredString("deploymentId")
    } else {
        check(mode == "validate") { "release requires an existing deployment record" }
        val upload = multipartUpload(bundle, headers, api, name)
        val id = sender.checked(upload).trimEnd('\n')
        check(id.isNotEmpty()) { "Central upload returned an empty deployment ID" }
        record.writeDeployment(id, name, "PENDING", candidateSha, bundleSha)
        id
    }

    fun pollUntil(wanted: String): String {
        repeat(POLL_ATTEMPTS) {
            val response = sender.checked(
                CentralPortalRequest("POST", "$api/status?id=$deploymentId", headers),
            )
            val state = response.jsonObject().requiredString("deploymentState")
            record.writeDeployment(deploymentId, name, state, candidateSha, bundleSha)
            when (state) {
                "FAILED" -> {
                    System.err.println(response)
                    error("Central deployment failed")
                }
                "PUBLISHED", wanted -> return state
            }
            sleeper(POLL_DELAY_MILLIS)
        }
        error("Central deployment timed out waiting for $wanted")
    }

    val state = pollUntil("VALIDATED")
    if (mode == "release" && state != "PUBLISHED") {
        check(state == "VALIDATED")
        print(sender.checked(CentralPortalRequest("POST", "$api/deployment/$deploymentId", headers)))
        pollUntil("PUBLISHED")
    }
}

private class JdkCentralPortalSender {
    private val client = HttpClient.newHttpClient()

    fun send(request: CentralPortalRequest): CentralPortalResponse {
        val builder = HttpRequest.newBuilder(URI.create(request.url))
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

private fun multipartUpload(
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
    val suffix = "\r\n--$boundary--\r\n".toByteArray(UTF_8)
    return CentralPortalRequest(
        method = "POST",
        url = "$api/upload?publishingType=USER_MANAGED&name=$deploymentName",
        headers = headers + ("Content-Type" to "multipart/form-data; boundary=$boundary"),
        body = prefix,
        bodyFile = bundle,
        suffix = suffix,
    )
}

private fun ((CentralPortalRequest) -> CentralPortalResponse).checked(request: CentralPortalRequest): String {
    val response = invoke(request)
    check(response.statusCode in 200..299) {
        "Central Portal HTTP ${response.statusCode}: ${response.body}"
    }
    return response.body
}

private fun File.writeDeployment(
    id: String,
    name: String,
    state: String,
    candidateSha: String,
    bundleSha: String,
) {
    val destination = toPath()
    Files.createDirectories(destination.toAbsolutePath().parent)
    val temporary = destination.resolveSibling("${destination.fileName}.tmp")
    Files.writeString(
        temporary,
        JsonOutput.prettyPrint(
            JsonOutput.toJson(
                linkedMapOf(
                    "schemaVersion" to 1,
                    "deploymentId" to id,
                    "deploymentName" to name,
                    "deploymentState" to state,
                    "candidateManifestSha256" to candidateSha,
                    "bundleSha256" to bundleSha,
                ),
            ),
        ) + "\n",
    )
    try {
        Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temporary, destination, REPLACE_EXISTING)
    }
}

@Suppress("UNCHECKED_CAST")
private fun File.jsonObject(): Map<String, Any?> = JsonSlurper().parse(this) as? Map<String, Any?>
    ?: error("Expected JSON object: $path")

@Suppress("UNCHECKED_CAST")
private fun String.jsonObject(): Map<String, Any?> = JsonSlurper().parseText(this) as? Map<String, Any?>
    ?: error("Expected JSON object")

private fun Map<String, Any?>.requiredString(name: String): String = this[name] as? String
    ?: error("Missing JSON string: $name")

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

private const val DEFAULT_CENTRAL_PORTAL_API = "https://central.sonatype.com/api/v1/publisher"
private const val POLL_ATTEMPTS = 120
private const val POLL_DELAY_MILLIS = 10_000L

fun Project.registerCentralPortalTasks() {
    val bundle = layout.file(providers.gradleProperty("codexAgent.centralBundle").map(::file))
    val candidate = layout.file(providers.gradleProperty("codexAgent.candidateManifest").map(::file))
    val record = layout.file(providers.gradleProperty("codexAgent.centralDeploymentRecord").map(::file))
    listOf(
        "validateCentralDeployment" to "validate",
        "releaseCentralDeployment" to "release",
    ).forEach { (taskName, taskMode) ->
        tasks.register(taskName, CentralPortalTask::class.java) {
            group = "publishing"
            description = "$taskMode the exact user-managed Central Portal deployment."
            mode.set(taskMode)
            bundleFile.set(bundle)
            candidateManifest.set(candidate)
            deploymentRecord.set(record)
        }
    }
}
