package io.github.ciurlaro.codexmobile.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentPluginCatalogMergeTest {
    @Test
    fun mergesInstalledStateWithoutReorderingAvailablePlugins() {
        val alpha = plugin("alpha", displayName = "Available Alpha")
        val availableBeta = plugin(
            "beta",
            displayName = "Available Beta",
            description = "Available description",
        )
        val installedBeta = plugin(
            "beta",
            displayName = "Installed Beta",
            description = "Installed description",
            isInstalled = true,
            isEnabled = true,
            installPolicy = AgentPluginInstallPolicy.INSTALLED_BY_DEFAULT,
            authPolicy = AgentPluginAuthPolicy.ON_INSTALL,
        )
        val installedOnly = plugin("installed-only", isInstalled = true, isEnabled = true)

        val merged = mergePluginCatalogs(
            available = AgentPluginCatalog(
                plugins = listOf(alpha, availableBeta),
                errors = listOf("available error", "shared error"),
                freshness = AgentCatalogFreshness.LIVE,
            ),
            installed = AgentPluginCatalog(
                plugins = listOf(installedBeta, installedOnly),
                errors = listOf("shared error", "installed error"),
                freshness = AgentCatalogFreshness.FRESH_CACHE,
            ),
        )

        assertEquals(listOf("alpha", "beta", "installed-only"), merged.plugins.map { it.reference.id })
        assertEquals(alpha, merged.plugins[0])
        with(merged.plugins[1]) {
            assertEquals("Available Beta", displayName)
            assertEquals("Available description", description)
            assertTrue(isInstalled)
            assertTrue(isEnabled)
            assertEquals(AgentPluginInstallPolicy.INSTALLED_BY_DEFAULT, installPolicy)
            assertEquals(AgentPluginAuthPolicy.ON_INSTALL, authPolicy)
        }
        assertEquals(installedOnly, merged.plugins[2])
        assertEquals(listOf("available error", "shared error", "installed error"), merged.errors)
        assertEquals(AgentCatalogFreshness.FRESH_CACHE, merged.freshness)
    }

    @Test
    fun keepsAvailableStateWhenPluginIsNotInstalled() {
        val available = plugin("available", isEnabled = true)

        val merged = mergePluginCatalogs(
            available = AgentPluginCatalog(listOf(available)),
            installed = AgentPluginCatalog(emptyList()),
        )

        assertEquals(available, merged.plugins.single())
        assertFalse(merged.plugins.single().isInstalled)
    }

    @Test
    fun reportsTheLeastFreshInputCatalog() {
        fun merge(first: AgentCatalogFreshness, second: AgentCatalogFreshness) = mergePluginCatalogs(
            available = AgentPluginCatalog(emptyList(), freshness = first),
            installed = AgentPluginCatalog(emptyList(), freshness = second),
        ).freshness

        assertEquals(AgentCatalogFreshness.LIVE, merge(AgentCatalogFreshness.LIVE, AgentCatalogFreshness.LIVE))
        assertEquals(
            AgentCatalogFreshness.FRESH_CACHE,
            merge(AgentCatalogFreshness.LIVE, AgentCatalogFreshness.FRESH_CACHE),
        )
        assertEquals(
            AgentCatalogFreshness.STALE_CACHE,
            merge(AgentCatalogFreshness.STALE_CACHE, AgentCatalogFreshness.LIVE),
        )
    }

    private fun plugin(
        id: String,
        displayName: String = id,
        description: String = "$id description",
        isInstalled: Boolean = false,
        isEnabled: Boolean = false,
        installPolicy: AgentPluginInstallPolicy = AgentPluginInstallPolicy.AVAILABLE,
        authPolicy: AgentPluginAuthPolicy = AgentPluginAuthPolicy.ON_USE,
    ) = AgentPluginSummary(
        reference = AgentPluginReference(
            id = id,
            name = id,
            marketplaceName = "test",
        ),
        displayName = displayName,
        description = description,
        isInstalled = isInstalled,
        isEnabled = isEnabled,
        installPolicy = installPolicy,
        authPolicy = authPolicy,
        isAvailable = true,
    )
}
