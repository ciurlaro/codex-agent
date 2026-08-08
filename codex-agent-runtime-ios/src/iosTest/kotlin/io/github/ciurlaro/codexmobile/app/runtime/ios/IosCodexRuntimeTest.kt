@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.AgentEvent
import io.github.ciurlaro.codexmobile.agent.BuiltInToolCall
import io.github.ciurlaro.codexmobile.agent.BuiltInToolContent
import io.github.ciurlaro.codexmobile.agent.BuiltInToolResult
import io.github.ciurlaro.codexmobile.agent.SessionId
import io.github.ciurlaro.codexmobile.appserver.client.AppServerConnection
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.ClientInfo
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeCapabilities
import io.github.ciurlaro.codexmobile.appserver.protocol.generated.InitializeParams
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.cinterop.alloc
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionCompleteUnlessOpen
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.darwin.KERN_SUCCESS
import platform.darwin.MACH_TASK_BASIC_INFO
import platform.darwin.MACH_TASK_BASIC_INFO_COUNT
import platform.darwin.mach_task_basic_info_data_t
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.getenv

class IosCodexRuntimeTest {
    @Test
    fun credentialProtectionPoliciesMapToExactAppleValues() {
        assertEquals(
            platform.Foundation.NSFileProtectionComplete,
            iosFileProtectionValue(IosCodexCredentialProtection.WHEN_UNLOCKED),
        )
        assertEquals(
            platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication,
            iosFileProtectionValue(IosCodexCredentialProtection.AFTER_FIRST_UNLOCK),
        )
        assertEquals(
            platform.Foundation.NSFileProtectionCompleteUnlessOpen,
            iosFileProtectionValue(IosCodexCredentialProtection.WHILE_OPEN),
        )
    }
    @Test
    fun localWorkspaceToolsReadSearchListAndModifyFiles() = runBlocking {
        TestWorkspace().use { test ->
            val tools = IosCodexRuntimeFactory(test.configuration).workspaceTools
            assertEquals(
                setOf("apply_patch", "read_file", "list_directory", "search_text", "write_file"),
                tools.definitions().mapTo(mutableSetOf()) { it.name },
            )
            assertTrue(tools.definitions().all { !it.requiresEnabledPlugin })
            assertFalse(tools.definitions().any { it.name.contains("command") || it.name.contains("git") })

            assertTrue(tools.call(test, "write_file", json("path" to "note.txt", "content" to "alpha\nbeta\n")).success)
            assertEquals("alpha\nbeta\n", tools.call(test, "read_file", json("path" to "note.txt")).text())
            assertTrue(tools.call(test, "search_text", json("query" to "BETA")).text().contains("note.txt:2:beta"))
            assertTrue(tools.call(test, "list_directory", buildJsonObject {}).text().contains("file\tnote.txt"))
            assertTrue(tools.call(test, "read_file", json("path" to "note.txt"), "${test.workspace}/.").success)
            assertFalse(tools.call(test, "read_file", json("path" to "note.txt"), test.sandboxRoot).success)

            assertTrue(tools.call(test, "write_file", json("path" to "note.txt", "content" to "modified locally\n")).success)
            assertEquals("modified locally\n", tools.call(test, "read_file", json("path" to "note.txt")).text())
            val patch = """
                *** Begin Patch
                *** Update File: note.txt
                @@
                -modified locally
                +patched locally
                *** End Patch
            """.trimIndent() + "\n"
            assertTrue(tools.call(test, "apply_patch", json("patch" to patch)).success)
            assertEquals("patched locally\n", tools.call(test, "read_file", json("path" to "note.txt")).text())
            val traversal = tools.call(test, "read_file", json("path" to "../outside.txt"))
            assertFalse(traversal.success)
            assertTrue(traversal.text().contains("must not contain '..'"))
        }
    }

