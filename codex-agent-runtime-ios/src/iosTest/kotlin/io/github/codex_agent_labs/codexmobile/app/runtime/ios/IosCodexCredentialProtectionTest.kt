@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.codex_agent_labs.codexmobile.app.runtime.ios

import io.github.codex_agent_labs.codexmobile.agent.runtime.IosCodexCredentialProtection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionCompleteUnlessOpen
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey

class IosCodexCredentialProtectionTest {
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

}
