@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.app.runtime.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
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

class IosCodexCredentialHardeningTest {
    @Test
    fun everyPolicyProtectsHomeExistingAuthAndLaterCredentialFileWithoutTestReapply() = runBlocking {
        IosCodexCredentialProtection.entries.forEach { policy ->
            val fixture = fixture(policy.name)
            val existingState = "${fixture.codexHome}/auth.json"
            val laterChild = "${fixture.codexHome}/refreshed-auth.json"
            assertTrue(NSFileManager.defaultManager.createFileAtPath(existingState, null, null))
            val expectedProtection = iosFileProtectionValue(policy)
            val monitor = IosCodexCredentialProtectionMonitor(fixture.configuration(policy))
            try {
                val protectionAttributesAvailable = fileProtection(fixture.codexHome) != null
                assertProtected(fixture.codexHome, expectedProtection, protectionAttributesAvailable)
                assertProtected(existingState, expectedProtection, protectionAttributesAvailable)

                assertTrue(NSFileManager.defaultManager.createFileAtPath(laterChild, null, null))
                // Read the inherited values first. The production directory watcher, not this
                // test, corrects platforms/filesystems that do not inherit the requested policy.
                fileProtection(laterChild)
                excludedFromBackup(laterChild)
                withTimeout(TEST_TIMEOUT_MILLIS) {
                    while (
                        (protectionAttributesAvailable && fileProtection(laterChild) != expectedProtection) ||
                        !excludedFromBackup(laterChild)
                    ) {
                        yield()
                    }
                }
                assertProtected(laterChild, expectedProtection, protectionAttributesAvailable)
            } finally {
                monitor.close()
                fixture.remove()
            }
        }
    }

    @Test
    fun everyPolicyMapsToItsExactAppleFileProtectionValue() {
        assertEquals(NSFileProtectionComplete, iosFileProtectionValue(IosCodexCredentialProtection.WHEN_UNLOCKED))
        assertEquals(
            NSFileProtectionCompleteUntilFirstUserAuthentication,
            iosFileProtectionValue(IosCodexCredentialProtection.AFTER_FIRST_UNLOCK),
        )
        assertEquals(
            NSFileProtectionCompleteUnlessOpen,
            iosFileProtectionValue(IosCodexCredentialProtection.WHILE_OPEN),
        )
    }

    @Test
    fun rejectedNestedCodexHomeIsNotCreated() = runBlocking {
        val fixture = fixture("rejected")
        val nested = "${fixture.workspace}/state"
        try {
            val configuration = IosCodexRuntimeConfiguration(
                sandboxRootPath = fixture.sandbox,
                workspacePath = fixture.workspace,
                codexHomePath = nested,
                credentialProtection = IosCodexCredentialProtection.WHEN_UNLOCKED,
            )

            assertFailsWith<IosCodexRuntimeException> {
                executeIosWorkspaceTool(configuration, "list_directory", buildJsonObject {})
            }
            assertFalse(NSFileManager.defaultManager.fileExistsAtPath(nested))
        } finally {
            fixture.remove()
        }
    }

    private fun assertProtected(path: String, expectedProtection: String, attributesAvailable: Boolean) {
        val actualProtection = fileProtection(path)
        if (attributesAvailable) {
            assertEquals(expectedProtection, actualProtection, path)
        } else {
            assertEquals(null, actualProtection, "The simulator filesystem must consistently omit $NSFileProtectionKey")
        }
        assertTrue(excludedFromBackup(path), path)
    }

    private fun fileProtection(path: String): Any? =
        NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null)?.get(NSFileProtectionKey)

    private fun excludedFromBackup(path: String): Boolean {
        val value = NSURL.fileURLWithPath(path).resourceValuesForKeys(
            listOf(NSURLIsExcludedFromBackupKey),
            error = null,
        )?.get(NSURLIsExcludedFromBackupKey)
        return (value as? NSNumber)?.boolValue ?: (value as? Boolean ?: false)
    }

    private fun fixture(label: String): CredentialFixture {
        val sandbox = "${NSTemporaryDirectory()}codex-agent-$label-${NSUUID.UUID().UUIDString}"
        val workspace = "$sandbox/workspace"
        val codexHome = "$sandbox/codex-home"
        val fileManager = NSFileManager.defaultManager
        assertTrue(fileManager.createDirectoryAtPath(workspace, true, null, null))
        assertTrue(fileManager.createDirectoryAtPath(codexHome, true, null, null))
        return CredentialFixture(sandbox, workspace, codexHome)
    }

    private data class CredentialFixture(
        val sandbox: String,
        val workspace: String,
        val codexHome: String,
    ) {
        fun configuration(policy: IosCodexCredentialProtection) = IosCodexRuntimeConfiguration(
            sandboxRootPath = sandbox,
            workspacePath = workspace,
            codexHomePath = codexHome,
            credentialProtection = policy,
        )

        fun remove() {
            NSFileManager.defaultManager.removeItemAtPath(sandbox, null)
        }
    }

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 5_000L
    }
}