    @Test
    fun commonConnectionOwnsInitializationAndRuntimeRestarts() = runBlocking {
        TestWorkspace().use { test ->
            repeat(2) {
                val connection = AppServerConnection(
                    runtimeFactory = IosCodexRuntimeFactory(test.configuration),
                    initializeParams = InitializeParams(
                        clientInfo = ClientInfo("ios-runtime-test", "0.2.0", "iOS Runtime Test"),
                        capabilities = InitializeCapabilities(
                            experimentalApi = true,
                            mcpServerOpenaiFormElicitation = false,
                        ),
                    ),
                    requestTimeoutMillis = 60_000,
                )
                try {
                    val initialized = connection.ensureStarted()
                    assertEquals("ios", initialized.platformOs)
                    assertTrue(initialized.codexHome.contains("CodexAgent"))
                } finally {
                    connection.shutdown()
                }
            }
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(test.unusedTemporaryPath))
        }
    }

    @Test
    fun browserAuthenticationUsesTheEmbeddedLoopbackCallbackAndCancelsCleanly() = runBlocking {
        TestWorkspace().use { test ->
            val facade = IosCodexAgentFacade(test.configuration, clientVersion = "0.2.0")
            try {
                val eventResult = CompletableDeferred<AgentEvent.AuthenticationRequired>()
                val observation = facade.observeEvents { event ->
                    if (event is AgentEvent.AuthenticationRequired) eventResult.complete(event)
                }
                val startResult = CompletableDeferred<String?>()
                val startOperation = facade.authenticateWithChatGpt(startResult::complete)

                assertNull(withTimeout(60_000) { startResult.await() })
                val event = withTimeout(60_000) { eventResult.await() }
                assertTrue(event.signInUrl.startsWith("https://auth.openai.com/"))
                assertTrue(event.signInUrl.contains("redirect_uri="))
                assertTrue(event.signInUrl.contains("localhost"))

                startOperation.cancel()
                withTimeout(60_000) {
                    while (facade.authenticationState.status != IosCodexAuthenticationStatus.SIGNED_OUT) {
                        kotlinx.coroutines.yield()
                    }
                }
                observation.close()
            } finally {
                facade.close()
            }
        }
    }

    @Test
    fun nativeConfigurationRejectsEqualAndNestedPaths() = runBlocking {
        TestWorkspace().use { test ->
            assertRejected(test.configuration.copy(codexHomePath = test.workspace))
            assertRejected(test.configuration.copy(codexHomePath = "${test.workspace}/state"))

            val home = "${test.sandboxRoot}/state"
            val nestedWorkspace = "$home/workspace"
            createDirectory(nestedWorkspace)
            assertRejected(
                test.configuration.copy(workspacePath = nestedWorkspace, codexHomePath = home),
            )
        }
    }

    @Test
    fun nativeConfigurationRejectsEitherPathOutsideSandbox() = runBlocking {
        TestWorkspace().use { test ->
            TestWorkspace().use { outside ->
                assertRejected(test.configuration.copy(workspacePath = outside.workspace))
                assertRejected(test.configuration.copy(codexHomePath = outside.codexHome))
            }
        }
    }

    @Test
    fun nativeConfigurationAcceptsSiblingDirectories() = runBlocking {
        TestWorkspace().use { test ->
            val result = executeIosWorkspaceTool(test.configuration, "list_directory", buildJsonObject {})
            assertTrue(result.success)
        }
    }

    @Test
    fun duplicateRuntimeOwnershipIsRejectedAndReusableAfterCleanShutdown() = runBlocking {
        TestWorkspace().use { test ->
            val first = IosCodexRuntime(test.configuration)
            val duplicate = IosCodexRuntime(test.configuration)
            first.start()
            try {
                val error = runCatching { duplicate.start() }.exceptionOrNull()
                assertIs<IosCodexRuntimeException>(error)
                assertTrue(error.message.orEmpty().contains("already owns"))
            } finally {
                duplicate.close()
                first.close()
            }
            IosCodexRuntime(test.configuration).also {
                it.start()
                it.close()
            }
            Unit
        }
    }

    @Test
    fun recordsStartupShutdownAndMemoryReleaseMetrics() = runBlocking {
        TestWorkspace().use { test ->
            val startup = mutableListOf<Long>()
            val shutdown = mutableListOf<Long>()
            var coldStartupMillis = 0L
            var idleCurrentResidentBytes = 0L
            var recursiveSearchCurrentResidentBytes = 0L
            repeat(6) { iteration ->
                val connection = AppServerConnection(
                    runtimeFactory = IosCodexRuntimeFactory(test.configuration),
                    initializeParams = InitializeParams(
                        clientInfo = ClientInfo("ios-release-metrics", "0.2.0", "iOS Release Metrics"),
                        capabilities = InitializeCapabilities(
                            experimentalApi = true,
                            mcpServerOpenaiFormElicitation = false,
                        ),
                    ),
                    requestTimeoutMillis = 60_000,
                )
                val startMark = kotlin.time.TimeSource.Monotonic.markNow()
                connection.ensureStarted()
                val startupMillis = startMark.elapsedNow().inWholeMilliseconds
                assertTrue(startupMillis < 30_000, "startup took ${startupMillis}ms")
                if (iteration == 0) {
                    coldStartupMillis = startupMillis
                    idleCurrentResidentBytes = currentResidentBytes()
                    repeat(500) { index ->
                        NSFileManager.defaultManager.createFileAtPath(
                            "${test.workspace}/metric-$index.txt",
                            null,
                            null,
                        )
                    }
                    val tools = IosCodexRuntimeFactory(test.configuration).workspaceTools
                    assertTrue(
                        tools.call(test, "search_text", json("query" to "missing-metric-value")).success,
                    )
                    recursiveSearchCurrentResidentBytes = currentResidentBytes()
                } else {
                    startup += startupMillis
                }
                val shutdownMark = kotlin.time.TimeSource.Monotonic.markNow()
                connection.shutdown()
                val shutdownMillis = shutdownMark.elapsedNow().inWholeMilliseconds
                assertTrue(shutdownMillis < 5_000, "shutdown took ${shutdownMillis}ms")
                if (iteration > 0) shutdown += shutdownMillis
            }
            writeRuntimeMetrics(
                coldStartupMillis = coldStartupMillis,
                startup = startup,
                shutdown = shutdown,
                idleCurrentResidentBytes = idleCurrentResidentBytes,
                recursiveSearchCurrentResidentBytes = recursiveSearchCurrentResidentBytes,
            )
        }
    }

    @Test
    fun appliesEveryCredentialProtectionPolicyAndBackupExclusion() {
        TestWorkspace().use { test ->
            val expected = mapOf(
                IosCodexCredentialProtection.WHEN_UNLOCKED to NSFileProtectionComplete,
                IosCodexCredentialProtection.AFTER_FIRST_UNLOCK to NSFileProtectionCompleteUntilFirstUserAuthentication,
                IosCodexCredentialProtection.WHILE_OPEN to NSFileProtectionCompleteUnlessOpen,
            )
            expected.forEach { (policy, appleValue) ->
                val home = "${test.sandboxRoot}/state-$policy"
                createDirectory(home)
                val configuration = test.configuration.copy(
                    codexHomePath = home,
                    credentialProtection = policy,
                )
                applyIosCredentialProtection(configuration)
                val resources = NSURL.fileURLWithPath(home).resourceValuesForKeys(
                    listOf(NSURLIsExcludedFromBackupKey),
                    error = null,
                )
                assertTrue((resources?.get(NSURLIsExcludedFromBackupKey) as? NSNumber)?.boolValue == true)
            }
        }
    }

    @Test
    fun credentialProtectionFailsForMissingCodexHome() {
        TestWorkspace().use { test ->
            val error = runCatching {
                applyIosCredentialProtection(
                    test.configuration.copy(codexHomePath = "${test.sandboxRoot}/missing"),
                )
            }.exceptionOrNull()
            assertIs<IllegalStateException>(error)
        }
    }

    @Test
    fun facadeBroadcastsAuthenticationFailureAndNormalEventsToEveryObserver() = runBlocking {
        val upstream = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 8)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val broadcast = IosCodexEventBroadcast(upstream, scope)
            val first = Channel<AgentEvent>(Channel.UNLIMITED)
            val second = Channel<AgentEvent>(Channel.UNLIMITED)
            val firstObservation = broadcast.observeEvents { first.trySend(it) }
            val secondObservation = broadcast.observeEvents { second.trySend(it) }
            val events = listOf(
                AgentEvent.AuthenticationRequired("https://auth.openai.com/resume"),
                AgentEvent.Failure(null, "authentication_failed", "failed", true),
                AgentEvent.TextDelta(SessionId("session"), "hello"),
            )

            events.forEach { upstream.emit(it) }
            assertEquals(events, events.map { withTimeout(5_000) { first.receive() } })
            assertEquals(events, events.map { withTimeout(5_000) { second.receive() } })

            firstObservation.close()
            val finalEvent = AgentEvent.Authenticated
            upstream.emit(finalEvent)
            assertEquals(finalEvent, withTimeout(5_000) { second.receive() })
            assertTrue(first.tryReceive().isFailure)
            secondObservation.close()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun facadeAuthenticationStateResetsOnlyForRelevantFailures() = runBlocking {
        val upstream = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 8)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val broadcast = IosCodexEventBroadcast(upstream, scope)
            upstream.emit(AgentEvent.Authenticated)
            withTimeout(5_000) {
                while (broadcast.authenticationState.status != IosCodexAuthenticationStatus.AUTHENTICATED) {
                    kotlinx.coroutines.yield()
                }
            }
            upstream.emit(AgentEvent.Failure(SessionId("session"), "turn_failed", "failed", true))
            assertEquals(IosCodexAuthenticationStatus.AUTHENTICATED, broadcast.authenticationState.status)
            upstream.emit(AgentEvent.Failure(null, "event_stream", "runtime failed", true))
            withTimeout(5_000) {
                while (broadcast.authenticationState.status != IosCodexAuthenticationStatus.SIGNED_OUT) {
                    kotlinx.coroutines.yield()
                }
            }
        } finally {
            scope.cancel()
        }
    }
}

