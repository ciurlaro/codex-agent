package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

internal data class NodeLaunchSpec(
    val command: String,
    val arguments: Array<String>,
    val workingDirectory: String,
    val detached: Boolean,
    val target: String,
)

internal interface NodeOwnedProcess {
    val stdout: Flow<ByteArray>
    val exitCode: CompletableDeferred<Int>
    suspend fun write(line: String)
    fun close()
}

internal fun interface NodeProcessLauncher {
    suspend fun launch(spec: NodeLaunchSpec): NodeOwnedProcess
}

internal interface NodeHost : NodeProcessLauncher {
    val platform: String
    val architecture: String
    fun isAbsolute(path: String): Boolean
    fun realPath(path: String): String
    fun resolvePath(path: String): String
    fun baseName(path: String): String
    fun directoryName(path: String): String
    fun joinPath(parent: String, child: String): String
    fun isFile(path: String): Boolean
    fun isDirectory(path: String): Boolean
    fun requireExecutable(path: String)
    fun sha256(path: String): String
    fun environment(name: String): String?
    fun arguments(): List<String>
    fun exit(code: Int)
    fun error(message: String)
    fun temporaryDirectory(prefix: String): String
    fun writeExecutableFile(path: String, value: String)
    fun removeDirectory(path: String)
}

internal expect val nodeHost: NodeHost

internal object DefaultNodeProcessLauncher : NodeProcessLauncher {
    override suspend fun launch(spec: NodeLaunchSpec): NodeOwnedProcess = nodeHost.launch(spec)
}

internal fun <T> beginOwnedTermination(
    detached: Boolean,
    exitCode: CompletableDeferred<Int>,
    signal: (String) -> Unit,
    scheduleKill: (() -> Unit) -> T,
    clearKill: (T) -> Unit,
) {
    var forced = false
    fun forceKill() {
        if (!forced) {
            forced = true
            signal("SIGKILL")
        }
    }
    signal("SIGTERM")
    val timer = scheduleKill(::forceKill)
    exitCode.invokeOnCompletion {
        if (detached) forceKill()
        clearKill(timer)
    }
}

internal fun currentNodePlatform(): String = nodeHost.platform
internal fun currentNodeArchitecture(): String = nodeHost.architecture
internal fun nodeEnvironment(name: String): String? = nodeHost.environment(name)
internal fun nodeArguments(): List<String> = nodeHost.arguments()
internal fun nodeExit(code: Int): Unit = nodeHost.exit(code)
internal fun nodeTemporaryDirectory(prefix: String): String = nodeHost.temporaryDirectory(prefix)
internal fun nodeWriteFile(path: String, value: String): Unit = nodeHost.writeExecutableFile(path, value)
internal fun nodeRemoveDirectory(path: String): Unit = nodeHost.removeDirectory(path)
internal fun nodeJoinPath(parent: String, child: String): String = nodeHost.joinPath(parent, child)
internal fun nodeDirectoryName(path: String): String = nodeHost.directoryName(path)
internal fun nodeConsoleError(message: String): Unit = nodeHost.error(message)

internal fun currentNodeTarget(): String = when (currentNodePlatform() to currentNodeArchitecture()) {
    "darwin" to "arm64" -> "macosArm64"
    "darwin" to "x64" -> "macosX64"
    "linux" to "arm64" -> "linuxArm64"
    "linux" to "x64" -> "linuxX64"
    "win32" to "x64" -> "mingwX64"
    else -> error("Unsupported Node runtime target: ${currentNodePlatform()}/${currentNodeArchitecture()}")
}
