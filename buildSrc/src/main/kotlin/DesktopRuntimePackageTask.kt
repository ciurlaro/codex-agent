import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

data class DesktopCodexManifest(
    val version: String,
    val releaseTag: String,
    val distributions: List<DesktopCodexDistributionSpec>,
)

data class DesktopCodexDistributionSpec(
    val target: String,
    val classifier: String,
    val asset: String,
    val archiveSha256: String,
    val archiveEntry: String,
    val binarySha256: String,
    val executableName: String,
)

fun readDesktopCodexManifest(file: File): DesktopCodexManifest {
    val root = Json.parseToJsonElement(file.readText()).jsonObject
    fun kotlinx.serialization.json.JsonObject.string(name: String) = getValue(name).jsonPrimitive.content
    val distributions = root.getValue("distributions").jsonArray.map { value ->
        val entry = value.jsonObject
        DesktopCodexDistributionSpec(
            target = entry.string("target"),
            classifier = entry.string("classifier"),
            asset = entry.string("asset"),
            archiveSha256 = entry.string("archiveSha256"),
            archiveEntry = entry.string("archiveEntry"),
            binarySha256 = entry.string("binarySha256"),
            executableName = entry.string("executableName"),
        )
    }
    val manifest = DesktopCodexManifest(root.string("version"), root.string("releaseTag"), distributions)
    check(manifest.releaseTag == "rust-v${manifest.version}") { "Desktop release tag/version mismatch" }
    check(distributions.size == 5 && distributions.map { it.target }.toSet().size == 5) {
        "Desktop distribution manifest must contain five unique targets"
    }
    check(distributions.map { it.classifier }.toSet().size == 5) {
        "Desktop distribution classifiers must be unique"
    }
    distributions.forEach { distribution ->
        check(distribution.archiveSha256.matches(Regex("[0-9a-f]{64}"))) {
            "${distribution.target} archive SHA-256 is invalid"
        }
        check(distribution.binarySha256.matches(Regex("[0-9a-f]{64}"))) {
            "${distribution.target} binary SHA-256 is invalid"
        }
        check(distribution.archiveEntry == File(distribution.archiveEntry).name) {
            "${distribution.target} archive entry must be at the archive root"
        }
    }
    return manifest
}

