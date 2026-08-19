import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class DesktopClassifierProof(
    val target: String,
    val classifier: String,
    val libraryVersion: String,
    val archiveFile: File,
    val archiveSha256: String,
    val archiveBytes: Long,
    val executableName: String,
    val binarySha256: String,
    val supervisorExecutableName: String,
    val supervisorSha256: String,
)

internal data class DesktopRuntimeExecutables(val appServer: File, val supervisor: File)

internal fun extractDesktopRuntimeExecutables(
    classifier: DesktopClassifierProof,
    archive: File,
    destination: File,
): DesktopRuntimeExecutables {
    destination.mkdirs()
    val executables = DesktopRuntimeExecutables(
        destination.resolve(classifier.executableName),
        destination.resolve(classifier.supervisorExecutableName),
    )
    ZipFile(archive).use { zip ->
        listOf(
            classifier.executableName to executables.appServer,
            classifier.supervisorExecutableName to executables.supervisor,
        ).forEach { (name, output) ->
            zip.getInputStream(zip.getEntry(name)).use { Files.copy(it, output.toPath(), REPLACE_EXISTING) }
        }
    }
    validateDesktopRuntimeExecutables(
        classifier.target, classifier.binarySha256, classifier.supervisorSha256, executables,
    )
    return executables
}

internal fun validateDesktopRuntimeExecutables(
    target: String,
    binarySha256: String,
    supervisorSha256: String,
    executables: DesktopRuntimeExecutables,
) {
    check(executables.appServer.releaseDigest() == binarySha256) { "Extracted App Server hash mismatch" }
    check(executables.supervisor.releaseDigest() == supervisorSha256) { "Extracted supervisor hash mismatch" }
    if (target != "mingwX64") {
        check(executables.appServer.setExecutable(true, false) && executables.supervisor.setExecutable(true, false)) {
            "Runtime executables could not be made executable"
        }
    }
}

internal fun inspectDesktopClassifier(
    target: String,
    manifest: DesktopCodexManifest,
    archive: File,
): DesktopClassifierProof {
    val expectedTarget = desktopRuntimeEvidenceTargets.getValue(target)
    check(manifest.distributions.map(DesktopCodexDistributionSpec::target).toSet() ==
        desktopRuntimeEvidenceTargets.keys) { "Desktop distribution target set mismatch" }
    val distribution = manifest.distributions.single { it.target == target }
    check(distribution.classifier == expectedTarget.classifier) { "Classifier identity mismatch for $target" }
    check(archive.name == "${expectedTarget.classifier}.zip" ||
        archive.name.endsWith("-${expectedTarget.classifier}.zip")) {
        "Classifier archive filename mismatch for $target"
    }
    check(archive.isFile && archive.length() > 0) { "Classifier archive is missing for $target" }
    val contents = ZipFile(archive).use { zip ->
        val entries = zip.entries().asSequence().toList()
        check(entries.none(ZipEntry::isDirectory)) { "Classifier must not contain directories" }
        check(entries.map(ZipEntry::getName).toSet().size == entries.size) {
            "Classifier contains duplicate members"
        }
        check(entries.all { it.name == File(it.name).name && '/' !in it.name && '\\' !in it.name }) {
            "Classifier contains an unsafe member"
        }
        val expected = setOf(
            distribution.executableName,
            distribution.supervisorExecutableName,
            "openai-codex-LICENSE.txt",
            "openai-codex-NOTICE.txt",
            "codex-runtime-manifest.json",
        )
        check(entries.map(ZipEntry::getName).toSet() == expected && entries.size == expected.size) {
            "Classifier member set mismatch for $target"
        }
        fun digest(name: String) = zip.getInputStream(zip.getEntry(name)).use { it.releaseDigest() }
        val manifestRoot = Json.parseToJsonElement(
            zip.getInputStream(zip.getEntry("codex-runtime-manifest.json")).use {
                it.readBytes().decodeToString()
            },
        ).jsonObject
        check(manifestRoot.keys == setOf(
            "schemaVersion", "libraryVersion", "appServerVersion", "target", "classifier", "members",
        ) && !manifestRoot.getValue("schemaVersion").jsonPrimitive.isString &&
            manifestRoot.getValue("schemaVersion").jsonPrimitive.content == "1" &&
            manifestRoot.getValue("appServerVersion").jsonPrimitive.content == manifest.version &&
            manifestRoot.getValue("target").jsonPrimitive.content == target &&
            manifestRoot.getValue("classifier").jsonPrimitive.content == distribution.classifier) {
            "Classifier runtime manifest identity is invalid for $target"
        }
        val libraryVersion = manifestRoot.getValue("libraryVersion").jsonPrimitive.content
        check(libraryVersion.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Classifier library version is invalid for $target"
        }
        val payloadEntries = entries.filter { it.name != "codex-runtime-manifest.json" }.associateBy(ZipEntry::getName)
        val memberRecords = manifestRoot.getValue("members").jsonArray.map { value -> value.jsonObject }
        check(memberRecords.size == payloadEntries.size && memberRecords.all { member ->
            member.keys == setOf("name", "size", "sha256", "executable")
        }) { "Classifier runtime manifest members are invalid for $target" }
        val members = memberRecords.associateBy { it.getValue("name").jsonPrimitive.content }
        check(members.keys == payloadEntries.keys && members.all { (name, member) ->
            val entry = payloadEntries.getValue(name)
            !member.getValue("size").jsonPrimitive.isString &&
                !member.getValue("executable").jsonPrimitive.isString &&
                member.getValue("size").jsonPrimitive.content.toLong() == entry.size &&
                member.getValue("sha256").jsonPrimitive.content == digest(name) &&
                member.getValue("executable").jsonPrimitive.content.toBooleanStrict() ==
                (name in setOf(distribution.executableName, distribution.supervisorExecutableName))
        }) { "Classifier runtime manifest payload is invalid for $target" }
        InspectedDesktopClassifier(
            libraryVersion,
            digest(distribution.executableName),
            digest(distribution.supervisorExecutableName),
        )
    }
    check(contents.appServerSha256 == distribution.binarySha256) { "App Server hash is not pinned for $target" }
    return DesktopClassifierProof(
        target = target,
        classifier = distribution.classifier,
        libraryVersion = contents.libraryVersion,
        archiveFile = archive,
        archiveSha256 = archive.releaseDigest(),
        archiveBytes = archive.length(),
        executableName = distribution.executableName,
        binarySha256 = contents.appServerSha256,
        supervisorExecutableName = distribution.supervisorExecutableName,
        supervisorSha256 = contents.supervisorSha256,
    )
}

private data class InspectedDesktopClassifier(
    val libraryVersion: String,
    val appServerSha256: String,
    val supervisorSha256: String,
)
