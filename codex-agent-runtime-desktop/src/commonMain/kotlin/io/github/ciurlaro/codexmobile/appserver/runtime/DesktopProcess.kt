package io.github.ciurlaro.codexmobile.appserver.runtime

import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.blackholeSink
import okio.buffer

data class DesktopCodexRuntimeConfiguration(
    val appServerExecutable: Path,
    val processSupervisorExecutable: Path,
    val processSupervisorSha256: String,
    val workingDirectory: Path,
)

class DesktopCodexRuntimeFactory private constructor(
    private val configuration: DesktopCodexRuntimeConfiguration,
    private val validateConfiguration: (DesktopCodexRuntimeConfiguration) -> Unit,
    private val startProcess: suspend (DesktopCodexRuntimeConfiguration) -> DesktopProcess,
) : CodexRuntimeFactory {
    constructor(configuration: DesktopCodexRuntimeConfiguration) : this(
        configuration,
        ::validateDesktopConfiguration,
        ::startDesktopProcess,
    )

    internal constructor(
        configuration: DesktopCodexRuntimeConfiguration,
        startProcess: suspend (DesktopCodexRuntimeConfiguration) -> DesktopProcess,
    ) : this(configuration, {}, startProcess)

    override fun create(): CodexRuntime = DesktopCodexRuntime(
        configuration,
        validateConfiguration,
        startProcess,
    )
}

internal interface DesktopProcess {
    fun readStdout(buffer: ByteArray): Int
    fun readStderr(buffer: ByteArray): Int
    fun write(bytes: ByteArray)
    fun waitForExit(): Int?
    fun close()
}

internal expect suspend fun startDesktopProcess(
    configuration: DesktopCodexRuntimeConfiguration,
): DesktopProcess

internal expect fun currentDesktopTarget(): String

private fun validateDesktopConfiguration(configuration: DesktopCodexRuntimeConfiguration) {
    val executable = configuration.appServerExecutable
    val supervisor = configuration.processSupervisorExecutable
    val workingDirectory = configuration.workingDirectory
    check(executable.isAbsolute) { "Codex app-server path must be absolute" }
    check(supervisor.isAbsolute) { "Process-supervisor path must be absolute" }
    check(workingDirectory.isAbsolute) { "Desktop working-directory path must be absolute" }
    check(executable.isRegularFile()) { "Codex app server does not exist" }
    check(supervisor.isRegularFile()) { "Codex process supervisor does not exist" }
    check(FileSystem.SYSTEM.metadataOrNull(supervisor)?.symlinkTarget == null) {
        "Codex process supervisor must not be a symbolic link"
    }
    check(runCatching { FileSystem.SYSTEM.canonicalize(supervisor) }.getOrNull() == supervisor) {
        "Codex process-supervisor path must be canonical"
    }
    check(FileSystem.SYSTEM.metadataOrNull(workingDirectory)?.isDirectory == true) {
        "Desktop working directory does not exist"
    }
    val distribution = desktopCodexDistribution(currentDesktopTarget())
    check(supervisor.name == distribution.supervisorExecutableName) {
        "Expected process supervisor '${distribution.supervisorExecutableName}' for ${distribution.target}"
    }
    check(executable.sha256() == distribution.binarySha256) {
        "Codex app-server checksum does not match ${distribution.target}"
    }
    check(configuration.processSupervisorSha256.matches(Regex("[0-9a-f]{64}"))) {
        "Process-supervisor SHA-256 must be 64 lowercase hexadecimal characters"
    }
    check(supervisor.sha256() == configuration.processSupervisorSha256) {
        "Codex process-supervisor checksum does not match ${distribution.target}"
    }
}

private fun Path.isRegularFile(): Boolean = FileSystem.SYSTEM.metadataOrNull(this)?.isRegularFile == true

internal fun Path.sha256(): String {
    val hashingSource = HashingSource.sha256(FileSystem.SYSTEM.source(this))
    val buffered = hashingSource.buffer()
    try {
        buffered.readAll(blackholeSink())
    } finally {
        buffered.close()
    }
    return hashingSource.hash.hex()
}
