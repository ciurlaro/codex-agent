import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.streams.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

const val WINDOWS_SUPERVISOR_FILE_NAME = "codex-agent-node-windows-supervisor.exe"
const val WINDOWS_SUPERVISOR_IDENTITY_FILE_NAME = "windows-supervisor.json"
internal const val WINDOWS_SUPERVISOR_SCHEMA_VERSION = 1
internal const val WINDOWS_SUPERVISOR_GENERATOR = "Visual Studio 17 2022"
internal const val WINDOWS_SUPERVISOR_ARCHITECTURE = "x64"
internal const val WINDOWS_SUPERVISOR_CONFIGURATION = "Release"
internal const val WINDOWS_SUPERVISOR_TARGET = "codex_agent_node_windows_supervisor"

private val identityFields = setOf(
    "schemaVersion", "fileName", "sha256", "bytes", "sourceSha256", "compiler", "compilerCommand",
)
private val compilerFields = setOf(
    "id", "version", "cmakeVersion", "generator", "architecture", "configuration",
)
private val sourceNames = setOf("CMakeLists.txt", "supervisor.c")
private val json = Json { prettyPrint = true }
private val zipEpoch = LocalDateTime.of(1980, 1, 1, 0, 0)

internal data class WindowsSupervisorCompiler(
    val id: String,
    val version: String,
    val cmakeVersion: String,
    val generator: String = WINDOWS_SUPERVISOR_GENERATOR,
    val architecture: String = WINDOWS_SUPERVISOR_ARCHITECTURE,
    val configuration: String = WINDOWS_SUPERVISOR_CONFIGURATION,
)

internal data class WindowsSupervisorIdentity(
    val fileName: String,
    val sha256: String,
    val bytes: Long,
    val sourceSha256: String,
    val compiler: WindowsSupervisorCompiler,
    val compilerCommand: List<String> = windowsSupervisorPolicyCommands(),
)

internal fun windowsSupervisorConfigureCommand(
    cmake: String,
    source: File,
    build: File,
    output: File,
): List<String> = listOf(
    cmake, "-S", source.absolutePath, "-B", build.absolutePath,
    "-G", WINDOWS_SUPERVISOR_GENERATOR, "-A", WINDOWS_SUPERVISOR_ARCHITECTURE,
    "-DCMAKE_BUILD_TYPE=$WINDOWS_SUPERVISOR_CONFIGURATION",
    "-DCODEX_AGENT_SUPERVISOR_OUTPUT_DIR=${output.absolutePath}",
)

internal fun windowsSupervisorBuildCommand(cmake: String, build: File): List<String> = listOf(
    cmake, "--build", build.absolutePath, "--config", WINDOWS_SUPERVISOR_CONFIGURATION,
    "--target", WINDOWS_SUPERVISOR_TARGET, "--parallel", "1",
)

internal fun windowsSupervisorPolicyCommands(): List<String> = listOf(
    "cmake -S <SOURCE> -B <BUILD> -G \"$WINDOWS_SUPERVISOR_GENERATOR\" " +
        "-A $WINDOWS_SUPERVISOR_ARCHITECTURE -DCMAKE_BUILD_TYPE=$WINDOWS_SUPERVISOR_CONFIGURATION " +
        "-DCODEX_AGENT_SUPERVISOR_OUTPUT_DIR=<OUTPUT>",
    "cmake --build <BUILD> --config $WINDOWS_SUPERVISOR_CONFIGURATION " +
        "--target $WINDOWS_SUPERVISOR_TARGET --parallel 1",
)

internal fun windowsSupervisorSourceSha256(source: File): String {
    val root = source.toPath()
    check(source.isDirectory && !Files.isSymbolicLink(root)) { "Windows supervisor source must be a real directory" }
    val paths = Files.walk(root).use { it.toList() }
    check(paths.none { it != root && Files.isSymbolicLink(it) }) { "Windows supervisor source contains a link" }
    val files = paths.filter { Files.isRegularFile(it) }.associateBy {
        root.relativize(it).invariantSeparatorsPathString
    }
    check(files.keys == sourceNames) { "Windows supervisor source set must be exactly $sourceNames" }
    val digest = MessageDigest.getInstance("SHA-256")
    files.toSortedMap().forEach { (name, path) ->
        digest.update(name.encodeToByteArray()); digest.update(0)
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.update(0)
    }
    return digest.digest().hex()
}

internal fun File.windowsSupervisorSha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    digest.digest().hex()
}

internal fun writeWindowsSupervisorIdentity(file: File, identity: WindowsSupervisorIdentity) {
    file.writeSupervisorText(json.encodeToString(JsonObject.serializer(), identity.toJson()) + "\n")
}

internal fun readWindowsSupervisorIdentity(file: File): WindowsSupervisorIdentity =
    parseWindowsSupervisorIdentity(file.readText())

internal fun verifyWindowsSupervisorIdentity(
    identity: WindowsSupervisorIdentity,
    executable: File,
    source: File,
) {
    check(identity.fileName == WINDOWS_SUPERVISOR_FILE_NAME && executable.name == identity.fileName) {
        "Windows supervisor basename mismatch"
    }
    check(identity.sha256.matches(Regex("[0-9a-f]{64}")) && executable.windowsSupervisorSha256() == identity.sha256) {
        "Windows supervisor SHA-256 mismatch"
    }
    check(identity.bytes > 0 && executable.length() == identity.bytes) { "Windows supervisor byte count mismatch" }
    check(identity.sourceSha256 == windowsSupervisorSourceSha256(source)) { "Windows supervisor source mismatch" }
    check(identity.compiler.id == "MSVC" && identity.compiler.version.matches(Regex("[0-9]+(?:\\.[0-9]+)+"))) {
        "Windows supervisor compiler is not pinned MSVC"
    }
    check(identity.compiler.cmakeVersion.matches(Regex("[0-9]+(?:\\.[0-9]+)+"))) {
        "Windows supervisor CMake version is invalid"
    }
    check(identity.compiler.generator == WINDOWS_SUPERVISOR_GENERATOR &&
        identity.compiler.architecture == WINDOWS_SUPERVISOR_ARCHITECTURE &&
        identity.compiler.configuration == WINDOWS_SUPERVISOR_CONFIGURATION) {
        "Windows supervisor compiler policy mismatch"
    }
    check(identity.compilerCommand == windowsSupervisorPolicyCommands()) {
        "Windows supervisor compiler command policy mismatch"
    }
}

