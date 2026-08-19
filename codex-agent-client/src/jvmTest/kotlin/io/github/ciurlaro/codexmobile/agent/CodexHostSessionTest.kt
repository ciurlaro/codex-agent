package io.github.ciurlaro.codexmobile.agent

import io.github.ciurlaro.codexmobile.appserver.runtime.CodexRuntime
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class CodexHostTest {
    @Test
    fun staleCloseDoesNotDetachANewerHandleForTheSameConversationId(): Unit = runBlocking {
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start", "thread/resume" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-shared") }
                })
                "thread/read" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-shared") }
                })
            }
        }
        val workspace = CodexWorkspace("/workspace")
        val host = CodexHost(
            FakePlatformSupport(
                FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace)),
                ArrayDeque<FakeCodexRuntime>().apply { add(runtime) },
            ),
            TEST_CLIENT_INFO,
        )
        try {
            host.start()
            val agent = readyAgent(host)
            val first = agent.openConversation()
            val id = checkNotNull(first.state.value.conversationId)
            val replacement = agent.openConversation(id)
            assertSame(replacement, agent.activeConversation.value)
            first.close()

            runtime.request(990, "item/commandExecution/requestApproval", hostApprovalRequest(id.value, "turn-shared"))
            val pending = withTimeout(1_000) { agent.interactionState.first { it.pending.isNotEmpty() } }
            assertEquals(id, pending.pending.single().conversationId)
        } finally {
            host.close()
        }
    }

    @Test
    fun newerWorkspaceSelectionIsTheLastPersistedSelection(): Unit = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val persisted = mutableListOf<String>()
        val store = object : CodexWorkspaceStore {
            override suspend fun select(selection: CodexWorkspaceSelection): CodexWorkspaceResolution {
                val path = (selection as CodexPathWorkspaceSelection).path
                if (path == "/workspace/a") {
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                }
                persisted += path
                return CodexWorkspaceResolution.Available(CodexWorkspace(path))
            }

            override suspend fun restore() = CodexWorkspaceResolution.SelectionRequired(
                CodexWorkspaceSelectionReason.NOT_SELECTED,
                "Select",
            )

            override suspend fun clear() = Unit
        }
        val runtime = FakeCodexRuntime { message, server ->
            if (message.method == "initialize") server.respond(message.id, buildJsonObject {})
        }
        val platform = object : CodexPlatform {
            override val authorizationBrowser =
                CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }
            override val workspaceStore = store

            override suspend fun prepare(workspace: CodexWorkspace) = PreparedCodexRuntime(
                runtimeFactory = { runtime },
                workspacePath = workspace.path,
                features = CodexRuntimeFeature.entries.toSet(),
            )
        }
        val host = CodexHost(platform, TEST_CLIENT_INFO)
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                host.selectWorkspace(CodexPathWorkspaceSelection("/workspace/a"))
            }
            firstStarted.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                host.selectWorkspace(CodexPathWorkspaceSelection("/workspace/b"))
            }
            releaseFirst.complete(Unit)
            assertFailsWith<CancellationException> { first.await() }
            second.await()

            assertEquals(listOf("/workspace/a", "/workspace/b"), persisted)
            assertEquals("/workspace/b", readyAgent(host).workspace.path)
        } finally {
            host.close()
        }
    }

    @Test
    fun unavailablePreparedFeaturesFailBeforeTheirRpc(): Unit = runBlocking {
        val requestedMethods = mutableListOf<String>()
        var unsupportedStartupFlags: List<Boolean>? = null
        var startupInstructions = ""
        var hasShellPolicy = true
        val runtime = FakeCodexRuntime { message, server ->
            message.method?.let(requestedMethods::add)
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    val config = message.params.requiredObject("config")
                    val flags = config.requiredObject("features")
                    unsupportedStartupFlags = listOf(
                        "shell_tool",
                        "apps",
                        "enable_mcp_apps",
                        "plugins",
                        "hooks",
                    ).map { flags.getValue(it).jsonPrimitive.content.toBoolean() }
                    startupInstructions = message.params.requiredString("developerInstructions")
                    hasShellPolicy = "shell_environment_policy" in config
                    putJsonObject("thread") { put("id", "thread-limited") }
                })
            }
        }
        val workspace = CodexWorkspace("/workspace")
        val platform = object : CodexPlatform {
            override val authorizationBrowser =
                CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }
            override val workspaceStore = FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace))

            override suspend fun prepare(workspace: CodexWorkspace) = PreparedCodexRuntime(
                runtimeFactory = { runtime },
                workspacePath = workspace.path,
                features = emptySet(),
            )
        }
        val host = CodexHost(platform, TEST_CLIENT_INFO)
        try {
            host.start()
            val agent = readyAgent(host)
            val conversation = agent.openConversation()
            val failures = listOf(
                assertFailsWith<CodexOperationException> { agent.listSkills() },
                assertFailsWith<CodexOperationException> { agent.listHooks() },
                assertFailsWith<CodexOperationException> { agent.listPlugins() },
                assertFailsWith<CodexOperationException> { agent.listConnectors() },
                assertFailsWith<CodexOperationException> { agent.listMcpServers() },
                assertFailsWith<CodexOperationException> { conversation.runShellCommand("pwd") },
                assertFailsWith<CodexOperationException> {
                    conversation.send(
                        AgentTurnRequest(
                            "review",
                            invocations = listOf(AgentInvocation.Skill("Review", "/skills/review/SKILL.md")),
                        ),
                    )
                },
                assertFailsWith<CodexOperationException> {
                    conversation.send(
                        AgentTurnRequest(
                            "use plugin",
                            invocations = listOf(AgentInvocation.Plugin("Drive", "plugin://drive@catalog")),
                        ),
                    )
                },
            )
            assertTrue(failures.all { it.failure.code == "unsupported_feature" && !it.failure.isRecoverable })
            assertEquals(AgentConversationStatus.READY, conversation.state.value.status)
            assertEquals(listOf("initialize", "initialized", "thread/start"), requestedMethods)
            assertEquals(List(5) { false }, unsupportedStartupFlags)
            assertFalse(hasShellPolicy)
            assertFalse("shell" in startupInstructions.lowercase())
            assertFalse("plugin" in startupInstructions.lowercase())
        } finally {
            host.close()
        }
    }

    @Test
    fun ownedHostUsesClientIdentityPreparedPathAndDeclaredFeatures(): Unit = runBlocking {
        val paths = mutableListOf<String>()
        var initializedClient: Triple<String, String, String>? = null
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> {
                    val info = message.params.requiredObject("clientInfo")
                    initializedClient = Triple(
                        info.requiredString("name"),
                        info.requiredString("title"),
                        info.requiredString("version"),
                    )
                    server.respond(message.id, buildJsonObject {})
                }
                "thread/start" -> {
                    paths += message.params.requiredString("cwd")
                    server.respond(message.id, buildJsonObject {
                        putJsonObject("thread") { put("id", "thread-prepared") }
                    })
                }
                "skills/list" -> {
                    paths += message.params.requiredArray("cwds").single().jsonPrimitive.content
                    server.respond(message.id, buildJsonObject { putJsonArray("data") {} })
                }
                "hooks/list" -> {
                    paths += message.params.requiredArray("cwds").single().jsonPrimitive.content
                    server.respond(message.id, buildJsonObject { putJsonArray("data") {} })
                }
                "plugin/list", "plugin/installed" -> {
                    paths += message.params.requiredArray("cwds").single().jsonPrimitive.content
                    server.respond(message.id, buildJsonObject {
                        putJsonArray("marketplaces") {}
                        putJsonArray("marketplaceLoadErrors") {}
                    })
                }
            }
        }
        val logicalWorkspace = CodexWorkspace("/selected", "Selected")
        val platform = object : CodexPlatform {
            override val authorizationBrowser =
                CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }
            override val workspaceStore = FakeWorkspaceStore(CodexWorkspaceResolution.Available(logicalWorkspace))

            override suspend fun prepare(workspace: CodexWorkspace) = PreparedCodexRuntime(
                runtimeFactory = { runtime },
                workspacePath = "/prepared",
                features = CodexRuntimeFeature.entries.toSet(),
            )
        }
        val host = CodexHost(platform, TEST_CLIENT_INFO)
        try {
            host.start()
            val agent = readyAgent(host)
            assertEquals(logicalWorkspace, agent.workspace)
            assertEquals(CodexRuntimeFeature.entries.toSet(), agent.features)
            val conversation = agent.openConversation()
            assertFalse(host.state is MutableStateFlow<*>)
            assertFalse(agent.authenticationState is MutableStateFlow<*>)
            assertFalse(agent.interactionState is MutableStateFlow<*>)
            assertFalse(agent.integrationAuthorizationState is MutableStateFlow<*>)
            assertFalse(agent.activeConversation is MutableStateFlow<*>)
            assertFalse(conversation.state is MutableStateFlow<*>)
            agent.listSkills()
            agent.listHooks()
            agent.listPlugins()
            assertTrue(paths.size >= 5)
            assertTrue(paths.all { it == "/prepared" })
            assertEquals(
                Triple(TEST_CLIENT_INFO.name, TEST_CLIENT_INFO.title, TEST_CLIENT_INFO.version),
                initializedClient,
            )
        } finally {
            host.close()
        }
        assertTrue(runtime.allClientStreamsClosed())
    }

    @Test
    fun typedExtensionMutationsUseOnlyTheirSnapshotIdentity(): Unit = runBlocking {
        val mutations = mutableListOf<String>()
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "skills/config/write" -> {
                    mutations += "skill:${message.params.requiredString("path")}:${message.params.requiredBoolean("enabled")}"
                    server.respond(message.id, buildJsonObject { put("effectiveEnabled", true) })
                }
                "config/batchWrite" -> {
                    val value = message.params.requiredArray("edits").single().jsonObject.requiredObject("value")
                    val (key, state) = value.entries.single()
                    val snapshot = state.jsonObject
                    mutations += if ("trusted_hash" in snapshot) {
                        "trust:$key:${snapshot.requiredString("trusted_hash")}"
                    } else {
                        "hook:$key:${snapshot.requiredBoolean("enabled")}"
                    }
                    server.respond(message.id, buildJsonObject {
                        put("filePath", "/tmp/config.toml")
                        put("status", "ok")
                        put("version", "1")
                    })
                }
                "config/value/write" -> {
                    mutations += "plugin:${message.params.requiredString("keyPath")}:${message.params.getValue("value").jsonPrimitive.content}"
                    server.respond(message.id, buildJsonObject {})
                }
            }
        }
        val workspace = CodexWorkspace("/workspace")
        val host = CodexHost(
            FakePlatformSupport(
                FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace)),
                ArrayDeque<FakeCodexRuntime>().apply { add(runtime) },
            ),
            TEST_CLIENT_INFO,
        )
        try {
            host.start()
            val agent = readyAgent(host)
            agent.setSkillEnabled(
                AgentSkill("review", "Review", "Review", "/skills/typed/SKILL.md", AgentSkillScope.USER, false),
                true,
            )
            val hook = AgentHook(
                key = "typed-hook",
                currentHash = "hash-from-snapshot",
                isEnabled = false,
                eventName = "afterTurn",
                handlerType = "command",
                isManaged = false,
                source = "user",
                sourcePath = "/hooks/typed.json",
                timeoutSeconds = 10,
                trustStatus = AgentHookTrustStatus.UNTRUSTED,
            )
            agent.setHookEnabled(hook, true)
            agent.trustHook(hook)
            agent.setPluginEnabled(
                AgentPluginSummary(
                    reference = AgentPluginReference("typed-plugin", "typed", "catalog", remotePluginId = "remote"),
                    displayName = "Typed",
                    description = "Typed",
                    isInstalled = true,
                    isEnabled = false,
                    installPolicy = AgentPluginInstallPolicy.AVAILABLE,
                    authPolicy = AgentPluginAuthPolicy.ON_USE,
                    isAvailable = true,
                ),
                true,
            )
            assertEquals(
                listOf(
                    "skill:/skills/typed/SKILL.md:true",
                    "hook:typed-hook:true",
                    "trust:typed-hook:hash-from-snapshot",
                    "plugin:plugins.typed-plugin.enabled:true",
                ),
                mutations,
            )
        } finally {
            host.close()
        }
    }

    @Test
    fun limitedRuntimeKeepsLocalToolsAndOmitsPluginDependentTools(): Unit = runBlocking {
        var dynamicTools = emptyList<String>()
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> {
                    dynamicTools = message.params.requiredArray("dynamicTools")
                        .map { it.jsonObject.requiredString("name") }
                    server.respond(message.id, buildJsonObject {
                        putJsonObject("thread") { put("id", "thread-local-tools") }
                    })
                }
            }
        }
        val workspace = CodexWorkspace("/workspace")
        val provider = object : CodexToolProvider {
            override fun definitions() = listOf(
                BuiltInToolDefinition("local", "local_read", "Read", buildJsonObject {}, requiresEnabledPlugin = false),
                BuiltInToolDefinition("plugin", "plugin_read", "Read", buildJsonObject {}),
            )

            override suspend fun execute(
                call: BuiltInToolCall,
                context: CodexToolExecutionContext,
            ) = BuiltInToolResult.text("ok")
        }
        val platform = object : CodexPlatform {
            override val authorizationBrowser =
                CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }
            override val workspaceStore = FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace))
            override suspend fun prepare(workspace: CodexWorkspace) = PreparedCodexRuntime(
                runtimeFactory = { runtime },
                workspacePath = workspace.path,
                features = setOf(CodexRuntimeFeature.SKILLS),
                toolProvider = provider,
            )
        }
        val host = CodexHost(platform, TEST_CLIENT_INFO)
        try {
            host.start()
            readyAgent(host).openConversation()
            assertEquals(listOf("local_read"), dynamicTools)
        } finally {
            host.close()
        }
    }

    @Test
    fun hostClosePreservesPresentationFailureAfterClosingTheRuntime(): Unit = runBlocking {
        val presentationCloseStarted = CountDownLatch(1)
        val releasePresentationClose = CountDownLatch(1)
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.respond(message.id, buildJsonObject {
                    put("account", kotlinx.serialization.json.JsonNull)
                    put("requiresOpenaiAuth", true)
                })
                "account/login/start" -> server.respond(message.id, buildJsonObject {
                    put("type", "chatgpt")
                    put("loginId", "login-close")
                    put("authUrl", "https://auth.openai.com/oauth?state=login-close")
                })
            }
        }
        val workspace = CodexWorkspace("/workspace")
        val platform = object : CodexPlatform {
            override val authorizationBrowser = CodexAuthorizationBrowser {
                CodexAuthorizationPresentation {
                    presentationCloseStarted.countDown()
                    check(releasePresentationClose.await(5, TimeUnit.SECONDS))
                    error("presentation close failed")
                }
            }
            override val workspaceStore = FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace))
            override suspend fun prepare(workspace: CodexWorkspace) = PreparedCodexRuntime(
                runtimeFactory = { runtime },
                workspacePath = workspace.path,
                features = CodexRuntimeFeature.entries.toSet(),
            )
        }
        val host = CodexHost(platform, TEST_CLIENT_INFO)
        host.start()
        val agent = readyAgent(host)
        agent.authenticate()
        withTimeout(1_000) { agent.authenticationState.first { it.pendingSignInUrl != null } }

        val firstClose = async(Dispatchers.Default) { runCatching { host.close() } }
        assertTrue(presentationCloseStarted.await(1, TimeUnit.SECONDS))
        val secondClose = async(Dispatchers.Default) { runCatching { host.close() } }
        yield()
        assertFalse(secondClose.isCompleted)
        releasePresentationClose.countDown()
        val firstFailure = assertIs<CodexOperationException>(firstClose.await().exceptionOrNull())
        val secondFailure = assertIs<CodexOperationException>(secondClose.await().exceptionOrNull())
        assertEquals("host_close_failed", firstFailure.failure.code)
        assertEquals(firstFailure.failure, secondFailure.failure)
        assertEquals("presentation close failed", firstFailure.cause?.message)
        assertEquals("presentation close failed", secondFailure.cause?.message)
        assertIs<CodexHostState.Closed>(host.state.value)
        assertTrue(runtime.allClientStreamsClosed())
    }

    @Test
    fun hostCloseWaitsForAnInFlightBrowserPresentationAndClosesIt(): Unit = runBlocking {
        val browserOpenStarted = CountDownLatch(1)
        val releaseBrowserOpen = CountDownLatch(1)
        val presentationCloseCount = AtomicInteger()
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "account/read" -> server.respond(message.id, buildJsonObject {
                    put("account", kotlinx.serialization.json.JsonNull)
                    put("requiresOpenaiAuth", true)
                })
                "account/login/start" -> server.respond(message.id, buildJsonObject {
                    put("type", "chatgpt")
                    put("loginId", "login-blocked-browser")
                    put("authUrl", "https://auth.openai.com/oauth?state=login-blocked-browser")
                })
            }
        }
        val workspace = CodexWorkspace("/workspace")
        val platform = object : CodexPlatform {
            override val authorizationBrowser = CodexAuthorizationBrowser {
                browserOpenStarted.countDown()
                check(releaseBrowserOpen.await(5, TimeUnit.SECONDS))
                CodexAuthorizationPresentation { presentationCloseCount.incrementAndGet() }
            }
            override val workspaceStore = FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace))
            override suspend fun prepare(workspace: CodexWorkspace) = PreparedCodexRuntime(
                runtimeFactory = { runtime },
                workspacePath = workspace.path,
                features = CodexRuntimeFeature.entries.toSet(),
            )
        }
        val host = CodexHost(platform, TEST_CLIENT_INFO)
        host.start()
        readyAgent(host).authenticate()
        assertTrue(browserOpenStarted.await(1, TimeUnit.SECONDS))

        val closing = async(Dispatchers.Default) { host.close() }
        yield()
        assertFalse(closing.isCompleted)
        releaseBrowserOpen.countDown()
        closing.await()

        assertEquals(1, presentationCloseCount.get())
        assertTrue(runtime.allClientStreamsClosed())
    }

    @Test
    fun duplicateConversationCloseReportsTheSameDetachFailure(): Unit = runBlocking {
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-close-failure") }
                })
            }
        }
        val workspace = CodexWorkspace("/workspace")
        val platform = object : CodexPlatform {
            override val authorizationBrowser = CodexAuthorizationBrowser {
                CodexAuthorizationPresentation { error("elicitation presentation close failed") }
            }
            override val workspaceStore = FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace))
            override suspend fun prepare(workspace: CodexWorkspace) = PreparedCodexRuntime(
                runtimeFactory = { runtime },
                workspacePath = workspace.path,
                features = CodexRuntimeFeature.entries.toSet(),
            )
        }
        val host = CodexHost(platform, TEST_CLIENT_INFO)
        try {
            host.start()
            val agent = readyAgent(host)
            val conversation = agent.openConversation()
            runtime.request(905, "mcpServer/elicitation/request", buildJsonObject {
                put("serverName", "example")
                put("threadId", "thread-close-failure")
                put("elicitationId", "elicitation-close")
                put("message", "Authorize")
                put("url", "https://accounts.example.com/authorize")
                put("turnId", "turn-close")
                put("mode", "url")
            })
            val pending = withTimeout(1_000) {
                agent.interactionState.first { it.pending.isNotEmpty() }.pending.single()
            }
            agent.openElicitationUrl(assertIs<AgentPendingElicitation>(pending))

            val first = assertFailsWith<CodexOperationException> { conversation.close() }
            val second = assertFailsWith<CodexOperationException> { conversation.close() }
            assertEquals("close_failed", first.failure.code)
            assertEquals(first.failure, second.failure)
            assertEquals("elicitation presentation close failed", first.cause?.message)
            assertEquals("elicitation presentation close failed", second.cause?.message)
            assertEquals(AgentConversationStatus.CLOSED, conversation.state.value.status)
            assertNull(agent.activeConversation.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun readyAgentPublishesReturnedConversationAndResolvesTypedApproval(): Unit = runBlocking {
        val approvalResponse = CompletableDeferred<String>()
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-typed") }
                })
                null -> if (message.id == 903L) {
                    approvalResponse.complete(
                        message.objectValue.getValue("result").jsonObject
                            .getValue("decision").jsonPrimitive.content,
                    )
                }
            }
        }
        val workspace = CodexWorkspace("/workspace", "Workspace")
        val host = CodexHost(
            FakePlatformSupport(
                FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace)),
                ArrayDeque<FakeCodexRuntime>().apply { add(runtime) },
            ),
            this,
            TEST_CLIENT_INFO,
        )
        try {
            host.start()
            val agent = readyAgent(host)
            assertEquals(workspace, agent.workspace)
            assertEquals(AgentAuthenticationStatus.SIGNED_OUT, agent.authenticationState.value.status)
            val conversation = agent.openConversation()
            assertSame(conversation, agent.activeConversation.value)

            runtime.request(
                903,
                "item/commandExecution/requestApproval",
                hostApprovalRequest("thread-typed", "turn-typed"),
            )
            val approval = withTimeout(1_000) {
                agent.interactionState.first { it.pending.isNotEmpty() }.pending.single()
            }
            agent.resolveApproval(assertIs<AgentPendingApproval>(approval), AgentApprovalDecision.ACCEPT)
            assertEquals("accept", withTimeout(1_000) { approvalResponse.await() })
            conversation.close()
            conversation.close()
            assertNull(agent.activeConversation.value)
        } finally {
            host.close()
        }
    }

    @Test
    fun cancellationDoesNotLeaveHostInARestoreOrPrepareTransition(): Unit = runBlocking {
        val restoringHost = CodexHost(
            object : CodexPlatform {
                override val authorizationBrowser =
                    CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }
                override val workspaceStore = object : CodexWorkspaceStore {
                    override suspend fun select(selection: CodexWorkspaceSelection) = awaitCancellation()
                    override suspend fun restore(): CodexWorkspaceResolution = awaitCancellation()
                    override suspend fun clear() = Unit
                }

                override suspend fun prepare(workspace: CodexWorkspace): PreparedCodexRuntime =
                    error("prepare must not be reached")
            },
            this,
            TEST_CLIENT_INFO,
        )
        val restoring = async(start = CoroutineStart.UNDISPATCHED) { restoringHost.start() }
        assertIs<CodexHostState.Restoring>(restoringHost.state.value)
        restoring.cancelAndJoin()
        assertIs<CodexHostState.Failed>(restoringHost.state.value)
        restoringHost.close()

        val workspace = CodexWorkspace("/workspace", "Workspace")
        val preparingHost = CodexHost(
            object : CodexPlatform {
                override val authorizationBrowser =
                    CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }
                override val workspaceStore = object : CodexWorkspaceStore {
                    override suspend fun select(selection: CodexWorkspaceSelection) =
                        CodexWorkspaceResolution.Available(workspace)
                    override suspend fun restore() = CodexWorkspaceResolution.Available(workspace)
                    override suspend fun clear() = Unit
                }

                override suspend fun prepare(workspace: CodexWorkspace): PreparedCodexRuntime =
                    awaitCancellation()
            },
            this,
            TEST_CLIENT_INFO,
        )
        val preparing = async(start = CoroutineStart.UNDISPATCHED) { preparingHost.start() }
        assertIs<CodexHostState.Preparing>(preparingHost.state.value)
        preparing.cancelAndJoin()
        assertIs<CodexHostState.Failed>(preparingHost.state.value)
        preparingHost.close()
    }

    @Test
    fun closeWaitsForAnInFlightPrepareAndTheStartIsSuperseded(): Unit = runBlocking {
        val prepareStarted = CompletableDeferred<Unit>()
        val releasePrepare = CompletableDeferred<Unit>()
        val runtime = FakeCodexRuntime { message, server ->
            if (message.method == "initialize") server.respond(message.id, buildJsonObject {})
        }
        val workspace = CodexWorkspace("/workspace")
        val platform = object : CodexPlatform {
            override val authorizationBrowser =
                CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }
            override val workspaceStore = FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace))
            override suspend fun prepare(workspace: CodexWorkspace): PreparedCodexRuntime {
                prepareStarted.complete(Unit)
                releasePrepare.await()
                return PreparedCodexRuntime(
                    runtimeFactory = { runtime },
                    workspacePath = workspace.path,
                    features = CodexRuntimeFeature.entries.toSet(),
                )
            }
        }
        val host = CodexHost(platform, TEST_CLIENT_INFO)
        try {
            val starting = async(start = CoroutineStart.UNDISPATCHED) { host.start() }
            prepareStarted.await()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { host.close() }
            yield()
            assertFalse(closing.isCompleted)

            releasePrepare.complete(Unit)
            assertFailsWith<CancellationException> { starting.await() }
            closing.await()
            assertIs<CodexHostState.Closed>(host.state.value)
            assertTrue(runtime.allClientStreamsClosed())
        } finally {
            releasePrepare.complete(Unit)
            host.close()
        }
    }

    @Test
    fun closeReportsCleanupFailureFromAnInFlightPrepare(): Unit = runBlocking {
        val prepareStarted = CompletableDeferred<Unit>()
        val releasePrepare = CompletableDeferred<Unit>()
        val delegate = FakeCodexRuntime { message, server ->
            if (message.method == "initialize") server.respond(message.id, buildJsonObject {})
        }
        val runtime = object : CodexRuntime by delegate {
            override fun close() {
                delegate.close()
                error("late runtime close failed")
            }
        }
        val workspace = CodexWorkspace("/workspace")
        val platform = object : CodexPlatform {
            override val authorizationBrowser =
                CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }
            override val workspaceStore = FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace))
            override suspend fun prepare(workspace: CodexWorkspace): PreparedCodexRuntime {
                prepareStarted.complete(Unit)
                releasePrepare.await()
                return PreparedCodexRuntime(
                    runtimeFactory = { runtime },
                    workspacePath = workspace.path,
                    features = CodexRuntimeFeature.entries.toSet(),
                )
            }
        }
        val host = CodexHost(platform, TEST_CLIENT_INFO)
        try {
            val starting = async(start = CoroutineStart.UNDISPATCHED) { runCatching { host.start() } }
            prepareStarted.await()
            val closing = async(start = CoroutineStart.UNDISPATCHED) { runCatching { host.close() } }
            yield()
            assertFalse(closing.isCompleted)

            releasePrepare.complete(Unit)
            val startFailure = assertIs<CodexOperationException>(starting.await().exceptionOrNull())
            val closeFailure = assertIs<CodexOperationException>(closing.await().exceptionOrNull())
            assertEquals("host_close_failed", startFailure.failure.code)
            assertEquals("host_close_failed", closeFailure.failure.code)
            assertEquals("late runtime close failed", closeFailure.cause?.message)
            assertTrue(delegate.allClientStreamsClosed())
        } finally {
            releasePrepare.complete(Unit)
            runCatching { host.close() }
        }
    }

    @Test
    fun closeConversationDoesNotWaitForAnOverlappingOpenRequest(): Unit = runBlocking {
        val startRequested = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val staleApprovalRejected = CountDownLatch(1)
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> {
                    startRequested.countDown()
                    check(releaseStart.await(5, TimeUnit.SECONDS))
                    server.respond(message.id, buildJsonObject {
                        putJsonObject("thread") { put("id", "thread-overlap") }
                    })
                }
                null -> if (message.id == 904L) staleApprovalRejected.countDown()
            }
        }
        val workspace = CodexWorkspace("/workspace", "Workspace")
        val host = CodexHost(
            FakePlatformSupport(
                FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace)),
                ArrayDeque<FakeCodexRuntime>().apply { add(runtime) },
            ),
            this,
            clientInfo = TEST_CLIENT_INFO,
        )
        try {
            host.start()
            val agent = readyAgent(host)
            val opening = async(start = CoroutineStart.UNDISPATCHED) { agent.openConversation() }
            withTimeout(1_000) {
                while (startRequested.count != 0L) yield()
            }

            val retained = assertNotNull(agent.activeConversation.value)
            withTimeout(1_000) { retained.close() }
            assertNull(agent.activeConversation.value)
            assertEquals(AgentConversationStatus.CLOSED, retained.state.value.status)

            releaseStart.countDown()
            assertFailsWith<CancellationException> { opening.await() }
            assertNull(agent.activeConversation.value)
            runtime.request(
                904,
                "item/commandExecution/requestApproval",
                hostApprovalRequest("thread-overlap", "turn-overlap"),
            )
            assertTrue(staleApprovalRejected.await(1, TimeUnit.SECONDS))
        } finally {
            releaseStart.countDown()
            host.close()
        }
    }

    @Test
    fun cancellingCloseConversationStillClosesAndDetachesTheConversation(): Unit = runBlocking {
        val interruptRequest = CompletableDeferred<Long>()
        val staleApprovalRejected = CountDownLatch(1)
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-cancel-close") }
                })
                "turn/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("turn") { put("id", "turn-cancel-close") }
                })
                "turn/interrupt" -> interruptRequest.complete(checkNotNull(message.id))
                null -> if (message.id == 901L) staleApprovalRejected.countDown()
            }
        }
        val workspace = CodexWorkspace("/workspace", "Workspace")
        val host = CodexHost(
            FakePlatformSupport(
                FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace)),
                ArrayDeque<FakeCodexRuntime>().apply { add(runtime) },
            ),
            this,
            TEST_CLIENT_INFO,
        )
        try {
            host.start()
            val agent = readyAgent(host)
            val conversation = agent.openConversation()
            conversation.send(AgentTurnRequest("hello"))

            val closing = async(start = CoroutineStart.UNDISPATCHED) { conversation.close() }
            val interruptId = withTimeout(1_000) { interruptRequest.await() }
            closing.cancel()
            runtime.respond(interruptId, buildJsonObject {})

            assertFailsWith<CancellationException> { closing.await() }
            assertNull(agent.activeConversation.value)
            assertEquals(AgentConversationStatus.CLOSED, conversation.state.value.status)
            runtime.request(
                901,
                "item/commandExecution/requestApproval",
                hostApprovalRequest("thread-cancel-close", "turn-cancel-close"),
            )
            assertTrue(staleApprovalRejected.await(1, TimeUnit.SECONDS))
            assertTrue(agent.interactionState.value.pending.isEmpty())
        } finally {
            host.close()
        }
    }

    @Test
    fun cancellingABlockedTurnStartDoesNotDelayConversationClose(): Unit = runBlocking {
        val turnStartRequested = CountDownLatch(1)
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-cancel-send") }
                })
                "turn/start" -> turnStartRequested.countDown()
            }
        }
        val workspace = CodexWorkspace("/workspace")
        val host = CodexHost(
            FakePlatformSupport(
                FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace)),
                ArrayDeque<FakeCodexRuntime>().apply { add(runtime) },
            ),
            TEST_CLIENT_INFO,
        )
        try {
            host.start()
            val conversation = readyAgent(host).openConversation()
            val sending = async(start = CoroutineStart.UNDISPATCHED) { conversation.send("hello") }
            withTimeout(5_000) {
                while (turnStartRequested.count != 0L) yield()
            }
            sending.cancel()
            assertFailsWith<CancellationException> { sending.await() }
            assertEquals(AgentConversationStatus.FAILED, conversation.state.value.status)

            withTimeout(1_000) { conversation.close() }
            assertEquals(AgentConversationStatus.CLOSED, conversation.state.value.status)
        } finally {
            host.close()
        }
    }

    @Test
    fun gracefulCloseTimeoutStillClosesAndDetachesTheConversation(): Unit = runBlocking {
        val staleApprovalRejected = CountDownLatch(1)
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "thread/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("thread") { put("id", "thread-close-timeout") }
                })
                "turn/start" -> server.respond(message.id, buildJsonObject {
                    putJsonObject("turn") { put("id", "turn-close-timeout") }
                })
                "turn/interrupt" -> Unit
                null -> if (message.id == 902L) staleApprovalRejected.countDown()
            }
        }
        val workspace = CodexWorkspace("/workspace", "Workspace")
        val host = CodexHost(
            FakePlatformSupport(
                FakeWorkspaceStore(CodexWorkspaceResolution.Available(workspace)),
                ArrayDeque<FakeCodexRuntime>().apply { add(runtime) },
            ),
            this,
            TEST_CLIENT_INFO,
            requestTimeoutMillis = 50,
        )
        try {
            host.start()
            val agent = readyAgent(host)
            val conversation = agent.openConversation()
            conversation.send(AgentTurnRequest("hello"))

            withTimeout(1_000) { conversation.close() }
            assertNull(agent.activeConversation.value)
            assertEquals(AgentConversationStatus.CLOSED, conversation.state.value.status)
            runtime.request(
                902,
                "item/commandExecution/requestApproval",
                hostApprovalRequest("thread-close-timeout", "turn-close-timeout"),
            )
            assertTrue(staleApprovalRejected.await(1, TimeUnit.SECONDS))
            assertTrue(agent.interactionState.value.pending.isEmpty())
        } finally {
            host.close()
        }
    }

    @Test
    fun restoresSelectsReplacesRetriesAndClosesOneOwnedGraph(): Unit = runBlocking {
        val runtimes = ArrayDeque<FakeCodexRuntime>()
        val threadIds = AtomicInteger()
        val conversationWorkspaces = mutableListOf<String?>()
        repeat(3) { runtimes += hostRuntime(threadIds, conversationWorkspaces) }
        val workspaceOne = CodexWorkspace("/workspace/one", "One")
        val workspaceTwo = CodexWorkspace("/workspace/two", "Two")
        val store = FakeWorkspaceStore(
            restoreResult = CodexWorkspaceResolution.SelectionRequired(
                CodexWorkspaceSelectionReason.NOT_FOUND,
                "Choose a workspace",
            ),
        )
        val support = FakePlatformSupport(store, runtimes)
        val host = CodexHost(support, this, clientInfo = TEST_CLIENT_INFO)

        host.start()
        val required = assertIs<CodexHostState.WorkspaceRequired>(host.state.value)
        assertEquals(CodexWorkspaceSelectionReason.NOT_FOUND, required.requirement.reason)
        assertFailsWith<IllegalStateException> { host.start() }

        store.selectResult = CodexWorkspaceResolution.Available(workspaceOne)
        host.selectWorkspace(CodexPathWorkspaceSelection(workspaceOne.path))
        val firstAgent = readyAgent(host)
        assertEquals(AgentIntegrationAuthorizationStatus.IDLE, firstAgent.integrationAuthorizationState.value.status)
        firstAgent.listModels()
        val firstRuntime = support.preparedRuntimes[0]

        val firstConversation = firstAgent.openConversation()
        assertSame(firstConversation, firstAgent.activeConversation.value)
        val firstConversationId = firstConversation.state.value.conversationId
        assertEquals("/workspace/one", conversationWorkspaces.last())
        val secondConversation = firstAgent.openConversation()
        val secondConversationId = secondConversation.state.value.conversationId
        assertTrue(firstConversationId != secondConversationId)
        assertEquals(AgentConversationStatus.CLOSED, firstConversation.state.value.status)
        assertFailsWith<IllegalStateException> { firstConversation.reload() }

        store.selectResult = CodexWorkspaceResolution.Available(workspaceTwo)
        host.selectWorkspace(CodexPathWorkspaceSelection(workspaceTwo.path))
        val secondAgent = readyAgent(host)
        assertNull(secondAgent.activeConversation.value)
        assertFailsWith<IllegalStateException> { firstAgent.listModels() }
        assertTrue(firstRuntime.allClientStreamsClosed())

        support.failNextPrepare = true
        store.selectResult = CodexWorkspaceResolution.Available(CodexWorkspace("/workspace/three", "Three"))
        assertFailsWith<CodexOperationException> {
            host.selectWorkspace(CodexPathWorkspaceSelection("/workspace/three"))
        }
        val failed = assertIs<CodexHostState.Failed>(host.state.value)
        assertEquals("Could not prepare Codex", failed.failure.message)
        host.start()
        val thirdAgent = readyAgent(host)
        thirdAgent.listModels()

        val signedOutConversation = thirdAgent.openConversation()
        thirdAgent.signOut()
        assertNull(thirdAgent.activeConversation.value)
        assertEquals(AgentConversationStatus.CLOSED, signedOutConversation.state.value.status)
        assertFailsWith<IllegalStateException> {
            signedOutConversation.send(AgentTurnRequest("after sign-out"))
        }

        host.close()
        host.close()
        assertIs<CodexHostState.Closed>(host.state.value)
        assertFailsWith<IllegalStateException> { thirdAgent.listModels() }
        signedOutConversation.close()
        assertTrue(support.preparedRuntimes.last().allClientStreamsClosed())
    }
}