@CacheableTask
abstract class PackageDesktopCodexRuntimeTask @Inject constructor(
    private val archives: ArchiveOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {
    @get:Input abstract val releaseTag: Property<String>
    @get:Input abstract val asset: Property<String>
    @get:Input abstract val archiveSha256: Property<String>
    @get:Input abstract val archiveEntry: Property<String>
    @get:Input abstract val binarySha256: Property<String>
    @get:Input abstract val executableName: Property<String>

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val localArchive: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val licenseFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticeFile: RegularFileProperty

    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun packageRuntime() {
        val releaseAsset = asset.get()
        val url = URI("https://github.com/openai/codex/releases/download/${releaseTag.get()}/$releaseAsset")
        val temporary = Files.createTempDirectory(temporaryDir.toPath(), "package-")
        try {
            val archive = temporary.resolve(releaseAsset).toFile()
            if (localArchive.isPresent) {
                Files.copy(localArchive.get().asFile.toPath(), archive.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } else {
                downloadHttps(url, archive.toPath())
            }
            check(archive.releaseDigest() == archiveSha256.get()) {
                "$releaseAsset SHA-256 mismatch"
            }
            val extracted = temporary.resolve("extracted").toFile().also(File::mkdirs)
            files.copy {
                from(if (releaseAsset.endsWith(".zip")) archives.zipTree(archive) else archives.tarTree(archives.gzip(archive)))
                into(extracted)
            }
            val entries = extracted.walkTopDown().filter(File::isFile).toList()
            check(entries.size == 1 && entries.single().relativeTo(extracted).invariantSeparatorsPath == archiveEntry.get()) {
                "$releaseAsset must contain exactly the root executable '${archiveEntry.get()}'"
            }
            val executable = entries.single()
            check(executable.releaseDigest() == binarySha256.get()) {
                "${archiveEntry.get()} SHA-256 mismatch"
            }

            val first = temporary.resolve("first.zip").toFile()
            val second = temporary.resolve("second.zip").toFile()
            writePackage(first, executable)
            writePackage(second, executable)
            check(first.releaseDigest() == second.releaseDigest()) { "Desktop runtime ZIP is not deterministic" }
            verifyPackage(first)
            installAtomically(first, outputFile.get().asFile)
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun writePackage(target: File, executable: File) {
        val members = listOf(
            executableName.get() to executable,
            LICENSE_NAME to licenseFile.get().asFile,
            NOTICE_NAME to noticeFile.get().asFile,
        ).sortedBy(Pair<String, File>::first)
        ZipOutputStream(BufferedOutputStream(target.outputStream())).use { output ->
            output.setLevel(9)
            members.forEach { (name, source) ->
                check(name == File(name).name) { "Unsafe desktop runtime ZIP member: $name" }
                output.putNextEntry(ZipEntry(name).apply { setTimeLocal(ZIP_EPOCH) })
                source.inputStream().use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        patchUnixModes(target)
    }

    private fun patchUnixModes(target: File) = RandomAccessFile(target, "rw").use { archive ->
        fun readUnsignedShortLittleEndian(): Int = archive.readUnsignedByte() or (archive.readUnsignedByte() shl 8)
        fun readUnsignedIntLittleEndian(): Long = (0 until 4).fold(0L) { value, shift ->
            value or (archive.readUnsignedByte().toLong() shl (shift * 8))
        }
        fun writeUnsignedIntLittleEndian(value: Long) {
            repeat(4) { shift -> archive.write(((value shr (shift * 8)) and 0xff).toInt()) }
        }

        val searchStart = maxOf(0L, archive.length() - MAX_EOCD_BYTES)
        var eocd = archive.length() - MIN_EOCD_BYTES
        while (eocd >= searchStart) {
            archive.seek(eocd)
            if (readUnsignedIntLittleEndian() == EOCD_SIGNATURE) break
            eocd--
        }
        check(eocd >= searchStart) { "Desktop runtime ZIP end record is missing" }
        archive.seek(eocd + 10)
        val entryCount = readUnsignedShortLittleEndian()
        archive.seek(eocd + 16)
        var cursor = readUnsignedIntLittleEndian()
        repeat(entryCount) {
            archive.seek(cursor)
            check(readUnsignedIntLittleEndian() == CENTRAL_ENTRY_SIGNATURE) {
                "Desktop runtime ZIP central directory is invalid"
            }
            archive.seek(cursor + 28)
            val nameBytes = ByteArray(readUnsignedShortLittleEndian())
            val extraBytes = readUnsignedShortLittleEndian()
            val commentBytes = readUnsignedShortLittleEndian()
            archive.seek(cursor + 46)
            archive.readFully(nameBytes)
            val name = nameBytes.decodeToString()
            val mode = if (name == executableName.get()) EXECUTABLE_MODE else FILE_MODE
            archive.seek(cursor + 4)
            archive.write(20)
            archive.write(3)
            archive.seek(cursor + 38)
            writeUnsignedIntLittleEndian(mode.toLong() shl 16)
            cursor += 46 + nameBytes.size + extraBytes + commentBytes
        }
    }

    private fun verifyPackage(packageFile: File) = ZipFile(packageFile).use { archive ->
        val members = archive.entries().asSequence().filterNot(ZipEntry::isDirectory).toList()
        val expected = setOf(executableName.get(), LICENSE_NAME, NOTICE_NAME)
        check(members.map(ZipEntry::getName).toSet() == expected && members.size == expected.size) {
            "Desktop runtime ZIP member set is invalid"
        }
        fun digest(name: String) = archive.getInputStream(archive.getEntry(name)).use { it.releaseDigest() }
        check(digest(executableName.get()) == binarySha256.get()) { "Packaged runtime SHA-256 mismatch" }
        check(digest(LICENSE_NAME) == licenseFile.get().asFile.releaseDigest()) { "Packaged license mismatch" }
        check(digest(NOTICE_NAME) == noticeFile.get().asFile.releaseDigest()) { "Packaged notice mismatch" }
    }

    private fun installAtomically(source: File, output: File) {
        output.parentFile.mkdirs()
        val staged = output.toPath().resolveSibling(".${output.name}.${System.nanoTime()}.tmp")
        try {
            Files.copy(source.toPath(), staged, StandardCopyOption.REPLACE_EXISTING)
            try {
                Files.move(staged, output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staged, output.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private companion object {
        const val LICENSE_NAME = "openai-codex-LICENSE.txt"
        const val NOTICE_NAME = "openai-codex-NOTICE.txt"
        const val EXECUTABLE_MODE = 0x81ed
        const val FILE_MODE = 0x81a4
        const val EOCD_SIGNATURE = 0x06054b50L
        const val CENTRAL_ENTRY_SIGNATURE = 0x02014b50L
        const val MIN_EOCD_BYTES = 22L
        const val MAX_EOCD_BYTES = MIN_EOCD_BYTES + 65_535L
        val ZIP_EPOCH: LocalDateTime = LocalDateTime.of(1980, 1, 1, 0, 0)
    }
}
