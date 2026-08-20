package io.github.codex_agent_labs.codexmobile.appserver.generator

import java.nio.file.Files
import kotlin.io.path.createFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class ProtocolGeneratorSupportTest {
    @Test
    fun generatedSourcesAreDeterministicAndRemoveStaleShards() {
        val directory = Files.createTempDirectory("protocol-generator-test")
        try {
            val sources = listOf(
                GeneratedFile("GeneratedProtocolAlpha.kt", "package generated\n\nclass Alpha\n"),
                GeneratedFile("GeneratedProtocolBeta.kt", "package generated\n\nclass Beta\n"),
            )
            writeGeneratedSources(directory.toFile(), sources)
            val first = sources.associate { it.name to directory.resolve(it.name).readText() }
            val stale = directory.resolve("GeneratedProtocolStale.kt").createFile()
            stale.writeText("stale")

            writeGeneratedSources(directory.toFile(), sources.reversed())

            assertEquals(first, sources.associate { it.name to directory.resolve(it.name).readText() })
            assertFalse(Files.exists(stale))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun generatedProtocolTypesAreInternal() {
        val schema = buildJsonObject {
            repeat(40) { index ->
                putJsonObject("Model$index") { put("type", "object") }
            }
            putJsonObject("v2") {}
        }
        val models = ProtocolModels(schema)
        val sources = renderKotlin(
            client = emptyList(),
            server = emptyList(),
            notifications = emptyList(),
            clientNotifications = emptyList(),
            schemaSha256 = "test",
            models = models,
        ) + models.renderFiles()
        val content = sources.joinToString("\n", transform = GeneratedFile::content)

        assertTrue("internal data class AppServerRequestDescriptor" in content)
        assertTrue("internal class Model0" in content)
        assertFalse(Regex("(?m)^public\\s+").containsMatchIn(content))
    }
}
