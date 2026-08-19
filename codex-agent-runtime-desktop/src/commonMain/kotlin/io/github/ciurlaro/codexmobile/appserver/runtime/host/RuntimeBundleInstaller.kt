package io.github.ciurlaro.codexmobile.appserver.runtime.host

import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.blackholeSink
import okio.buffer
import okio.openZip

internal class RuntimeBundleInstaller(
    private val bundleDirectory: Path,
    private val dataDirectory: Path,
    private val descriptor: RuntimeBundleDescriptor,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val makeExecutable: (Path) -> Unit,
) {
    suspend fun install(): InstalledRuntime = installationLock.withLock {
        validateDescriptor()
        require(bundleDirectory.isAbsolute && dataDirectory.isAbsolute) {
            "Runtime bundle and data directories must be absolute"
        }
        require(fileSystem.metadataOrNull(bundleDirectory)?.isDirectory == true) {
            "Runtime bundle directory is unavailable"
        }
        val archivePath = bundleDirectory / archiveName()
        val archiveMetadata = fileSystem.metadataOrNull(archivePath)
        require(archiveMetadata?.isRegularFile == true) {
            "Runtime bundle '${archivePath.name}' is unavailable"
        }
        require(archiveMetadata.size in 22..MAX_RUNTIME_ARCHIVE_BYTES) {
            "Runtime ZIP size is invalid"
        }
        val archiveBytes = read(archivePath)
        val zipMembers = inspectRuntimeZip(archiveBytes)
        val zip = fileSystem.openZip(archivePath)
        val manifestPath = "/$RUNTIME_MANIFEST_NAME".toPath()
        val manifestSource = zip.source(manifestPath).buffer()
        val manifestBytes = try {
            manifestSource.readByteArray()
        } finally {
            manifestSource.close()
        }
        val manifest = parseRuntimeBundleManifest(manifestBytes)
        validateManifest(manifest, zipMembers)

        fileSystem.createDirectories(dataDirectory)
        val installationRoot = fileSystem.canonicalize(dataDirectory)
        val versionDirectory = installationRoot / "runtimes" / descriptor.libraryVersion
        val installedDirectory = versionDirectory / descriptor.target
        fileSystem.createDirectories(versionDirectory)
        cleanStaging(versionDirectory)
        if (validInstallation(installedDirectory, manifest, manifestBytes)) {
            return installedRuntime(installedDirectory, manifest)
        }

        val token = Random.nextLong().toString().replace('-', '0')
        val staging = versionDirectory / ".${descriptor.target}.staging-$token"
        val displaced = versionDirectory / ".${descriptor.target}.corrupt-$token"
        fileSystem.deleteRecursively(staging, mustExist = false)
        fileSystem.createDirectories(staging)
        try {
            write(staging / RUNTIME_MANIFEST_NAME, manifestBytes)
            manifest.members.forEach { member ->
                val target = staging / member.name
                val source = zip.source("/${member.name}".toPath()).buffer()
                val sink = fileSystem.sink(target).buffer()
                try {
                    sink.writeAll(source)
                } finally {
                    runCatching { sink.close() }
                    source.close()
                }
                require(fileSystem.metadata(target).size == member.size && target.sha256() == member.sha256) {
                    "Extracted runtime member '${member.name}' is corrupt"
                }
                if (member.executable) makeExecutable(target)
            }
            check(validInstallation(staging, manifest, manifestBytes)) { "Staged runtime validation failed" }
            val hadInstalled = fileSystem.metadataOrNull(installedDirectory) != null
            if (hadInstalled) fileSystem.atomicMove(installedDirectory, displaced)
            try {
                fileSystem.atomicMove(staging, installedDirectory)
            } catch (error: Throwable) {
                if (hadInstalled && fileSystem.metadataOrNull(displaced) != null) {
                    fileSystem.atomicMove(displaced, installedDirectory)
                }
                throw error
            }
            fileSystem.deleteRecursively(displaced, mustExist = false)
        } finally {
            fileSystem.deleteRecursively(staging, mustExist = false)
        }
        installedRuntime(installedDirectory, manifest)
    }

    private fun validInstallation(
        directory: Path,
        manifest: RuntimeBundleManifest,
        manifestBytes: ByteArray,
    ): Boolean = runCatching {
        check(fileSystem.metadata(directory).isDirectory && fileSystem.metadata(directory).symlinkTarget == null)
        check(fileSystem.canonicalize(directory) == directory)
        check(fileSystem.list(directory).map(Path::name).toSet() ==
            manifest.members.map(RuntimeBundleMember::name).toSet() + RUNTIME_MANIFEST_NAME)
        val installedManifest = read(directory / RUNTIME_MANIFEST_NAME)
        check(installedManifest.contentEquals(manifestBytes))
        manifest.members.forEach { member ->
            val path = directory / member.name
            val metadata = fileSystem.metadata(path)
            check(metadata.isRegularFile && metadata.symlinkTarget == null && metadata.size == member.size)
            check(fileSystem.canonicalize(path) == path && path.sha256() == member.sha256)
            if (member.executable) makeExecutable(path)
        }
        true
    }.getOrDefault(false)

    private fun validateManifest(manifest: RuntimeBundleManifest, zipMembers: List<RuntimeZipMember>) {
        require(manifest.libraryVersion == descriptor.libraryVersion &&
            manifest.appServerVersion == descriptor.appServerVersion &&
            manifest.target == descriptor.target && manifest.classifier == descriptor.classifier) {
            "Runtime manifest identity does not match the current target"
        }
        val expectedNames = setOf(
            descriptor.appServerName,
            descriptor.supervisorName,
            RUNTIME_LICENSE_NAME,
            RUNTIME_NOTICE_NAME,
        )
        require(manifest.members.size == expectedNames.size && manifest.members.map { it.name }.toSet() == expectedNames) {
            "Runtime manifest member set is invalid"
        }
        require(zipMembers.map { it.name }.toSet() == expectedNames + RUNTIME_MANIFEST_NAME &&
            zipMembers.size == expectedNames.size + 1) { "Runtime ZIP has missing or extra members" }
        val zipSizes = zipMembers.associate { it.name to it.size }
        manifest.members.forEach { member ->
            require(member.name.isSafeMemberName() && member.size > 0 &&
                member.sha256.matches(Regex("[0-9a-f]{64}")) && zipSizes[member.name] == member.size) {
                "Runtime manifest member '${member.name}' is invalid"
            }
        }
        require(manifest.members.single { it.name == descriptor.appServerName }.sha256 == descriptor.appServerSha256) {
            "Runtime manifest App Server checksum is invalid"
        }
        require(manifest.members.filter { it.executable }.map { it.name }.toSet() ==
            setOf(descriptor.appServerName, descriptor.supervisorName)) {
            "Runtime manifest executable set is invalid"
        }
    }

    private fun validateDescriptor() {
        listOf(descriptor.libraryVersion, descriptor.appServerVersion, descriptor.target, descriptor.classifier)
            .forEach { require(it.matches(Regex("[A-Za-z0-9._-]+"))) { "Runtime identity is invalid" } }
        require(descriptor.appServerName.isSafeMemberName() && descriptor.supervisorName.isSafeMemberName())
        require(descriptor.appServerSha256.matches(Regex("[0-9a-f]{64}")))
    }

    private fun cleanStaging(versionDirectory: Path) {
        fileSystem.list(versionDirectory)
            .filter {
                it.name.startsWith(".${descriptor.target}.staging-") ||
                    it.name.startsWith(".${descriptor.target}.corrupt-")
            }
            .forEach { fileSystem.deleteRecursively(it, mustExist = false) }
    }

    private fun installedRuntime(directory: Path, manifest: RuntimeBundleManifest) = InstalledRuntime(
        appServer = directory / descriptor.appServerName,
        supervisor = directory / descriptor.supervisorName,
        supervisorSha256 = manifest.members.single { it.name == descriptor.supervisorName }.sha256,
    )

    private fun archiveName(): String =
        "codex-agent-runtime-desktop-${descriptor.libraryVersion}-${descriptor.classifier}.zip"

    private fun write(path: Path, bytes: ByteArray) {
        val sink = fileSystem.sink(path).buffer()
        try {
            sink.write(bytes)
        } finally {
            sink.close()
        }
    }

    private fun read(path: Path): ByteArray {
        val source = fileSystem.source(path).buffer()
        return try {
            source.readByteArray()
        } finally {
            source.close()
        }
    }

    private fun Path.sha256(): String {
        val hashing = HashingSource.sha256(fileSystem.source(this))
        val source = hashing.buffer()
        try {
            source.readAll(blackholeSink())
        } finally {
            source.close()
        }
        return hashing.hash.hex()
    }

    private companion object {
        val installationLock = Mutex()
    }
}

private fun String.isSafeMemberName(): Boolean =
    isNotBlank() && this != "." && this != ".." && '/' !in this && '\\' !in this && ':' !in this
