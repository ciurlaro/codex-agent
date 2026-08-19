@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.runtime.IosCodexCredentialProtection
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionComplete
import platform.Foundation.NSFileProtectionCompleteUnlessOpen
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.darwin.DISPATCH_SOURCE_TYPE_VNODE
import platform.darwin.DISPATCH_VNODE_ATTRIB
import platform.darwin.DISPATCH_VNODE_EXTEND
import platform.darwin.DISPATCH_VNODE_RENAME
import platform.darwin.DISPATCH_VNODE_WRITE
import platform.darwin.dispatch_activate
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_source_cancel
import platform.darwin.dispatch_source_create
import platform.darwin.dispatch_source_set_cancel_handler
import platform.darwin.dispatch_source_set_event_handler
import platform.darwin.dispatch_source_t
import platform.posix.open

internal fun applyIosCredentialProtection(configuration: IosCodexRuntimeConfiguration) {
    val fileManager = NSFileManager.defaultManager
    val codexHome = configuration.codexHomePath
    check(fileManager.fileExistsAtPath(codexHome)) { "iOS Codex home does not exist" }

    val paths = mutableListOf(codexHome)
    val enumerator = fileManager.enumeratorAtPath(codexHome)
    while (true) {
        val relative = enumerator?.nextObject() as? String ?: break
        paths += "$codexHome/$relative"
    }

    val protection = iosFileProtectionValue(configuration.credentialProtection)
    paths.forEach { path ->
        check(
            fileManager.setAttributes(
                mapOf(NSFileProtectionKey to protection),
                ofItemAtPath = path,
                error = null,
            ),
        ) { "Could not apply iOS file protection to Codex state" }
        check(
            NSURL.fileURLWithPath(path).setResourceValue(
                true,
                forKey = NSURLIsExcludedFromBackupKey,
                error = null,
            ),
        ) { "Could not exclude Codex state from backups" }
    }
}

internal class IosCodexCredentialProtectionMonitor(
    private val configuration: IosCodexRuntimeConfiguration,
    private val onFailure: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val descriptor: Int
    private val source: dispatch_source_t

    init {
        applyIosCredentialProtection(configuration)
        descriptor = open(configuration.codexHomePath, DARWIN_O_EVTONLY)
        check(descriptor >= 0) { "Could not watch iOS Codex state" }
        source = checkNotNull(
            dispatch_source_create(
                DISPATCH_SOURCE_TYPE_VNODE,
                descriptor.toULong(),
                (DISPATCH_VNODE_WRITE or DISPATCH_VNODE_EXTEND or
                    DISPATCH_VNODE_ATTRIB or DISPATCH_VNODE_RENAME).toULong(),
                dispatch_queue_create("io.github.ciurlaro.codex-agent.credentials", null),
            ),
        ) { "Could not create iOS Codex state watcher" }
        dispatch_source_set_event_handler(source) {
            runCatching { applyIosCredentialProtection(configuration) }.onFailure(onFailure)
        }
        dispatch_source_set_cancel_handler(source) { platform.posix.close(descriptor) }
        dispatch_activate(source)
    }

    override fun close() = dispatch_source_cancel(source)
}

private const val DARWIN_O_EVTONLY = 0x8000

internal fun iosFileProtectionValue(protection: IosCodexCredentialProtection): String =
    checkNotNull(
        when (protection) {
            IosCodexCredentialProtection.WHEN_UNLOCKED -> NSFileProtectionComplete
            IosCodexCredentialProtection.AFTER_FIRST_UNLOCK ->
                NSFileProtectionCompleteUntilFirstUserAuthentication
            IosCodexCredentialProtection.WHILE_OPEN -> NSFileProtectionCompleteUnlessOpen
        },
    )
