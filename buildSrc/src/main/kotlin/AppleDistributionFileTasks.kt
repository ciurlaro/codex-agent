import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

internal fun deleteReleaseTree(file: File) {
    if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
    Files.walk(file.toPath()).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}

internal fun copyReleaseTree(source: File, target: File) {
    check(source.isDirectory) { "Distribution source directory is missing: $source" }
    val sourcePath = source.toPath()
    Files.walk(sourcePath).use { stream ->
        stream.sorted(compareBy { sourcePath.relativize(it).nameCount }).forEach { path ->
            val destination = target.toPath().resolve(sourcePath.relativize(path))
            when {
                Files.isSymbolicLink(path) -> {
                    Files.createDirectories(destination.parent)
                    Files.deleteIfExists(destination)
                    Files.createSymbolicLink(destination, Files.readSymbolicLink(path))
                }
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(destination)
                else -> {
                    Files.createDirectories(destination.parent)
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }
}

private fun copyReleaseFile(source: File, target: File) {
    check(source.isFile) { "Distribution source file is missing: $source" }
    Files.createDirectories(target.toPath().parent)
    Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
}

internal data class AppleDistributionInputs(
    val packageManifest: File,
    val sources: File,
    val tests: File,
    val xcframework: File,
    val license: File,
    val thirdPartyNotices: File,
    val codexLicense: File,
    val codexNotice: File,
    val testApplication: File,
)

internal fun stageAppleDistribution(inputs: AppleDistributionInputs, distribution: File) {
    deleteReleaseTree(distribution)
    val packageDirectory = distribution.resolve("CodexAgentPackage")
    Files.createDirectories(packageDirectory.toPath())
    copyReleaseFile(inputs.packageManifest, packageDirectory.resolve("Package.swift"))
    copyReleaseTree(inputs.sources, packageDirectory.resolve("Sources"))
    copyReleaseTree(inputs.tests, packageDirectory.resolve("Tests"))
    copyReleaseTree(inputs.xcframework, packageDirectory.resolve("CodexAgent.xcframework"))
    copyReleaseFile(inputs.license, packageDirectory.resolve("LICENSE.txt"))
    copyReleaseFile(inputs.thirdPartyNotices, packageDirectory.resolve("THIRD_PARTY_NOTICES.md"))
    copyReleaseFile(inputs.codexLicense, packageDirectory.resolve("openai-codex-LICENSE.txt"))
    copyReleaseFile(inputs.codexNotice, packageDirectory.resolve("openai-codex-NOTICE.txt"))
    copyReleaseTree(inputs.testApplication, distribution.resolve("CodexAgentTestApp"))
}

internal fun sortedAvailableLibraries(json: String): String {
    val libraries = releaseJson.parseToJsonElement(json) as? JsonArray
        ?: error("XCFramework AvailableLibraries is not a JSON array")
    return JsonArray(libraries.sortedBy {
        (it as? JsonObject ?: error("XCFramework library is not an object")).releaseString("LibraryIdentifier")
    }).toString()
}

internal fun libtoolNormalizeCommand(archive: File, normalized: File) = listOf(
    "/usr/bin/xcrun", "libtool", "-static", "-D", "-no_warning_for_no_symbols",
    archive.absolutePath, "-o", normalized.absolutePath,
)

internal fun stripReleaseArchiveCommand(archive: File, stripped: File) = listOf(
    "/usr/bin/xcrun", "strip", "-S", "-x", "-o", stripped.absolutePath, archive.absolutePath,
)

internal fun verifyPrivacyPlacement(xcframework: File, privacyManifest: File) {
    listOf("ios-arm64", "ios-arm64-simulator").forEach { slice ->
        val packaged = xcframework.resolve("$slice/CodexAgent.framework/PrivacyInfo.xcprivacy")
        check(packaged.isFile && Files.mismatch(privacyManifest.toPath(), packaged.toPath()) == -1L) {
            "Privacy manifest is missing or changed in $slice"
        }
    }
}

internal fun verifyPackagedLicenses(
    sourceToPackaged: List<Pair<File, File>>,
    buildScript: File,
): String {
    sourceToPackaged.forEach { (source, packaged) ->
        check(source.isFile && packaged.isFile && Files.mismatch(source.toPath(), packaged.toPath()) == -1L) {
            "Packaged license differs from ${source.name}"
        }
    }
    check("GNU General Public License v3.0 or later" in buildScript.readText()) {
        "iOS publication is missing GPL metadata"
    }
    return sourceToPackaged.joinToString(separator = "", transform = { (_, packaged) ->
        "${packaged.releaseDigest()}  ${packaged.absolutePath}\n"
    })
}

private fun moveReleaseFile(source: File, target: File) {
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

@DisableCachingByDefault(because = "Normalizes static archives with the selected Apple toolchain")
abstract class PrepareCodexAgentReleaseXCFrameworkTask @Inject constructor(
    private val processes: ExecOperations,
) : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val assembledXCFrameworkDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val privacyManifest: RegularFileProperty
    @get:OutputDirectory abstract val releaseXCFrameworkDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        val assembled = assembledXCFrameworkDirectory.get().asFile
        val release = releaseXCFrameworkDirectory.get().asFile
        deleteReleaseTree(release)
        copyReleaseTree(assembled, release)
        listOf("ios-arm64", "ios-arm64-simulator").forEach { slice ->
            val framework = release.resolve("$slice/CodexAgent.framework")
            copyReleaseFile(privacyManifest.get().asFile, framework.resolve("PrivacyInfo.xcprivacy"))
            val archive = framework.resolve("CodexAgent")
            val stripped = framework.resolve("CodexAgent.stripped")
            val normalized = framework.resolve("CodexAgent.normalized")
            Files.deleteIfExists(stripped.toPath())
            Files.deleteIfExists(normalized.toPath())
            try {
                processes.captureReleaseProcess(stripReleaseArchiveCommand(archive, stripped))
                processes.captureReleaseProcess(libtoolNormalizeCommand(stripped, normalized))
                moveReleaseFile(normalized, archive)
            } finally {
                Files.deleteIfExists(stripped.toPath())
                Files.deleteIfExists(normalized.toPath())
            }
        }
        verifyPrivacyPlacement(release, privacyManifest.get().asFile)
        val infoPlist = release.resolve("Info.plist")
        val libraries = processes.captureReleaseProcess(
            listOf("/usr/bin/plutil", "-extract", "AvailableLibraries", "json", "-o", "-", infoPlist.absolutePath),
        )
        processes.captureReleaseProcess(
            listOf(
                "/usr/bin/plutil", "-replace", "AvailableLibraries", "-json",
                sortedAvailableLibraries(libraries), infoPlist.absolutePath,
            ),
        )
    }
}

@CacheableTask
abstract class StageCodexAgentAppleDistributionTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val packageManifest: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sourcesDirectory: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val testsDirectory: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val xcframeworkDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val licenseFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val thirdPartyNotices: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val codexLicense: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val codexNotice: RegularFileProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val testApplication: DirectoryProperty
    @get:OutputDirectory abstract val distributionDirectory: DirectoryProperty

    @TaskAction fun stage() = stageAppleDistribution(
        AppleDistributionInputs(
            packageManifest.get().asFile, sourcesDirectory.get().asFile, testsDirectory.get().asFile,
            xcframeworkDirectory.get().asFile, licenseFile.get().asFile, thirdPartyNotices.get().asFile,
            codexLicense.get().asFile, codexNotice.get().asFile, testApplication.get().asFile,
        ),
        distributionDirectory.get().asFile,
    )
}

@CacheableTask
abstract class VerifyIosLicensePackagingTask : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val packageDirectory: DirectoryProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val licenseFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val thirdPartyNotices: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val codexLicense: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val codexNotice: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val buildScript: RegularFileProperty
    @get:OutputFile abstract val reportFile: RegularFileProperty

    @TaskAction fun verify() {
        val packageRoot = packageDirectory.get().asFile
        val report = verifyPackagedLicenses(
            listOf(
                licenseFile.get().asFile to packageRoot.resolve("LICENSE.txt"),
                thirdPartyNotices.get().asFile to packageRoot.resolve("THIRD_PARTY_NOTICES.md"),
                codexLicense.get().asFile to packageRoot.resolve("openai-codex-LICENSE.txt"),
                codexNotice.get().asFile to packageRoot.resolve("openai-codex-NOTICE.txt"),
            ),
            buildScript.get().asFile,
        )
        reportFile.get().asFile.apply {
            Files.createDirectories(toPath().parent)
            Files.writeString(toPath(), report, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        }
    }
}
