package io.github.ciurlaro.codexmobile.appserver.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopbackProxyPolicyTest {
    @Test
    fun allowsPublicDestinationsAndRejectsPrivateAndReservedRanges() {
        listOf(
            byteArrayOf(8, 8, 8, 8),
            byteArrayOf(1, 1, 1, 1),
            byteArrayOf(0x26, 0x06, 0x47, 0x00, 0x47, 0x00, 0, 0, 0, 0, 0, 0, 0, 0, 0x11, 0x11),
        ).forEach { assertTrue(it.isPublicProxyAddress()) }

        listOf(
            byteArrayOf(127, 0, 0, 1),
            byteArrayOf(10, 0, 0, 1),
            byteArrayOf(100, 64, 0, 1),
            byteArrayOf(169.toByte(), 254.toByte(), 1, 1),
            byteArrayOf(172.toByte(), 16, 0, 1),
            byteArrayOf(192.toByte(), 168.toByte(), 0, 1),
            byteArrayOf(192.toByte(), 0, 2, 1),
            byteArrayOf(198.toByte(), 51, 100, 1),
            byteArrayOf(203.toByte(), 0, 113, 1),
            ByteArray(15) + byteArrayOf(1),
            byteArrayOf(0xfd.toByte()) + ByteArray(15),
            byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte()) + ByteArray(12),
        ).forEach { assertFalse(it.isPublicProxyAddress()) }
    }
}
