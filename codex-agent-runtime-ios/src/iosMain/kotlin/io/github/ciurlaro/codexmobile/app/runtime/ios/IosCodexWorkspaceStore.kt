@file:OptIn(kotlinx.cinterop.BetaInteropApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.ciurlaro.codexmobile.app.runtime.ios

import io.github.ciurlaro.codexmobile.agent.CodexWorkspace
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceSelection
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceSelectionReason
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceStore
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkCreationWithSecurityScope
import platform.Foundation.NSURLBookmarkResolutionWithSecurityScope
import platform.Foundation.NSDataWritingAtomic
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.free
import platform.posix.realpath

class IosCodexWorkspaceSelection(val url: NSURL) : CodexWorkspaceSelection

internal data class IosResolvedBookmark(
    val url: NSURL,
    val stale: Boolean,
)

internal interface IosWorkspaceBookmarkBackend {
    fun create(url: NSURL, securityScoped: Boolean): ByteArray
    fun resolve(bookmark: ByteArray, securityScoped: Boolean): IosResolvedBookmark
}

internal object FoundationIosWorkspaceBookmarkBackend : IosWorkspaceBookmarkBackend {
    override fun create(url: NSURL, securityScoped: Boolean): ByteArray = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        val options = if (securityScoped) NSURLBookmarkCreationWithSecurityScope else 0u
        val data = url.bookmarkDataWithOptions(options, null, null, error.ptr)
            ?: error(error.value?.localizedDescription ?: "Unable to create the workspace bookmark")
        data.toByteArray()
    }

    override fun resolve(bookmark: ByteArray, securityScoped: Boolean): IosResolvedBookmark = memScoped {
        val stale = alloc<BooleanVar>()
        val error = alloc<ObjCObjectVar<NSError?>>()
        stale.value = false
        error.value = null
        val options = if (securityScoped) NSURLBookmarkResolutionWithSecurityScope else 0u
        val url = NSURL.URLByResolvingBookmarkData(
            bookmark.toNSData(),
            options,
            null,
            stale.ptr,
            error.ptr,
        ) ?: error(error.value?.localizedDescription ?: "Unable to restore the workspace bookmark")
        IosResolvedBookmark(url, stale.value)
    }
}

