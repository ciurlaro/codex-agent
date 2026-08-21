package io.github.codex_agent_labs.codexmobile.appserver.runtime.host

import io.github.codex_agent_labs.codexmobile.appserver.runtime.nodeHost
import kotlin.random.Random
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import okio.Path.Companion.toPath

internal class NodeRuntimeBundleInstaller(
    bundleDirectory: Path,
    dataDirectory: Path,
    private val descriptor: RuntimeBundleDescriptor,
) {
    private val bundleDirectory = bundleDirectory.toString()
    private val dataDirectory = dataDirectory.toString()

    suspend fun install(): InstalledRuntime = installationLock.withLock {
        validateDescriptor()
        require(nodeHost.isAbsolute(bundleDirectory) && nodeHost.isAbsolute(dataDirectory)) {
            "Runtime bundle and data directories must be absolute"
        }
        require(nodeHost.exists(bundleDirectory) && nodeHost.isDirectory(bundleDirectory)) {
            "Runtime bundle directory is unavailable"
        }
        val archivePath = nodeHost.joinPath(bundleDirectory, archiveName())
        require(nodeHost.exists(archivePath) && nodeHost.isFile(archivePath) &&
            !nodeHost.isSymbolicLink(archivePath)) { "Runtime bundle is unavailable" }
        require(nodeHost.fileSize(archivePath) in 22..MAX_RUNTIME_ARCHIVE_BYTES) {
            "Runtime ZIP size is invalid"
        }
        val archive = nodeHost.readBytes(archivePath)
        val zipMembers = inspectRuntimeZip(archive)
        fun extract(name: String): ByteArray {
            val member = zipMembers.single { it.name == name }
            val compressed = archive.copyOfRange(member.dataOffset, member.dataOffset + member.compressedSize)
            val bytes = when (member.compression) {
                RUNTIME_ZIP_STORED -> compressed
                RUNTIME_ZIP_DEFLATED -> nodeHost.inflateRaw(compressed, (member.size + 1).toInt())
                else -> error("Runtime ZIP entry is unsupported")
            }
            require(bytes.size.toLong() == member.size) { "Runtime ZIP member '$name' is corrupt" }
            return bytes
        }
        val manifestBytes = extract(RUNTIME_MANIFEST_NAME)
        val manifest = parseRuntimeBundleManifest(manifestBytes)
        validateManifest(manifest, zipMembers)

        nodeHost.createDirectories(dataDirectory)
        val installationRoot = nodeHost.realPath(dataDirectory)
        val versionDirectory = nodeHost.joinPath(
            nodeHost.joinPath(installationRoot, "runtimes"),
            descriptor.libraryVersion,
        )
        val installedDirectory = nodeHost.joinPath(versionDirectory, descriptor.target)
        nodeHost.createDirectories(versionDirectory)
        cleanStaging(versionDirectory)
        if (validInstallation(installedDirectory, manifest, manifestBytes)) {
            return installedRuntime(installedDirectory, manifest)
        }

        val token = Random.nextLong().toString().replace('-', '0')
        val staging = nodeHost.joinPath(versionDirectory, ".${descriptor.target}.staging-$token")
        val displaced = nodeHost.joinPath(versionDirectory, ".${descriptor.target}.corrupt-$token")
        nodeHost.removePath(staging)
        nodeHost.createDirectories(staging)
        try {
            nodeHost.writeBytes(nodeHost.joinPath(staging, RUNTIME_MANIFEST_NAME), manifestBytes)
            manifest.members.forEach { member ->
                val target = nodeHost.joinPath(staging, member.name)
                val bytes = extract(member.name)
                nodeHost.writeBytes(target, bytes)
                require(nodeHost.fileSize(target) == member.size && nodeHost.sha256(target) == member.sha256) {
                    "Extracted runtime member '${member.name}' is corrupt"
                }
                if (member.executable) nodeHost.makeExecutable(target)
            }
            check(validInstallation(staging, manifest, manifestBytes)) { "Staged runtime validation failed" }
            val hadInstalled = nodeHost.exists(installedDirectory)
            if (hadInstalled) nodeHost.move(installedDirectory, displaced)
            try {
                nodeHost.move(staging, installedDirectory)
            } catch (error: Throwable) {
                if (hadInstalled && nodeHost.exists(displaced)) nodeHost.move(displaced, installedDirectory)
                throw error
            }
            nodeHost.removePath(displaced)
        } finally {
            nodeHost.removePath(staging)
        }
        installedRuntime(installedDirectory, manifest)
    }

    private fun validInstallation(
        directory: String,
        manifest: RuntimeBundleManifest,
        manifestBytes: ByteArray,
    ): Boolean = runCatching {
        check(nodeHost.exists(directory) && nodeHost.isDirectory(directory) && !nodeHost.isSymbolicLink(directory))
        check(nodeHost.realPath(directory) == nodeHost.resolvePath(directory))
        val expectedNames = manifest.members.map { it.name }.toSet() + RUNTIME_MANIFEST_NAME
        check(nodeHost.list(directory).toSet() == expectedNames)
        check(nodeHost.readBytes(nodeHost.joinPath(directory, RUNTIME_MANIFEST_NAME)).contentEquals(manifestBytes))
        manifest.members.forEach { member ->
            val path = nodeHost.joinPath(directory, member.name)
            check(nodeHost.isFile(path) && !nodeHost.isSymbolicLink(path) && nodeHost.fileSize(path) == member.size)
            check(nodeHost.realPath(path) == nodeHost.resolvePath(path) && nodeHost.sha256(path) == member.sha256)
            if (member.executable) nodeHost.makeExecutable(path)
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
        require(manifest.members.size == expectedNames.size && manifest.members.map { it.name }.toSet() == expectedNames)
        require(zipMembers.size == expectedNames.size + 1 &&
            zipMembers.map { it.name }.toSet() == expectedNames + RUNTIME_MANIFEST_NAME)
        val zipSizes = zipMembers.associate { it.name to it.size }
        manifest.members.forEach { member ->
            require(member.name.isSafeName() && member.size > 0 &&
                member.sha256.matches(Regex("[0-9a-f]{64}")) && zipSizes[member.name] == member.size)
        }
        require(manifest.members.single { it.name == descriptor.appServerName }.sha256 == descriptor.appServerSha256)
        require(manifest.members.filter { it.executable }.map { it.name }.toSet() ==
            setOf(descriptor.appServerName, descriptor.supervisorName))
    }

    private fun validateDescriptor() {
        listOf(descriptor.libraryVersion, descriptor.appServerVersion, descriptor.target, descriptor.classifier)
            .forEach { require(it.matches(Regex("[A-Za-z0-9._-]+"))) { "Runtime identity is invalid" } }
        require(descriptor.appServerName.isSafeName() && descriptor.supervisorName.isSafeName())
        require(descriptor.appServerSha256.matches(Regex("[0-9a-f]{64}")))
    }

    private fun cleanStaging(versionDirectory: String) {
        nodeHost.list(versionDirectory)
            .filter {
                it.startsWith(".${descriptor.target}.staging-") ||
                    it.startsWith(".${descriptor.target}.corrupt-")
            }
            .forEach { nodeHost.removePath(nodeHost.joinPath(versionDirectory, it)) }
    }

    private fun installedRuntime(directory: String, manifest: RuntimeBundleManifest) = InstalledRuntime(
        appServer = nodeHost.joinPath(directory, descriptor.appServerName).toPath(),
        supervisor = nodeHost.joinPath(directory, descriptor.supervisorName).toPath(),
        supervisorSha256 = manifest.members.single { it.name == descriptor.supervisorName }.sha256,
    )

    private fun archiveName() =
        "codex-agent-runtime-desktop-${descriptor.libraryVersion}-${descriptor.classifier}.zip"

    private companion object {
        val installationLock = Mutex()
    }
}

private fun String.isSafeName(): Boolean =
    isNotBlank() && this != "." && this != ".." && '/' !in this && '\\' !in this && ':' !in this