internal fun writeWindowsSupervisorPackage(target: File, executable: File, identityFile: File) {
    check(identityFile.name == WINDOWS_SUPERVISOR_IDENTITY_FILE_NAME) {
        "Windows supervisor identity basename mismatch"
    }
    val members = listOf(executable.name to executable.readBytes(), identityFile.name to identityFile.readBytes()).sortedBy { it.first }
    target.parentFile.mkdirs()
    ZipOutputStream(target.outputStream().buffered()).use { output ->
        members.forEach { (name, bytes) ->
            check(name == File(name).name) { "Unsafe Windows supervisor package member" }
            val crc = CRC32().apply { update(bytes) }
            output.putNextEntry(ZipEntry(name).apply {
                method = ZipEntry.STORED; size = bytes.size.toLong(); compressedSize = size
                this.crc = crc.value; setTimeLocal(zipEpoch)
            })
            output.write(bytes); output.closeEntry()
        }
    }
}

internal fun verifyWindowsSupervisorPackage(
    packageFile: File,
    canonicalIdentity: File,
    source: File,
): WindowsSupervisorIdentity = ZipFile(packageFile).use { zip ->
    check(canonicalIdentity.name == WINDOWS_SUPERVISOR_IDENTITY_FILE_NAME) {
        "Windows supervisor identity basename mismatch"
    }
    val entries = zip.entries().asSequence().toList()
    val expected = setOf(WINDOWS_SUPERVISOR_FILE_NAME, canonicalIdentity.name)
    check(entries.none(ZipEntry::isDirectory) && entries.size == expected.size && entries.map(ZipEntry::getName).toSet() == expected) {
        "Windows supervisor package member set mismatch"
    }
    val identityBytes = zip.getInputStream(zip.getEntry(canonicalIdentity.name)).use { it.readBytes() }
    check(identityBytes.contentEquals(canonicalIdentity.readBytes())) { "Packaged Windows supervisor identity mismatch" }
    val identity = parseWindowsSupervisorIdentity(identityBytes.decodeToString())
    val executable = zip.getInputStream(zip.getEntry(WINDOWS_SUPERVISOR_FILE_NAME)).use { it.readBytes() }
    check(executable.size.toLong() == identity.bytes && executable.sha256() == identity.sha256) {
        "Packaged Windows supervisor executable mismatch"
    }
    check(identity.sourceSha256 == windowsSupervisorSourceSha256(source)) { "Packaged Windows supervisor source mismatch" }
    identity
}

private fun WindowsSupervisorIdentity.toJson() = buildJsonObject {
    put("schemaVersion", JsonPrimitive(WINDOWS_SUPERVISOR_SCHEMA_VERSION)); put("fileName", JsonPrimitive(fileName))
    put("sha256", JsonPrimitive(sha256)); put("bytes", JsonPrimitive(bytes)); put("sourceSha256", JsonPrimitive(sourceSha256))
    put("compiler", buildJsonObject {
        put("id", JsonPrimitive(compiler.id)); put("version", JsonPrimitive(compiler.version))
        put("cmakeVersion", JsonPrimitive(compiler.cmakeVersion)); put("generator", JsonPrimitive(compiler.generator))
        put("architecture", JsonPrimitive(compiler.architecture)); put("configuration", JsonPrimitive(compiler.configuration))
    })
    put("compilerCommand", buildJsonArray { compilerCommand.forEach { add(JsonPrimitive(it)) } })
}

private fun parseWindowsSupervisorIdentity(text: String): WindowsSupervisorIdentity {
    val root = Json.parseToJsonElement(text).jsonObject
    check(root.keys == identityFields && root.getValue("schemaVersion").jsonPrimitive.content.toInt() == WINDOWS_SUPERVISOR_SCHEMA_VERSION) {
        "Windows supervisor identity schema mismatch"
    }
    val compiler = root.getValue("compiler").jsonObject
    check(compiler.keys == compilerFields) { "Windows supervisor compiler schema mismatch" }
    fun JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
    return WindowsSupervisorIdentity(
        fileName = root.string("fileName"), sha256 = root.string("sha256"),
        bytes = root.string("bytes").toLong(), sourceSha256 = root.string("sourceSha256"),
        compiler = WindowsSupervisorCompiler(
            compiler.string("id"), compiler.string("version"), compiler.string("cmakeVersion"),
            compiler.string("generator"), compiler.string("architecture"), compiler.string("configuration"),
        ),
        compilerCommand = root.getValue("compilerCommand").jsonArray.map { it.jsonPrimitive.content },
    )
}

private fun File.writeSupervisorText(text: String) {
    parentFile.mkdirs()
    val staged = toPath().resolveSibling(".$name.${System.nanoTime()}.tmp")
    try {
        Files.writeString(staged, text)
        try {
            Files.move(staged, toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(staged, toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(staged)
    }
}

private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).hex()
private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
