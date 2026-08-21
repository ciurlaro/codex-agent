package io.github.codex_agent_labs.codexmobile.agent

import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.AppServerClientMethods
import io.github.codex_agent_labs.codexmobile.appserver.protocol.generated.PluginInstalledParams
import kotlinx.coroutines.sync.withLock

internal suspend fun CodexAgentClient.refreshBuiltInPluginEnablementAction(
    workingDirectory: String,
) {
    if (builtInEnablementLoaded) return
    builtInToolGate.withLock {
        if (builtInEnablementLoaded) return
        runCatching {
            val result = pluginRequest(
                AppServerClientMethods.PluginInstalled,
                PluginInstalledParams(cwds = listOf(workingDirectory)),
            )
            applyBuiltInPluginEnablement(
                AgentPluginCatalog(parsePluginMarketplaces(result.marketplaces)),
            )
        }.onFailure {
            builtInPluginEnabled.keys.forEach { builtInPluginEnabled[it] = false }
        }
        builtInEnablementLoaded = true
    }
}

internal fun CodexAgentClient.applyBuiltInPluginEnablementAction(
    catalog: AgentPluginCatalog,
) {
    builtInPluginEnabled.keys.forEach { pluginId ->
        val plugin = catalog.plugins.singleOrNull { it.reference.id == pluginId }
        builtInPluginEnabled[pluginId] = plugin?.let { it.isInstalled && it.isEnabled } == true
    }
}
