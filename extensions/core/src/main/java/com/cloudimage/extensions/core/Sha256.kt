package com.cloudimage.extensions.core

import java.io.File
import java.security.MessageDigest

/**
 * SHA-256 hashing for extension package verification.
 *
 * Digests are reported as 64 lower-case hex characters, the same format
 * extension repositories publish in their indexes, so verification is a
 * plain string comparison.
 */
object Sha256 {
    private const val BUFFER_BYTES = 64 * 1024
    private const val HEX_DIGITS = "0123456789abcdef"

    /** Hex digest of [bytes]. */
    fun of(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Streams [file] through the digest — constant memory for any package size. */
    fun of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    private fun hex(bytes: ByteArray): String =
        buildString(bytes.size * 2) {
            for (byte in bytes) {
                append(HEX_DIGITS[(byte.toInt() shr 4) and 0xf])
                append(HEX_DIGITS[byte.toInt() and 0xf])
            }
        }
}
