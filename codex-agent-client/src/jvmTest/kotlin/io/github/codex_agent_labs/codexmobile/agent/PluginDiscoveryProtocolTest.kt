package io.github.codex_agent_labs.codexmobile.agent

import okio.FileSystem
import okio.Path.Companion.toPath
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.*
import io.github.codex_agent_labs.codexmobile.agent.*
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class PluginDiscoveryProtocolTest : SkillsPluginsProtocolTestBase() {
    @Test
    fun pluginDiscoveryWorksWithoutAWorkspace() = runBlocking {
        var params: JsonObject? = null
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/list" -> {
                    params = message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, pluginList(installed = false))
                }
            }
        }

        CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000).use { client ->
            assertTrue(client.listAvailablePlugins(null, forceRefresh = true).plugins.isNotEmpty())
        }
        assertFalse("cwds" in checkNotNull(params))
    }

    @Test
    fun signOutClearsSavedPluginCatalogs(): Unit = runBlocking {
        val cache = Files.createTempDirectory("signed-out-plugin-cache-").toFile()
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize", "account/logout" -> server.respond(message.id, buildJsonObject {})
                "plugin/list" -> server.respond(message.id, pluginList(installed = false))
            }
        }
        CodexAgentClient(
            { runtime },
            requestTimeoutMillis = 1_000,
            pluginCacheDirectory = cache.absolutePath.toPath(),
            fileSystem = FileSystem.SYSTEM,
        ).use { client ->
            client.listAvailablePlugins("/workspace")
            assertTrue(cache.listFiles().orEmpty().isNotEmpty())
            client.signOut()
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }
        cache.deleteRecursively()
    }

    @Test
    fun cancelledPluginRefreshDoesNotSurfaceCachedDataAsAnErrorResult(): Unit = runBlocking {
        val cache = Files.createTempDirectory("plugin-cache-").toFile()
        val refreshStarted = CompletableDeferred<Unit>()
        var requests = 0
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/list" -> if (++requests == 1) {
                    server.respond(message.id, pluginList(installed = false))
                } else {
                    refreshStarted.complete(Unit)
                }
            }
        }

        CodexAgentClient(
            { runtime },
            requestTimeoutMillis = 5_000,
            pluginCacheDirectory = cache.absolutePath.toPath(),
            fileSystem = FileSystem.SYSTEM,
        ).use { client ->
            client.listAvailablePlugins("/workspace", forceRefresh = true)
            var delivered = false
            val refresh = launch {
                client.listAvailablePlugins("/workspace", forceRefresh = true)
                delivered = true
            }
            refreshStarted.await()
            refresh.cancel()
            refresh.join()
            assertFalse(delivered)
        }
        cache.deleteRecursively()
    }

    @Test
    fun pluginDiscoveryAcceptsMarketplacesExposedByAppServer() {
        val response = Json.decodeFromJsonElement(
            PluginListResponse.serializer(),
            pluginList(installed = false, marketplace = "team-catalog"),
        )
        val plugins = parsePluginMarketplaces(response.marketplaces)

        assertEquals("team-catalog", plugins.single().reference.marketplaceName)
        assertEquals(REMOTE_PLUGIN_ID, plugins.single().reference.remotePluginId)
    }

    @Test
    fun pluginReadSendsExactlyOneMarketplaceIdentity(): Unit = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/read" -> {
                    requests += message.objectValue["params"]!!.jsonObject
                    server.respond(message.id, pluginDetail())
                }
            }
        }

        CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000).use { client ->
            client.readPlugin(AgentPluginReference("local@catalog", "local", "catalog", "/marketplace"))
            client.readPlugin(
                AgentPluginReference("remote@catalog", "remote", "catalog", remotePluginId = REMOTE_PLUGIN_ID),
            )
        }

        assertEquals(setOf("pluginName", "marketplacePath"), requests[0].keys)
        assertEquals(setOf("pluginName", "remoteMarketplaceName"), requests[1].keys)
        assertEquals("local", requests[0]["pluginName"]!!.jsonPrimitive.content)
        assertEquals(REMOTE_PLUGIN_ID, requests[1]["pluginName"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapsAStaleRemotePluginEntryToUnavailable(): Unit = runBlocking {
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/install" -> server.sendRaw(
                    buildJsonObject {
                        put("id", message.id)
                        putJsonObject("error") {
                            put("code", -32600)
                            put("message", "remote plugin request failed with status 404: Plugin not found")
                        }
                    }.toString(),
                )
            }
        }
        val workspace = CodexWorkspace("/workspace")
        val platform = object : CodexPlatform {
            override val authorizationBrowser =
                CodexAuthorizationBrowser { CodexAuthorizationPresentation.None }
            override val workspaceStore = object : CodexWorkspaceStore {
                override suspend fun select(selection: CodexWorkspaceSelection) =
                    CodexWorkspaceResolution.Available(workspace)
                override suspend fun restore() = CodexWorkspaceResolution.Available(workspace)
                override suspend fun clear() = Unit
            }
            override suspend fun prepare(workspace: CodexWorkspace) = PreparedCodexRuntime(
                runtimeFactory = { process },
                workspacePath = workspace.path,
                features = CodexRuntimeFeature.entries.toSet(),
            )
        }
        val host = CodexHost(platform, CodexClientInfo("plugin_test", "Plugin Test", "test"))
        try {
            host.start()
            val agent = assertIs<CodexHostState.Ready>(host.state.value).agent
            val error = runCatching {
                agent.installPlugin(
                    AgentPluginReference("missing@remote", "missing", "remote", remotePluginId = REMOTE_PLUGIN_ID),
                )
            }.exceptionOrNull()

            val operation = assertIs<CodexOperationException>(error)
            assertEquals("plugin_unavailable", operation.failure.code)
            assertEquals("missing@remote", assertIs<AgentPluginUnavailableException>(operation.cause).pluginId)
        } finally {
            host.close()
        }
    }

}
