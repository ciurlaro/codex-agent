package io.github.ciurlaro.codexmobile.appserver.runtime

import io.github.ciurlaro.codexmobile.agent.CodexPathWorkspaceSelection
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceSelectionReason
import io.github.ciurlaro.codexmobile.appserver.runtime.host.PathWorkspaceStore
import io.github.ciurlaro.codexmobile.appserver.runtime.host.RuntimeBundleDescriptor
import io.github.ciurlaro.codexmobile.appserver.runtime.host.RuntimeBundleInstaller
import io.github.ciurlaro.codexmobile.appserver.runtime.host.inspectRuntimeZip
import io.github.ciurlaro.codexmobile.appserver.runtime.host.parseRuntimeBundleManifest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath

class RuntimeBundleInstallerTest {
    @Test
    fun installsReusesAndRepairsVersionedRuntime() = withTemporaryDirectory { root ->
        val bundle = root / "bundle"
        val data = root / "data"
        FileSystem.SYSTEM.createDirectories(bundle)
        val descriptor = descriptor("1.2.3")
        writeBundle(bundle, descriptor)
        var executableCalls = 0
        val installer = RuntimeBundleInstaller(bundle, data, descriptor) { executableCalls++ }

        val first = runBlocking { installer.install() }
        assertEquals("app", first.appServer.readUtf8())
        val firstCalls = executableCalls
        assertEquals(first, runBlocking { installer.install() })
        assertTrue(executableCalls > firstCalls)

        val unexpected = first.appServer.parent!! / "unexpected"
        unexpected.writeUtf8("unexpected")
        runBlocking { installer.install() }
        assertTrue(FileSystem.SYSTEM.metadataOrNull(unexpected) == null)

        first.appServer.writeUtf8("corrupt")
        val repaired = runBlocking { installer.install() }
        assertEquals("app", repaired.appServer.readUtf8())
        assertTrue((FileSystem.SYSTEM.list(data / "runtimes" / "1.2.3")).none { ".corrupt-" in it.name })

        val next = descriptor("1.2.4")
        writeBundle(bundle, next)
        val second = runBlocking { RuntimeBundleInstaller(bundle, data, next) {}.install() }
        assertTrue(first.appServer.parent != second.appServer.parent)
        assertEquals("app", second.appServer.readUtf8())
    }

    @Test
    fun rejectsUnsafeZipShapesAndExtraMembers() {
        val normal = zip(linkedMapOf("one" to byteArrayOf(1), "two" to byteArrayOf(2)))
        val duplicate = normal.copyOf().also { bytes ->
            replaceAscii(bytes, "two", "one")
        }
        assertFailsWith<IllegalArgumentException> { inspectRuntimeZip(duplicate) }

        val traversal = zip(linkedMapOf("safe" to byteArrayOf(1))).also { bytes ->
            replaceAscii(bytes, "safe", "../x")
        }
        assertFailsWith<IllegalArgumentException> { inspectRuntimeZip(traversal) }

        val symlink = zip(linkedMapOf("safe" to byteArrayOf(1))).also { bytes ->
            val central = signatureOffsets(bytes, 0x02014b50).single()
            writeU32(bytes, central + 38, 0xa0000000L)
        }
        assertFailsWith<IllegalArgumentException> { inspectRuntimeZip(symlink) }

        val oversized = zip(linkedMapOf("safe" to byteArrayOf(1))).also { bytes ->
            val central = signatureOffsets(bytes, 0x02014b50).single()
            writeU32(bytes, central + 24, 384L * 1024 * 1024 + 1)
        }
        assertFailsWith<IllegalArgumentException> { inspectRuntimeZip(oversized) }

        assertFailsWith<IllegalArgumentException> {
            parseRuntimeBundleManifest(
                """{"schemaVersion":1,"libraryVersion":"1","appServerVersion":"1","target":"t","classifier":"c","members":[],"unexpected":true}"""
                    .encodeToByteArray(),
            )
        }

        withTemporaryDirectory { root ->
            val bundle = root / "bundle"
            FileSystem.SYSTEM.createDirectories(bundle)
            val descriptor = descriptor("1.2.3")
            writeBundle(bundle, descriptor, "unexpected" to byteArrayOf(1))
            assertFailsWith<IllegalArgumentException> {
                runBlocking { RuntimeBundleInstaller(bundle, root / "data", descriptor) {}.install() }
            }
        }
    }

