package io.github.ciurlaro.codexmobile.appserver.runtime

import io.github.ciurlaro.codexmobile.agent.CodexPathWorkspaceSelection
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceResolution
import io.github.ciurlaro.codexmobile.agent.CodexWorkspaceSelectionReason
import io.github.ciurlaro.codexmobile.appserver.runtime.host.NodePathWorkspaceStore
import io.github.ciurlaro.codexmobile.appserver.runtime.host.NodeRuntimeBundleInstaller
import io.github.ciurlaro.codexmobile.appserver.runtime.host.RuntimeBundleDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import okio.Path.Companion.toPath

@OptIn(DelicateCoroutinesApi::class)
class NodeRuntimeBundleInstallerTest {
    @Test
    fun installsRepairsAndPersistsWorkspace() = GlobalScope.promise {
        val root = nodeTemporaryDirectory("codex-agent-node-installer-")
        try {
            val bundle = nodeJoinPath(root, "bundle")
            val data = nodeJoinPath(root, "data")
            val workspace = nodeJoinPath(root, "workspace")
            nodeHost.createDirectories(bundle)
            nodeHost.createDirectories(workspace)
            val descriptor = descriptor()
            val archive = nodeJoinPath(
                bundle,
                "codex-agent-runtime-desktop-${descriptor.libraryVersion}-${descriptor.classifier}.zip",
            )
            nodeHost.writeBytes(archive, storedZip(bundleMembers(descriptor)))

            val installer = NodeRuntimeBundleInstaller(bundle.toPath(), data.toPath(), descriptor)
            val installed = installer.install()
            assertEquals("app", nodeHost.readBytes(installed.appServer.toString()).decodeToString())
            nodeHost.writeBytes(installed.appServer.toString(), "corrupt".encodeToByteArray())
            assertEquals("app", nodeHost.readBytes(installer.install().appServer.toString()).decodeToString())

            val store = NodePathWorkspaceStore(data.toPath())
            val selected = store.select(CodexPathWorkspaceSelection(workspace))
            assertIs<CodexWorkspaceResolution.Available>(selected)
            assertEquals(selected, store.restore())
            store.clear()
            val cleared = assertIs<CodexWorkspaceResolution.SelectionRequired>(store.restore())
            assertEquals(CodexWorkspaceSelectionReason.NOT_SELECTED, cleared.reason)
        } finally {
            nodeRemoveDirectory(root)
        }
    }

    private fun descriptor() = RuntimeBundleDescriptor(
        libraryVersion = "1.2.3",
        appServerVersion = "0.116.0",
        target = "testTarget",
        classifier = "test-classifier",
        appServerName = "codex",
        appServerSha256 = APP_SHA,
        supervisorName = "supervisor",
    )

    private fun bundleMembers(descriptor: RuntimeBundleDescriptor): LinkedHashMap<String, ByteArray> {
        val members = linkedMapOf(
            descriptor.appServerName to "app".encodeToByteArray(),
            descriptor.supervisorName to "supervisor".encodeToByteArray(),
            "openai-codex-LICENSE.txt" to "license".encodeToByteArray(),
            "openai-codex-NOTICE.txt" to "notice".encodeToByteArray(),
        )
        val hashes = mapOf(
            descriptor.appServerName to APP_SHA,
            descriptor.supervisorName to SUPERVISOR_SHA,
            "openai-codex-LICENSE.txt" to LICENSE_SHA,
            "openai-codex-NOTICE.txt" to NOTICE_SHA,
        )
        val manifest = buildString {
            append("""{"schemaVersion":1,"libraryVersion":"${descriptor.libraryVersion}",""")
            append(""""appServerVersion":"${descriptor.appServerVersion}",""")
            append(""""target":"${descriptor.target}","classifier":"${descriptor.classifier}","members":[""")
            members.entries.forEachIndexed { index, (name, bytes) ->
                if (index > 0) append(',')
                append("""{"name":"$name","size":${bytes.size},"sha256":"${hashes.getValue(name)}"""")
                append(",\"executable\":")
                append(name == descriptor.appServerName || name == descriptor.supervisorName)
                append('}')
            }
            append("]}")
        }
        members["codex-runtime-manifest.json"] = manifest.encodeToByteArray()
        return members
    }

    private fun storedZip(entries: LinkedHashMap<String, ByteArray>): ByteArray {
        val output = ByteWriter()
        val offsets = mutableMapOf<String, Int>()
        entries.forEach { (name, bytes) ->
            offsets[name] = output.size
            output.u32(0x04034b50).u16(20).u16(0).u16(0).u16(0).u16(0)
                .u32(0).u32(bytes.size).u32(bytes.size).u16(name.length).u16(0)
                .bytes(name.encodeToByteArray()).bytes(bytes)
        }
        val centralOffset = output.size
        entries.forEach { (name, bytes) ->
            output.u32(0x02014b50).u16(20).u16(20).u16(0).u16(0).u16(0).u16(0)
                .u32(0).u32(bytes.size).u32(bytes.size).u16(name.length).u16(0).u16(0)
                .u16(0).u16(0).u32(0).u32(offsets.getValue(name)).bytes(name.encodeToByteArray())
        }
        val centralSize = output.size - centralOffset
        output.u32(0x06054b50).u16(0).u16(0).u16(entries.size).u16(entries.size)
            .u32(centralSize).u32(centralOffset).u16(0)
        return output.toByteArray()
    }

    private class ByteWriter {
        private val values = mutableListOf<Byte>()
        val size get() = values.size
        fun u16(value: Int) = apply { repeat(2) { values += (value ushr (it * 8)).toByte() } }
        fun u32(value: Int) = apply { repeat(4) { values += (value ushr (it * 8)).toByte() } }
        fun bytes(value: ByteArray) = apply { values.addAll(value.toList()) }
        fun toByteArray() = values.toByteArray()
    }

    private companion object {
        const val APP_SHA = "a172cedcae47474b615c54d510a5d84a8dea3032e958587430b413538be3f333"
        const val SUPERVISOR_SHA = "0834c2d60725ac5902257b3b78dd161ad26d1c0290dbf1e47cc14add5b8c8142"
        const val LICENSE_SHA = "cc1d3b0234846714b0aeda6cc34b057b4305bb83dd447fb88f816efeb59a4e96"
        const val NOTICE_SHA = "9368a7d21e018f64ae3327d2f25cd4d7693b2d85328e4bb680bcfcbd4c26b90e"
    }
}
