package com.cloudimage.app.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AVIF byte sniff (v1.2.28): the factory claims sources by Content-Type
 * or by reading the ISO-BMFF `ftyp` box's major brand. The brand test is
 * the load-bearing half — servers mislabel more often than formats change
 * their magic — and it is what keeps the software decoder from claiming
 * plain HEIF (`mif1`) or letting JPEG/PNG/WebP fall through to it.
 */
class AvifSoftwareDecoderTest {
    /** An `ftyp` box with the given fourcc major brand, wallpapercave's shape. */
    private fun ftyp(brand: String): ByteArray {
        val header = ByteArray(16)
        // box size (28) then "ftyp" then the brand then minor version 0.
        header[0] = 0
        header[1] = 0
        header[2] = 0
        header[3] = 28
        "ftyp".toByteArray().copyInto(header, 4)
        brand.toByteArray().copyInto(header, 8)
        return header
    }

    @Test
    fun claimsTheStillBrand() {
        assertTrue(AvifSoftwareDecoder.headerLooksLikeAvif(ftyp("avif")))
    }

    @Test
    fun claimsTheSequenceBrand() {
        assertTrue(AvifSoftwareDecoder.headerLooksLikeAvif(ftyp("avis")))
    }

    @Test
    fun refusesTheGenericHeifBrand() {
        // mif1 is HEIF-generic: claiming it would race the platform's own
        // HEIF handling below API 28.
        assertFalse(AvifSoftwareDecoder.headerLooksLikeAvif(ftyp("mif1")))
    }

    @Test
    fun refusesOtherBoxes() {
        assertFalse(AvifSoftwareDecoder.headerLooksLikeAvif(ftyp("heic")))
        assertFalse(AvifSoftwareDecoder.headerLooksLikeAvif(ftyp("mp42")))
        assertFalse(AvifSoftwareDecoder.headerLooksLikeAvif(ftyp("isom")))
    }

    @Test
    fun refusesOtherFormats() {
        // JPEG: ff d8 ff e0 ... "JFIF"
        assertFalse(AvifSoftwareDecoder.headerLooksLikeAvif(byteArrayOf(-1, -40, -1, -32, 0, 16, 74, 70, 73, 70, 0, 1)))
        // PNG: 89 50 4e 47 0d 0a 1a 0a
        val png = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13)
        assertFalse(AvifSoftwareDecoder.headerLooksLikeAvif(png))
        // RIFF....WEBP
        assertFalse(
            AvifSoftwareDecoder.headerLooksLikeAvif(
                "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(),
            ),
        )
    }

    @Test
    fun refusesTruncatedHeaders() {
        assertFalse(AvifSoftwareDecoder.headerLooksLikeAvif(ftyp("avif").copyOf(8)))
        assertFalse(AvifSoftwareDecoder.headerLooksLikeAvif(ByteArray(0)))
    }

    @Test
    fun headerWidthMatchesTheBox() {
        // The sniff reads exactly 12 bytes — size(4) + "ftyp"(4) + brand(4).
        assertEquals(12, AvifSoftwareDecoder.FTYP_HEADER_BYTES)
    }

    @Test
    fun theAuditSampleDecodesAsAvif() {
        // The literal bytes wallpapercave served as image/avif during the
        // v1.2.28 audit: box size 28, ftyp, brand "avif", minor 0, compat
        // null + "mif1". Pins the sniff against the real thing.
        val auditBytes =
            byteArrayOf(
                0,
                0,
                0,
                28,
                0x66,
                0x74,
                0x79,
                0x70,
                0x61,
                0x76,
                0x69,
                0x66,
                0,
                0,
                0,
                0,
                0x6d,
                0x69,
                0x66,
                0x31,
            )
        assertTrue(AvifSoftwareDecoder.headerLooksLikeAvif(auditBytes))
    }
}
