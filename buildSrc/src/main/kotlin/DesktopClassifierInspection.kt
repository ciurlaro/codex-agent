import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

internal data class DesktopClassifierProof(
    val target: String,
    val classifier: String,
    val archiveFile: File,
    val archiveSha256: String,
    val archiveBytes: Long,
    val executableName: String,
    val binarySha256: String,
    val supervisorExecutableName: String,
    val supervisorSha256: String,
)

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
    val hashes = ZipFile(archive).use { zip ->
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
        )
        check(entries.map(ZipEntry::getName).toSet() == expected && entries.size == expected.size) {
            "Classifier member set mismatch for $target"
        }
        fun digest(name: String) = zip.getInputStream(zip.getEntry(name)).use { it.releaseDigest() }
        digest(distribution.executableName) to digest(distribution.supervisorExecutableName)
    }
    check(hashes.first == distribution.binarySha256) { "App Server hash is not pinned for $target" }
    return DesktopClassifierProof(
        target = target,
        classifier = distribution.classifier,
        archiveFile = archive,
        archiveSha256 = archive.releaseDigest(),
        archiveBytes = archive.length(),
        executableName = distribution.executableName,
        binarySha256 = hashes.first,
        supervisorExecutableName = distribution.supervisorExecutableName,
        supervisorSha256 = hashes.second,
    )
}