internal class IosCodexWorkspaceStore(
    private val sandboxRootPath: String,
    private val bookmarkPath: String,
    private val backend: IosWorkspaceBookmarkBackend = FoundationIosWorkspaceBookmarkBackend,
) : CodexWorkspaceStore {
    override suspend fun select(selection: CodexWorkspaceSelection): CodexWorkspaceResolution {
        if (selection !is IosCodexWorkspaceSelection || !selection.url.isFileURL()) return required(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "Select a filesystem folder.",
        )
        val path = canonicalDirectory(selection.url.path ?: return required(
            CodexWorkspaceSelectionReason.INVALID_SELECTION,
            "Selected folder path is invalid.",
        )) ?: return required(CodexWorkspaceSelectionReason.NOT_FOUND, "Selected folder is unavailable.")
        val securityScoped = !path.isInside(canonicalSandbox())
        val access = if (securityScoped) selection.url.startAccessingSecurityScopedResource() else true
        if (!access) return required(
            CodexWorkspaceSelectionReason.ACCESS_REVOKED,
            "Folder access was not granted; select it again.",
        )
        return try {
            val bookmark = backend.create(selection.url, securityScoped)
            persist(byteArrayOf(if (securityScoped) 1 else 0) + bookmark)
            available(path)
        } catch (error: Throwable) {
            required(
                CodexWorkspaceSelectionReason.INVALID_SELECTION,
                error.message ?: "Unable to save the workspace selection.",
            )
        } finally {
            if (securityScoped) selection.url.stopAccessingSecurityScopedResource()
        }
    }

    override suspend fun restore(): CodexWorkspaceResolution =
        resolvePersisted()?.let { resolved ->
            resolved.use { available(it.path) }
        } ?: if (NSFileManager.defaultManager.fileExistsAtPath(bookmarkPath)) {
            required(
                CodexWorkspaceSelectionReason.ACCESS_REVOKED,
                "Saved folder access is stale or unavailable; select it again.",
            )
        } else {
            required(CodexWorkspaceSelectionReason.NOT_SELECTED, "Select a workspace folder.")
        }

    override suspend fun clear() {
        NSFileManager.defaultManager.removeItemAtPath(bookmarkPath, null)
    }

    fun acquire(path: String): IosWorkspaceLease {
        val lease = requireNotNull(resolvePersisted()) {
            "Saved workspace access is stale or unavailable; select it again"
        }
        if (lease.path != canonicalDirectory(path)) {
            lease.close()
            error("Prepared workspace does not match the saved selection")
        }
        return lease
    }

    private fun resolvePersisted(): IosWorkspaceLease? = runCatching {
        val envelope = NSData.dataWithContentsOfFile(bookmarkPath)?.toByteArray() ?: return null
        require(envelope.size in 2..MAX_BOOKMARK_BYTES)
        val securityScoped = envelope[0].toInt() == 1
        require(securityScoped || envelope[0].toInt() == 0)
        val resolved = backend.resolve(envelope.copyOfRange(1, envelope.size), securityScoped)
        require(!resolved.stale && resolved.url.isFileURL())
        val path = canonicalDirectory(requireNotNull(resolved.url.path)) ?: return null
        require(securityScoped == !path.isInside(canonicalSandbox()))
        if (securityScoped) require(resolved.url.startAccessingSecurityScopedResource())
        IosWorkspaceLease(path, resolved.url, securityScoped)
    }.getOrNull()

    private fun persist(envelope: ByteArray) {
        require(envelope.size in 2..MAX_BOOKMARK_BYTES) { "Workspace bookmark size is invalid" }
        val parent = bookmarkPath.substringBeforeLast('/', missingDelimiterValue = "")
        require(parent.isNotEmpty()) { "Workspace bookmark path must have a parent directory" }
        val manager = NSFileManager.defaultManager
        check(manager.createDirectoryAtPath(parent, true, null, null)) {
            "Unable to create the workspace bookmark directory"
        }
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            error.value = null
            check(envelope.toNSData().writeToFile(bookmarkPath, NSDataWritingAtomic, error.ptr)) {
                error.value?.localizedDescription ?: "Unable to persist the workspace bookmark"
            }
        }
    }

    private fun canonicalSandbox(): String = canonicalDirectory(sandboxRootPath)
        ?: error("iOS sandbox root is unavailable")

    private fun available(path: String) = CodexWorkspaceResolution.Available(
        CodexWorkspace(path, path.substringAfterLast('/').ifBlank { path }),
    )

    private fun required(reason: CodexWorkspaceSelectionReason, message: String) =
        CodexWorkspaceResolution.SelectionRequired(reason, message)

    private companion object {
        const val MAX_BOOKMARK_BYTES = 1024 * 1024
    }
}

internal class IosWorkspaceLease(
    val path: String,
    private val url: NSURL,
    val securityScoped: Boolean,
) : AutoCloseable {
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        if (securityScoped) url.stopAccessingSecurityScopedResource()
    }
}

private fun canonicalDirectory(path: String): String? {
    val directory = memScoped {
        val value = alloc<BooleanVar>()
        if (!NSFileManager.defaultManager.fileExistsAtPath(path, value.ptr)) return null
        value.value
    }
    if (!directory) return null
    val resolved = realpath(path, null) ?: return null
    return try {
        resolved.toKString()
    } finally {
        free(resolved)
    }
}

private fun String.isInside(root: String): Boolean = this == root || startsWith("$root/")

private fun ByteArray.toNSData(): NSData = usePinned { pinned ->
    NSData.create(bytes = pinned.addressOf(0), length = size.convert())
}

private fun NSData.toByteArray(): ByteArray =
    bytes?.reinterpret<kotlinx.cinterop.UByteVar>()?.readBytes(length.toInt()) ?: ByteArray(0)
