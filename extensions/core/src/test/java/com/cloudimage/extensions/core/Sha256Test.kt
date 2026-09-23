package com.cloudimage.extensions.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Sha256Test {
    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun hashesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.of("abc".toByteArray()),
        )
    }

    @Test
    fun fileHashMatchesByteHash() {
        val file = tempDir.newFile("payload.zip")
        file.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(Sha256.of(byteArrayOf(1, 2, 3)), Sha256.of(file))
    }

    @Test
    fun outputIsSixtyFourLowercaseHexChars() {
        assertTrue(Sha256.of(ByteArray(0)).matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun differentInputsProduceDifferentDigests() {
        assertNotEquals(Sha256.of("a".toByteArray()), Sha256.of("b".toByteArray()))
    }

    @Test
    fun largeFileHashingSurvivesChunkBoundaries() {
        val file = tempDir.newFile("large.zip")
        file.writeBytes(ByteArray(200 * 1024) { (it % 251).toByte() })

        assertEquals(
            Sha256.of(file.readBytes()),
            Sha256.of(file),
        )
    }
}
