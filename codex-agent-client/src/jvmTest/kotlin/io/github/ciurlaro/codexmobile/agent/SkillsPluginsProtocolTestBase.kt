package io.github.ciurlaro.codexmobile.agent

import kotlinx.serialization.json.*

internal const val REMOTE_PLUGIN_ID = "plugin_asdk_app_69a1d78e929881919bba0dbda1f6436d"

abstract class SkillsPluginsProtocolTestBase {
    protected fun skillsResponse(path: String = "/skills/review/SKILL.md") = buildJsonObject {
        putJsonArray("data") {
            add(buildJsonObject {
                put("cwd", "/workspace")
                putJsonArray("errors") {}
                putJsonArray("skills") {
                    add(buildJsonObject {
                        put("name", "review")
                        put("description", "Review code")
                        put("enabled", true)
                        put("path", path)
                        put("scope", "system")
                    })
                }
            })
        }
    }

    protected fun emptyPluginList() = buildJsonObject {
        putJsonArray("marketplaces") {}
        putJsonArray("marketplaceLoadErrors") {}
    }

    protected fun pluginList(installed: Boolean, marketplace: String = "openai-curated") = buildJsonObject {
        putJsonArray("marketplaces") {
            add(buildJsonObject {
                put("name", marketplace)
                putJsonArray("plugins") { add(pluginSummary(installed, marketplace)) }
            })
        }
    }

    protected fun pluginSummary(installed: Boolean, marketplace: String = "openai-curated") = buildJsonObject {
        put("id", "drive@$marketplace")
        put("remotePluginId", REMOTE_PLUGIN_ID)
        put("name", "drive")
        put("installed", installed)
        put("enabled", true)
        put("installPolicy", "AVAILABLE")
        put("authPolicy", "ON_INSTALL")
        put("availability", "AVAILABLE")
        putJsonObject("source") { put("type", "remote") }
        putJsonObject("interface") {
            put("displayName", "Drive")
            put("shortDescription", "Files in Drive")
            put("capabilities", buildJsonArray { add(JsonPrimitive("Search files")) })
            put("screenshotUrls", buildJsonArray {})
            put("screenshots", buildJsonArray {})
        }
    }

    protected fun pluginDetail(installed: Boolean = true) = buildJsonObject {
        putJsonObject("plugin") {
            put("marketplaceName", "openai-curated")
            put("summary", pluginSummary(installed))
            putJsonArray("skills") {}
            putJsonArray("apps") { add(connector()) }
            putJsonArray("appTemplates") {}
            putJsonArray("mcpServers") { add(JsonPrimitive("drive")) }
            putJsonArray("hooks") {}
        }
    }

    protected fun connector() = buildJsonObject {
        put("id", "drive")
        put("name", "Drive")
        put("description", "Files")
        put("installUrl", "https://accounts.example.com/oauth")
        put("isAccessible", true)
        put("isEnabled", true)
    }
}
