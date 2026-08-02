package io.github.ciurlaro.codexmobile.appserver.generator

import java.nio.file.Files
import kotlin.io.path.createFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
}
