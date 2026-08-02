package io.github.ciurlaro.codexmobile.appserver.runtime

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RuntimeConfigurationSecurityTest {
    @Test
    fun environmentMergesPlatformFactsWithoutChangingCommonSecurityValues() {
        val environment = buildMinimalRuntimeEnvironment(
            platform = mapOf(
                "PATH" to "/trusted/bin",
                "LANG" to "en_US.UTF-8",
                "LD_LIBRARY_PATH" to "/platform/lib",
            ),
            applicationDirectory = "/private/home".toPath(),
            temporaryDirectory = "/private/tmp".toPath(),
            codexHome = "/private/codex".toPath(),
            certificateBundle = "/private/codex/system-ca.pem".toPath(),
            proxyUrl = "http://codex:token@127.0.0.1:1234",
        )

        assertEquals("/trusted/bin", environment["PATH"])
        assertEquals("/platform/lib", environment["LD_LIBRARY_PATH"])
        assertEquals("en_US.UTF-8", environment["LANG"])
        assertEquals("http://codex:token@127.0.0.1:1234", environment["HTTPS_PROXY"])
        assertFalse("HTTP_PROXY" in environment)
    }

    @Test
    fun environmentRejectsInvalidAndCommonOwnedPlatformValues() {
        fun build(platform: Map<String, String>) = buildMinimalRuntimeEnvironment(
            platform = platform,
            applicationDirectory = "/private/home".toPath(),
            temporaryDirectory = "/private/tmp".toPath(),
            codexHome = "/private/codex".toPath(),
            certificateBundle = "/private/codex/system-ca.pem".toPath(),
            proxyUrl = "http://codex:token@127.0.0.1:1234",
        )

        listOf("HOME", "HTTPS_PROXY", "http_proxy", "SSL_CERT_DIR").forEach { name ->
            assertFailsWith<IllegalArgumentException> { build(mapOf(name to "unsafe")) }
        }
        assertFailsWith<IllegalArgumentException> { build(mapOf("BAD=KEY" to "value")) }
        assertFailsWith<IllegalArgumentException> { build(mapOf("PATH" to "bad\u0000value")) }
    }

    @Test
    fun certificatesAndBinaryHashUsePortableFiles() {
        val directory =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "codex-runtime-configuration-${Random.nextLong()}"
        val codexHome = directory / "codex"
        FileSystem.SYSTEM.createDirectories(codexHome)
        try {
            val first = (directory / "a.pem").also { it.write("first") }
            val second = (directory / "b.pem").also { it.write("second") }

            val bundle = prepareRuntimeCertificateBundle(listOf(second, first), codexHome)

            assertEquals("first\nsecond\n", bundle.read())
            val binary = (directory / "binary").also { it.write("abc") }
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                binary.sha256(),
            )
        } finally {
            FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
        }
    }

    private fun Path.write(value: String) {
        FileSystem.SYSTEM.sink(this).buffer().use { it.writeUtf8(value) }
    }

    private fun Path.read(): String {
        return FileSystem.SYSTEM.source(this).buffer().use {
            it.readByteArray().decodeToString(throwOnInvalidSequence = true)
        }
    }
}
