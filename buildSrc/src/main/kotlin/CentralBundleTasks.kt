import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

private val centralChecksumSuffixes = listOf(".md5", ".sha1", ".sha256", ".sha512")

internal fun centralExclusion(file: File): String? = when {
    file.name == "maven-metadata.xml" || file.name.startsWith("maven-metadata.xml.") ->
        "repository metadata is not part of a Central deployment"
    centralChecksumSuffixes.any { file.name.endsWith(".asc$it") } ->
        "signature checksum is not part of a Central deployment"
    else -> null
}

internal fun buildCentralBundle(
    repository: File,
    mavenInventory: File,
    bundle: File,
    report: File,
    maximumBytes: Long,
) {
    check(repository.isDirectory) { "Maven staging repository is missing" }
    check(mavenInventory.isFile) { "Maven inventory is missing" }
    val canonicalRepository = repository.canonicalFile
    check(!bundle.canonicalFile.toPath().startsWith(canonicalRepository.toPath())) {
        "Central output bundle must be outside the staged repository"
    }
    val files = repository.walkTopDown().filter(File::isFile)
        .sortedBy { it.relativeTo(repository).invariantSeparatorsPath }
        .toList()
    check(files.isNotEmpty()) { "Maven staging repository is empty" }
    val included = files.filter { centralExclusion(it) == null }
    check(included.isNotEmpty()) { "Central deployment bundle would be empty" }

    bundle.parentFile.mkdirs()
    val temporary = Files.createTempFile(bundle.parentFile.toPath(), ".${bundle.name}-", ".tmp").toFile()
    ZipOutputStream(BufferedOutputStream(temporary.outputStream())).use { output ->
        output.setLevel(Deflater.BEST_COMPRESSION)
        included.forEach { file ->
            val relative = file.relativeTo(repository).invariantSeparatorsPath
            val entry = ZipEntry(relative).apply { setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0)) }
            output.putNextEntry(entry)
            file.inputStream().use { it.copyTo(output) }
            output.closeEntry()
        }
    }
    Files.move(temporary.toPath(), bundle.toPath(), REPLACE_EXISTING)
    check(bundle.length() < maximumBytes) {
        "Central bundle must remain below $maximumBytes bytes: ${bundle.length()}"
    }

    val entries = ZipFile(bundle).use { archive ->
        val values = mutableListOf<java.util.zip.ZipEntry>()
        val enumeration = archive.entries()
        while (enumeration.hasMoreElements()) values += enumeration.nextElement()
        values.filterNot { it.isDirectory }.map { entry ->
            buildJsonObject {
                put("path", JsonPrimitive(entry.name))
                put("bytes", JsonPrimitive(entry.size))
                put("compressedBytes", JsonPrimitive(entry.compressedSize))
                put("crc32", JsonPrimitive("%08x".format(entry.crc)))
            }
        }
    }
    report.atomicWriteJson(buildJsonObject {
        put("schemaVersion", JsonPrimitive(2))
        put("artifactCount", JsonPrimitive(files.size))
        put("includedArtifactCount", JsonPrimitive(included.size))
        put("artifacts", buildJsonArray {
            files.forEach { file ->
                val exclusion = centralExclusion(file)
                add(buildJsonObject {
                    put("path", JsonPrimitive(file.relativeTo(repository).invariantSeparatorsPath))
                    put("bytes", JsonPrimitive(file.length()))
                    put("sha256", JsonPrimitive(file.releaseDigest()))
                    put("included", JsonPrimitive(exclusion == null))
                    if (exclusion != null) put("exclusionReason", JsonPrimitive(exclusion))
                })
            }
        })
        put("bundle", buildJsonObject {
            bundle.releaseRecord().forEach { (key, value) -> put(key, value) }
            put("entryCount", JsonPrimitive(entries.size))
            put("entries", buildJsonArray { entries.forEach(::add) })
        })
        put("mavenInventorySha256", JsonPrimitive(mavenInventory.releaseDigest()))
        put("centralPortalUploadLimitBytes", JsonPrimitive(maximumBytes))
        put("belowCentralPortalUploadLimit", JsonPrimitive(true))
    })
}

@DisableCachingByDefault(because = "The signed repository is intentionally bundled afresh")
abstract class BuildCentralBundleTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val repositoryDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val mavenInventory: RegularFileProperty
    @get:Input abstract val maximumBytes: Property<Long>
    @get:OutputFile abstract val bundleFile: RegularFileProperty
    @get:OutputFile abstract val inventoryFile: RegularFileProperty

    @TaskAction
    fun build() = buildCentralBundle(
        repositoryDirectory.get().asFile,
        mavenInventory.get().asFile,
        bundleFile.get().asFile,
        inventoryFile.get().asFile,
        maximumBytes.get(),
    )
}