    @Test
    fun persistsRestoresClearsAndRejectsCorruptWorkspaceSelection() = withTemporaryDirectory { root ->
        val data = root / "data"
        val workspace = root / "workspace"
        FileSystem.SYSTEM.createDirectories(workspace)
        val store = PathWorkspaceStore(data)

        val selected = runBlocking { store.select(CodexPathWorkspaceSelection(workspace.toString())) }
        assertIs<CodexWorkspaceResolution.Available>(selected)
        assertEquals(selected, runBlocking { store.restore() })
        runBlocking { store.clear() }
        val cleared = assertIs<CodexWorkspaceResolution.SelectionRequired>(runBlocking { store.restore() })
        assertEquals(CodexWorkspaceSelectionReason.NOT_SELECTED, cleared.reason)

        FileSystem.SYSTEM.createDirectories(data)
        (data / "workspace.json").writeUtf8("not-json")
        val corrupt = assertIs<CodexWorkspaceResolution.SelectionRequired>(runBlocking { store.restore() })
        assertEquals(CodexWorkspaceSelectionReason.INVALID_SELECTION, corrupt.reason)
    }

    private fun descriptor(version: String) = RuntimeBundleDescriptor(
        libraryVersion = version,
        appServerVersion = "0.116.0",
        target = "testTarget",
        classifier = "test-classifier",
        appServerName = "codex",
        appServerSha256 = sha256("app".encodeToByteArray()),
        supervisorName = "supervisor",
    )

    private fun writeBundle(
        directory: Path,
        descriptor: RuntimeBundleDescriptor,
        extra: Pair<String, ByteArray>? = null,
    ) {
        val members = linkedMapOf(
            descriptor.appServerName to "app".encodeToByteArray(),
            descriptor.supervisorName to "supervisor".encodeToByteArray(),
            "openai-codex-LICENSE.txt" to "license".encodeToByteArray(),
            "openai-codex-NOTICE.txt" to "notice".encodeToByteArray(),
        )
        val manifest = buildString {
            append("""{"schemaVersion":1,"libraryVersion":"${descriptor.libraryVersion}",""")
            append(""""appServerVersion":"${descriptor.appServerVersion}",""")
            append(""""target":"${descriptor.target}","classifier":"${descriptor.classifier}","members":[""")
            members.entries.forEachIndexed { index, (name, bytes) ->
                if (index > 0) append(',')
                append("""{"name":"$name","size":${bytes.size},"sha256":"${sha256(bytes)}"""")
                append(",\"executable\":")
                append(name == descriptor.appServerName || name == descriptor.supervisorName)
                append('}')
            }
            append("]}")
        }.encodeToByteArray()
        members["codex-runtime-manifest.json"] = manifest
        if (extra != null) members[extra.first] = extra.second
        val archive = directory /
            "codex-agent-runtime-desktop-${descriptor.libraryVersion}-${descriptor.classifier}.zip"
        FileSystem.SYSTEM.write(archive) { write(zip(members)) }
    }

    private fun zip(entries: LinkedHashMap<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun replaceAscii(bytes: ByteArray, from: String, to: String) {
        require(from.length == to.length)
        val source = from.encodeToByteArray()
        val replacement = to.encodeToByteArray()
        for (index in 0..bytes.size - source.size) {
            if (source.indices.all { bytes[index + it] == source[it] }) {
                replacement.copyInto(bytes, index)
            }
        }
    }

    private fun signatureOffsets(bytes: ByteArray, signature: Int): List<Int> =
        (0..bytes.size - 4).filter { index ->
            (0..3).all { byte -> bytes[index + byte].toInt() and 0xff == signature ushr (byte * 8) and 0xff }
        }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        repeat(4) { bytes[offset + it] = (value ushr (it * 8)).toByte() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun Path.readUtf8(): String = FileSystem.SYSTEM.read(this) { readUtf8() }
    private fun Path.writeUtf8(value: String) = FileSystem.SYSTEM.write(this) { writeUtf8(value) }

    private fun withTemporaryDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("codex-runtime-installer-").toOkioPath()
        try {
            block(root)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }
}
