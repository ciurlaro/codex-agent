package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

class NodeWasmPlatformTest {
    @Test
    fun rejectsRelativeExecutableBeforeLaunch() {
        val error = assertFailsWith<IllegalStateException> {
            validateNodeLaunch(
                NodeCodexRuntimeConfiguration(
                    "codex-app-server".toPath(),
                    ".".toPath(),
                    "codex-process-supervisor".toPath(),
                    "0".repeat(64),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("must be absolute"))
    }

    @Test
    fun typedNodeAdapterUsesHostFilesystem() {
        val directory = nodeTemporaryDirectory("codex-agent-wasm-adapter-")
        try {
            val file = nodeJoinPath(directory, "probe")
            nodeWriteFile(file, "probe")

            assertTrue(nodeHost.isFile(file))
            assertEquals("probe", nodeHost.baseName(file))
            assertEquals(nodeHost.resolvePath(file), nodeHost.realPath(file))
            assertEquals(64, nodeHost.sha256(file).length)
            nodeHost.requireExecutable(file)
        } finally {
            nodeRemoveDirectory(directory)
        }
    }
}
