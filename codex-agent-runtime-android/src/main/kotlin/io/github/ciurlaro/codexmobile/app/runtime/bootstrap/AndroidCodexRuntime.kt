package io.github.ciurlaro.codexmobile.app.runtime.bootstrap

import io.github.ciurlaro.codexmobile.appserver.AppServerProtocolIdentity
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexJsonLine
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexMobileAppServerRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeConfiguration
import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntimeEvent
import io.github.ciurlaro.codexmobile.appserver.runtime.JsonLineFramer
import io.github.ciurlaro.codexmobile.appserver.runtime.buildMinimalRuntimeEnvironment
import io.github.ciurlaro.codexmobile.appserver.runtime.installRuntimeLogPrivacyGuard
import io.github.ciurlaro.codexmobile.appserver.runtime.isRegularFile
import io.github.ciurlaro.codexmobile.appserver.runtime.prepareRuntimeCertificateBundle
import io.github.ciurlaro.codexmobile.appserver.runtime.sha256
import java.io.File
import java.lang.Process
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.Path

internal class AndroidCodexRuntime(
    private val configuration: CodexRuntimeConfiguration,
) : CodexRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val eventChannel = Channel<CodexRuntimeEvent>(EVENT_BUFFER_SIZE)
    private val sendMutex = Mutex()
    private val started = AtomicBoolean()
    private val closed = AtomicBoolean()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var proxy: LoopbackConnectProxy? = null

    @Volatile
    private var certificateBundle: Path? = null

    override val events: Flow<CodexRuntimeEvent> = eventChannel.receiveAsFlow()

    override suspend fun start() {
        check(!closed.get()) { "Codex runtime is closed" }
        check(started.compareAndSet(false, true)) { "Codex runtime was already started" }
        try {
            prepareAndVerifyRuntime()
            val codexHome = configuration.privateDirectory / CODEX_HOME_DIRECTORY
            FileSystem.SYSTEM.createDirectories(codexHome)
            FileSystem.SYSTEM.createDirectories(configuration.applicationDirectory)
            FileSystem.SYSTEM.createDirectories(configuration.temporaryDirectory)
            val preparedCertificateBundle =
                prepareRuntimeCertificateBundle(configuration.certificateSources, codexHome)
            certificateBundle = preparedCertificateBundle
            val logsDatabase = codexHome / LOGS_DATABASE_FILE
            sanitizeExistingRuntimeLogs(logsDatabase)

            val startedProxy = LoopbackConnectProxy(configuration.proxyPassword)
            proxy = startedProxy
            val environment = buildMinimalRuntimeEnvironment(
                platform = configuration.platformEnvironment,
                applicationDirectory = configuration.applicationDirectory,
                temporaryDirectory = configuration.temporaryDirectory,
                codexHome = codexHome,
                certificateBundle = preparedCertificateBundle,
                proxyUrl = startedProxy.url,
            )
            val startedProcess = withContext(Dispatchers.IO) {
                ProcessBuilder(configuration.executable.toString())
                    .directory(File(configuration.applicationDirectory.toString()))
                    .redirectErrorStream(false)
                    .apply {
                        environment().clear()
                        environment().putAll(environment)
                    }
                    .start()
            }
            process = startedProcess
            watch(startedProcess)
            awaitRuntimeLogPrivacyGuard(logsDatabase, startedProcess)
        } catch (error: Exception) {
            eventChannel.trySend(CodexRuntimeEvent.StartFailure(error.visibleRuntimeMessage()))
            closeResources()
            throw error
        }
    }

    override suspend fun send(line: CodexJsonLine) = sendMutex.withLock {
        val current = process
        check(current?.isAlive == true) { "Codex App Server is not running" }
        try {
            withContext(Dispatchers.IO) {
                current.outputStream.write(line.value.encodeToByteArray())
                current.outputStream.write('\n'.code)
                current.outputStream.flush()
            }
        } catch (error: Exception) {
            eventChannel.trySend(CodexRuntimeEvent.IoFailure(error.visibleRuntimeMessage()))
            throw error
        }
    }

    private fun prepareAndVerifyRuntime() {
        configuration.packagedRuntimeEnvironment?.let { environment ->
            val distribution = CodexMobileAppServerRuntime.DISTRIBUTION
            distribution.requireCompatible(
                AppServerProtocolIdentity.APP_SERVER_VERSION,
                AppServerProtocolIdentity.UPSTREAM_REVISION,
                AppServerProtocolIdentity.SCHEMA_SHA256,
                environment,
            )
            check(configuration.executable.sha256() == distribution.binarySha256) {
                "Bundled Codex runtime checksum is invalid"
            }
        }
        val executable = File(configuration.executable.toString())
        check(configuration.executable.isRegularFile() && executable.canExecute()) {
            "Bundled Codex runtime is missing or not executable"
        }
    }

    private fun watch(current: Process) {
        scope.launch {
            try {
                val framer = JsonLineFramer()
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    val count = current.inputStream.read(buffer)
                    if (count < 0) break
                    framer.accept(buffer, count) { line ->
                        eventChannel.send(CodexRuntimeEvent.Received(CodexJsonLine(line)))
                    }
                }
                framer.finish { line ->
                    eventChannel.send(CodexRuntimeEvent.Received(CodexJsonLine(line)))
                }
                if (!closed.get() && process === current) eventChannel.send(CodexRuntimeEvent.EndOfFile)
            } catch (error: Exception) {
                if (!closed.get() && process === current) {
                    eventChannel.send(CodexRuntimeEvent.IoFailure(error.visibleRuntimeMessage()))
                }
            }
        }
        scope.launch {
            runCatching {
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                while (current.errorStream.read(buffer) >= 0) Unit
            }
        }
        scope.launch {
            val code = runCatching { current.waitFor() }.getOrNull() ?: return@launch
            if (!closed.get() && process === current) {
                eventChannel.send(CodexRuntimeEvent.Exited(code))
            }
        }
    }

    private fun sanitizeExistingRuntimeLogs(database: Path) {
        if (!database.isRegularFile()) return
        configuration.sqliteDriver.open(database.toString()).use(::installRuntimeLogPrivacyGuard)
    }

    private suspend fun awaitRuntimeLogPrivacyGuard(database: Path, current: Process) {
        var lastFailure: Throwable? = null
        val installed = withTimeoutOrNull(LOG_DATABASE_TIMEOUT_MILLIS) {
            while (current.isAlive) {
                if (database.isRegularFile()) {
                    runCatching {
                        configuration.sqliteDriver.open(database.toString())
                            .use(::installRuntimeLogPrivacyGuard)
                    }.onSuccess {
                        return@withTimeoutOrNull true
                    }.onFailure {
                        lastFailure = it
                    }
                }
                delay(LOG_DATABASE_RETRY_MILLIS)
            }
            false
        }
        check(installed == true) {
            "Unable to prepare the private Codex log store" +
                (lastFailure?.message?.let { ": ${it.take(200)}" } ?: "")
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeResources()
        scope.cancel()
        eventChannel.close()
    }

    private fun closeResources() {
        val current = process
        process = null
        runCatching { current?.outputStream?.close() }
        runCatching { current?.inputStream?.close() }
        runCatching { current?.errorStream?.close() }
        if (current?.isAlive == true) current.destroy()
        val exited = runCatching {
            current?.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: true
        }.getOrDefault(false)
        if (current?.isAlive == true && !exited) {
            current.destroyForcibly()
            runCatching { current.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
        proxy?.close()
        proxy = null
        certificateBundle?.let { bundle ->
            runCatching { FileSystem.SYSTEM.delete(bundle, mustExist = false) }
        }
        certificateBundle = null
    }

    private fun Throwable.visibleRuntimeMessage(): String =
        message?.take(500)?.takeIf(String::isNotBlank) ?: this::class.simpleName ?: "Codex failure"

    private companion object {
        const val CODEX_HOME_DIRECTORY = "codex"
        const val LOGS_DATABASE_FILE = "logs_2.sqlite"
        const val LOG_DATABASE_TIMEOUT_MILLIS = 20_000L
        const val LOG_DATABASE_RETRY_MILLIS = 25L
        const val PROCESS_STOP_TIMEOUT_SECONDS = 2L
        const val STREAM_BUFFER_SIZE = 8 * 1024
        const val EVENT_BUFFER_SIZE = 64
    }
}