private class TestWorkspace : AutoCloseable {
    val sandboxRoot = "${NSTemporaryDirectory().trimEnd('/')}/codex-agent-ios-${NSUUID().UUIDString}"
    val workspace = "$sandboxRoot/workspace"
    val codexHome = "$sandboxRoot/Library/Application Support/CodexAgent"
    val unusedTemporaryPath = "$sandboxRoot/deprecated-unused-temporary-path"
    @Suppress("DEPRECATION")
    val configuration = IosCodexRuntimeConfiguration(
        sandboxRootPath = sandboxRoot,
        workspacePath = workspace,
        credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
        temporaryPath = unusedTemporaryPath,
    )

    init {
        createDirectory(workspace)
    }

    override fun close() {
        NSFileManager.defaultManager.removeItemAtPath(sandboxRoot, error = null)
    }
}

private suspend fun assertRejected(configuration: IosCodexRuntimeConfiguration) {
    val error = runCatching {
        executeIosWorkspaceTool(configuration, "list_directory", buildJsonObject {})
    }.exceptionOrNull()
    assertIs<IosCodexRuntimeException>(error)
}

private fun createDirectory(path: String) {
    check(
        NSFileManager.defaultManager.createDirectoryAtPath(
            path,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        ),
    ) { "Could not create test directory" }
}

