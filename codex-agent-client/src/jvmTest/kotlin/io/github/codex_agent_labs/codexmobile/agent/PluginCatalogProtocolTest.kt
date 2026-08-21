package io.github.codex_agent_labs.codexmobile.agent

import okio.FileSystem
import okio.Path.Companion.toPath
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.*
import io.github.codex_agent_labs.codexmobile.agent.*
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class PluginCatalogProtocolTest : SkillsPluginsProtocolTestBase() {
    @Test
    fun usesPinnedAppServerCapabilityEndpoints(): Unit = runBlocking {
        val methods = mutableListOf<String>()
        var skillWrite: Boolean? = null
        var pluginWrite: String? = null
        var pluginReadName: String? = null
        var pluginInstallName: String? = null
        var pluginUninstallId: String? = null
        val process = FakeCodexRuntime { message, server ->
            message.method?.let(methods::add)
            when (message.method) {
                "initialize" -> {
                    val capabilities = message.objectValue["params"]!!.jsonObject["capabilities"]!!.jsonObject
                    assertTrue(capabilities["experimentalApi"]!!.jsonPrimitive.content.toBoolean())
                    assertFalse(capabilities["mcpServerOpenaiFormElicitation"]!!.jsonPrimitive.content.toBoolean())
                    server.respond(message.id, buildJsonObject {})
                }
                "skills/list" -> server.respond(message.id, skillsResponse())
                "skills/config/write" -> {
                    skillWrite = message.objectValue["params"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean()
                    server.respond(message.id, buildJsonObject { put("effectiveEnabled", true) })
                }
                "plugin/list" -> server.respond(message.id, pluginList(installed = false))
                "plugin/installed" -> server.respond(message.id, pluginList(installed = true))
                "plugin/read" -> {
                    pluginReadName = message.objectValue["params"]!!.jsonObject["pluginName"]!!.jsonPrimitive.content
                    server.respond(message.id, pluginDetail())
                }
                "plugin/install" -> {
                    pluginInstallName = message.objectValue["params"]!!.jsonObject["pluginName"]!!.jsonPrimitive.content
                    server.respond(
                        message.id,
                        buildJsonObject {
                            put("authPolicy", "ON_INSTALL")
                            putJsonArray("appsNeedingAuth") { add(connector()) }
                        },
                    )
                }
                "plugin/uninstall" -> {
                    pluginUninstallId = message.objectValue["params"]!!.jsonObject["pluginId"]!!.jsonPrimitive.content
                    server.respond(message.id, buildJsonObject {})
                }
                "config/value/write" -> {
                    pluginWrite = message.objectValue["params"]!!.jsonObject["keyPath"]!!.jsonPrimitive.content
                    server.respond(message.id, buildJsonObject {})
                }
                "app/list" -> server.respond(
                    message.id,
                    buildJsonObject { putJsonArray("data") { add(connector()) } },
                )
                "mcpServerStatus/list" -> server.respond(
                    message.id,
                    buildJsonObject {
                        putJsonArray("data") {
                            add(buildJsonObject {
                                put("name", "codex_apps")
                                put("authStatus", "oAuth")
                            })
                            add(buildJsonObject {
                                put("name", "drive")
                                put("authStatus", "notLoggedIn")
                            })
                        }
                    },
                )
                "mcpServer/oauth/login" -> server.respond(
                    message.id,
                    buildJsonObject { put("authorizationUrl", "https://accounts.example.com/oauth") },
                )
            }
        }
        val client = CodexAgentClient(
            { process },
            requestTimeoutMillis = 1_000,
            fileSystem = FileSystem.SYSTEM,
        )
        try {
            assertEquals("review", client.listSkills("/workspace").skills.single().name)
            client.setSkillEnabled("/skills/review/SKILL.md", true)
            val plugin = client.listInstalledPlugins("/workspace").plugins.single()
            assertFalse(client.listAvailablePlugins("/workspace").plugins.single().isInstalled)
            assertTrue(plugin.isInstalled)
            assertEquals("drive", client.readPlugin(plugin.reference).connectors.single().id)
            assertEquals("drive", client.installPlugin(plugin.reference).connectorsNeedingAuthentication.single().id)
            client.uninstallPlugin(plugin.reference)
            client.setPluginEnabled(plugin.reference.id, true)
            assertTrue(client.listConnectors().single().isAccessible)
            assertEquals("drive", client.listMcpServers().single().name)
            assertEquals("https://accounts.example.com/oauth", client.startMcpOauth("drive"))
            assertEquals(true, skillWrite)
            assertEquals("plugins.drive@openai-curated.enabled", pluginWrite)
            assertEquals(REMOTE_PLUGIN_ID, pluginReadName)
            assertEquals(REMOTE_PLUGIN_ID, pluginInstallName)
            assertEquals(REMOTE_PLUGIN_ID, pluginUninstallId)
            listOf("skills/list", "plugin/list", "plugin/installed", "plugin/read", "plugin/install", "app/list")
                .forEach { assertTrue(it in methods) }
        } finally {
            client.close()
        }
    }

    @Test
    fun readsLongSkillSourceWithoutSplittingUtf8Characters(): Unit = runBlocking {
        val source = File.createTempFile("codex-skill-", ".md")
        val expected = "a".repeat(32 * 1024 - 1) + "€" + "tail"
        source.writeText(expected)
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "skills/list" -> server.respond(message.id, skillsResponse(source.absolutePath))
            }
        }
        val client = CodexAgentClient(
            { process },
            requestTimeoutMillis = 1_000,
            fileSystem = FileSystem.SYSTEM,
        )
        try {
            client.listSkills("/workspace")
            val actual = buildString {
                var offset: Long? = 0
                while (offset != null) {
                    val chunk = client.readSkill(source.absolutePath, offset)
                    append(chunk.content)
                    offset = chunk.nextOffset
                }
            }
            assertEquals(expected, actual)
        } finally {
            client.close()
            source.delete()
        }
    }

    @Test
    fun availablePluginDiscoveryServesCacheAndKeepsStaleDataAfterRefreshFailure(): Unit = runBlocking {
        val cache = Files.createTempDirectory("plugin-cache-").toFile()
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/list" -> server.respond(message.id, pluginList(installed = false))
            }
        }
        CodexAgentClient(
            { process },
            requestTimeoutMillis = 1_000,
            pluginCacheDirectory = cache.absolutePath.toPath(),
            fileSystem = FileSystem.SYSTEM,
        ).use { client ->
            assertEquals(AgentCatalogFreshness.LIVE, client.listAvailablePlugins("/workspace").freshness)
        }

        val cached = CodexAgentClient(
            runtimeFactory = { error("Network should not be used for cached discovery") },
            requestTimeoutMillis = 100,
            pluginCacheDirectory = cache.absolutePath.toPath(),
            fileSystem = FileSystem.SYSTEM,
        )
        try {
            assertEquals(AgentCatalogFreshness.FRESH_CACHE, cached.listAvailablePlugins("/workspace").freshness)
            cache.listFiles().orEmpty().single { it.name.endsWith(".timestamp") }.writeText("0")
            assertEquals(AgentCatalogFreshness.STALE_CACHE, cached.listAvailablePlugins("/workspace").freshness)
            val fallback = cached.listAvailablePlugins("/workspace", forceRefresh = true)
            assertEquals(AgentCatalogFreshness.STALE_CACHE, fallback.freshness)
            assertTrue(fallback.plugins.isNotEmpty())
            assertTrue(fallback.errors.isNotEmpty())
        } finally {
            cached.close()
            cache.deleteRecursively()
        }
    }

    @Test
    fun pluginCacheIdentityIncludesTheFullClientIdentity() {
        val first = CodexAgentClient(
            runtimeFactory = { error("must not start") },
            clientName = "third_party",
            clientTitle = "First App",
            pluginCacheDirectory = "/cache".toPath(),
            fileSystem = FileSystem.SYSTEM,
        )
        val second = CodexAgentClient(
            runtimeFactory = { error("must not start") },
            clientName = "third_party",
            clientTitle = "Second App",
            pluginCacheDirectory = "/cache".toPath(),
            fileSystem = FileSystem.SYSTEM,
        )
        try {
            assertNotEquals(
                first.pluginCacheFileAction("/workspace", "available"),
                second.pluginCacheFileAction("/workspace", "available"),
            )
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun availablePluginDiscoveryRetriesAnEmptyCatalogWhileTheMarketplaceBecomesReady(): Unit = runBlocking {
        var requests = 0
        val process = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/list" -> {
                    requests++
                    server.respond(
                        message.id,
                        if (requests == 1) emptyPluginList() else pluginList(installed = false),
                    )
                }
            }
        }

        CodexAgentClient(
            runtimeFactory = { process },
            requestTimeoutMillis = 1_000,
            emptyPluginCatalogRetryDelaysMillis = listOf(0),
        ).use { client ->
            assertTrue(client.listAvailablePlugins("/workspace").plugins.isNotEmpty())
            assertEquals(2, requests)
        }
    }

    @Test
    fun installedPluginsPreserveMarketplaceRefreshFailures(): Unit = runBlocking {
        val runtime = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> server.respond(
                    message.id,
                    buildJsonObject {
                        putJsonArray("marketplaces") {
                            add(buildJsonObject {
                                put("name", "openai-curated")
                                putJsonArray("plugins") { add(pluginSummary(installed = true)) }
                            })
                        }
                        putJsonArray("marketplaceLoadErrors") {
                            add(buildJsonObject { put("message", "Stream Closed") })
                        }
                    },
                )
            }
        }

        CodexAgentClient({ runtime }, requestTimeoutMillis = 1_000).use { client ->
            val catalog = client.listInstalledPlugins("/workspace")
            assertTrue(catalog.plugins.single().isInstalled)
            assertEquals(listOf("Stream Closed"), catalog.errors)
        }
    }

    @Test
    fun installedPluginRefreshAcceptsAnAuthoritativeEmptyResponse(): Unit = runBlocking {
        val cache = Files.createTempDirectory("installed-plugin-cache-").toFile()
        val populated = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> server.respond(message.id, pluginList(installed = true))
            }
        }
        CodexAgentClient(
            { populated },
            requestTimeoutMillis = 1_000,
            pluginCacheDirectory = cache.absolutePath.toPath(),
            fileSystem = FileSystem.SYSTEM,
        ).use { client ->
            assertTrue(client.listInstalledPlugins("/workspace").plugins.single().isInstalled)
        }

        val empty = FakeCodexRuntime { message, server ->
            when (message.method) {
                "initialize" -> server.respond(message.id, buildJsonObject {})
                "plugin/installed" -> server.respond(message.id, emptyPluginList())
            }
        }
        CodexAgentClient(
            { empty },
            requestTimeoutMillis = 1_000,
            pluginCacheDirectory = cache.absolutePath.toPath(),
            fileSystem = FileSystem.SYSTEM,
        ).use { client ->
            val catalog = client.listInstalledPlugins("/workspace", forceRefresh = true)
            assertEquals(AgentCatalogFreshness.LIVE, catalog.freshness)
            assertTrue(catalog.plugins.isEmpty())
            assertTrue(catalog.errors.isEmpty())
        }
        cache.deleteRecursively()
    }

}