private fun readyAgent(host: CodexHost): CodexAgent =
    assertIs<CodexHostState.Ready>(host.state.value).agent

private class FakeWorkspaceStore(
    var restoreResult: CodexWorkspaceResolution,
) : CodexWorkspaceStore {
    lateinit var selectResult: CodexWorkspaceResolution

    override suspend fun select(selection: CodexWorkspaceSelection): CodexWorkspaceResolution = selectResult

    override suspend fun restore(): CodexWorkspaceResolution = restoreResult

    override suspend fun clear() = Unit
}

private class FakePlatformSupport(
    override val workspaceStore: CodexWorkspaceStore,
    private val runtimes: ArrayDeque<FakeCodexRuntime>,
) : CodexPlatform {
    val preparedRuntimes = mutableListOf<FakeCodexRuntime>()
    var failNextPrepare = false

    override val authorizationBrowser = CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }

    override suspend fun prepare(workspace: CodexWorkspace): PreparedCodexRuntime {
        if (failNextPrepare) {
            failNextPrepare = false
            error("prepare failed")
        }
        val runtime = runtimes.removeFirst()
        preparedRuntimes += runtime
        return PreparedCodexRuntime(
            runtimeFactory = { runtime },
            workspacePath = workspace.path,
            features = CodexRuntimeFeature.entries.toSet(),
        )
    }
}

private val TEST_CLIENT_INFO = CodexClientInfo("codex_agent_tests", "Codex Agent Tests", "test")

private fun hostRuntime(
    threadIds: AtomicInteger,
    conversationWorkspaces: MutableList<String?>,
): FakeCodexRuntime = FakeCodexRuntime { message, server ->
    when (message.method) {
        "initialize" -> server.respond(message.id, buildJsonObject {})
        "model/list" -> server.respond(message.id, buildJsonObject { putJsonArray("data") {} })
        "account/logout" -> server.respond(message.id, buildJsonObject {})
        "thread/start" -> {
            conversationWorkspaces += message.params.optionalString("cwd")
            val id = "thread-${threadIds.incrementAndGet()}"
            server.respond(message.id, buildJsonObject { putJsonObject("thread") { put("id", id) } })
        }
    }
}

private fun hostApprovalRequest(conversationId: String, turnId: String) = buildJsonObject {
    put("itemId", "item-1")
    put("startedAtMs", 1)
    put("threadId", conversationId)
    put("turnId", turnId)
    put("command", "git status")
}