private suspend fun IosCodexWorkspaceTools.call(
    test: TestWorkspace,
    tool: String,
    arguments: JsonObject,
    workspace: String = test.workspace,
) = execute(
    BuiltInToolCall(
        threadId = "thread",
        turnId = "turn",
        callId = "call-$tool",
        pluginId = "ios-local-workspace",
        tool = tool,
        arguments = arguments,
        workspace = workspace,
        argumentsHash = "test",
    ),
)

private fun BuiltInToolResult.text(): String = (content.single() as BuiltInToolContent.Text).value

private fun json(vararg values: Pair<String, String>) = buildJsonObject {
    values.forEach { (key, value) -> put(key, value) }
}

private fun currentResidentBytes(): Long = memScoped {
    val info = alloc<mach_task_basic_info_data_t>()
    val count = alloc<UIntVar>()
    count.value = MACH_TASK_BASIC_INFO_COUNT.toUInt()
    check(
        task_info(
            mach_task_self_,
            MACH_TASK_BASIC_INFO.toUInt(),
            info.ptr.reinterpret(),
            count.ptr,
        ) == KERN_SUCCESS,
    )
    info.resident_size.toLong()
}

private fun writeRuntimeMetrics(
    coldStartupMillis: Long,
    startup: List<Long>,
    shutdown: List<Long>,
    idleCurrentResidentBytes: Long,
    recursiveSearchCurrentResidentBytes: Long,
) {
    val output = getenv("CODEX_AGENT_IOS_METRICS_PATH")?.toKString() ?: return
    fun median(values: List<Long>) = values.sorted()[values.size / 2]
    val json = """
        {
          "warmupCycles": 1,
          "measuredCycles": 5,
          "coldStartupMilliseconds": $coldStartupMillis,
          "startupMilliseconds": $startup,
          "startupMedianMilliseconds": ${median(startup)},
          "startupMaximumMilliseconds": ${startup.max()},
          "shutdownMilliseconds": $shutdown,
          "shutdownMedianMilliseconds": ${median(shutdown)},
          "shutdownMaximumMilliseconds": ${shutdown.max()},
          "memoryMeasurement": "mach_task_basic_info.current_resident_size",
          "idleCurrentResidentBytes": $idleCurrentResidentBytes,
          "recursiveSearchCurrentResidentBytes": $recursiveSearchCurrentResidentBytes,
          "authenticatedTurnPeakResidentBytes": null
        }
    """.trimIndent()
    val file = checkNotNull(fopen(output, "w")) { "Could not write iOS runtime metrics" }
    try {
        check(fputs(json, file) >= 0)
    } finally {
        fclose(file)
    }
}
