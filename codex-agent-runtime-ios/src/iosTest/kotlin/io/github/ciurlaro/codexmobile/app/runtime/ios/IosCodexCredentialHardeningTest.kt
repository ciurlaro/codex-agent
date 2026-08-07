@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.app.runtime.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID

class IosCodexCredentialHardeningTest {
    @Test
    fun protectionExcludesHomeExistingStateAndLaterChildFromBackup() {
        IosCodexCredentialProtection.entries.forEach { policy ->
            val fixture = fixture(policy.name)
            try {
                val existingState = "${fixture.codexHome}/auth.json"
                assertTrue(NSFileManager.defaultManager.createFileAtPath(existingState, null, null))
                applyIosCredentialProtection(fixture.configuration(policy))

                val laterChild = "${fixture.codexHome}/refreshed-auth.json"
                assertTrue(NSFileManager.defaultManager.createFileAtPath(laterChild, null, null))
                applyIosCredentialProtection(fixture.configuration(policy))

                listOf(fixture.codexHome, existingState, laterChild).forEach { path ->
                    val values = NSURL.fileURLWithPath(path).resourceValuesForKeys(
                        listOf(NSURLIsExcludedFromBackupKey),
                        error = null,
                    )
                    assertEquals(true, values?.get(NSURLIsExcludedFromBackupKey), path)
                }
            } finally {
                fixture.remove()
            }
        }
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

    private fun fixture(label: String): CredentialFixture {
        val sandbox = "${platform.Foundation.NSTemporaryDirectory()}codex-agent-$label-${NSUUID.UUID().UUIDString}"
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
}
