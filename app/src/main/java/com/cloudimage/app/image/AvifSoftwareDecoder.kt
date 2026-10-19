package com.cloudimage.app.image

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.request.Options
import org.aomedia.avif.android.AvifDecoder
import java.nio.ByteBuffer

/**
 * Software AVIF decoding for the image pipeline below Android 12 (v1.2.28).
 *
 * ## Why this exists
 *
 * The wallpaper sources this app scrapes are converting their card-sized
 * preview tiers to modern formats: the v1.2.28 live audit of
 * wallpapercave.com measured its `/fuwp/` user-upload previews on a topic
 * page at **10/12 AVIF + 2/12 WebP — not a single JPEG left** — while the
 * grid cards ride exactly that family. Android only decodes AVIF from
 * API 31 (Android 12); on the API 26–30 devices this app also supports,
 * every such card failed to decode and rendered as the gray error
 * painter — a grid that "doesn't show any images".
 *
 * Coil 2 ships no first-party AVIF decoder (its
 * [coils default decoder][coil.decode.BitmapFactoryDecoder] hands every
 * mime type to the platform), so this adapter wraps the Alliance for
 * Open Media's own libavif JNI binding ([AvifDecoder]), which decodes
 * straight into a [Bitmap].
 *
 * ## Scope
 *
 * - **Below API 31** the factory claims AVIF sources — by Content-Type,
 *   or by sniffing the `ftyp` box when the server mislabels — and
 *   [decode] re-verifies the bytes before touching the native library.
 *   Stills only: an animated AVIF answers its first frame, which is the
 *   honest thumbnail.
 * - **API 31 and up** the factory declines, and the platform's own
 *   BitmapFactory path — faster, hardware-backed — takes over.
 * - Failure is total and quiet: any step that cannot produce a bitmap
 *   answers `null`, handing the source back to the decode chain's
 *   honest failure path rather than crashing.
 */
class AvifSoftwareDecoder private constructor(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {
    override suspend fun decode(): DecodeResult? {
        val bytes = runCatching { source.source().readByteArray() }.getOrNull() ?: return null
        if (!headerLooksLikeAvif(bytes.copyOf(FTYP_HEADER_BYTES))) return null

        // libavif's JNI reads through GetDirectBufferAddress, so the bytes
        // must live in a direct buffer, not on the JVM heap.
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.rewind()

        val info = AvifDecoder.Info()
        if (!AvifDecoder.getInfo(buffer, bytes.size, info)) return null
        if (info.width <= 0 || info.height <= 0) return null

        buffer.rewind()
        val bitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        if (!AvifDecoder.decode(buffer, bytes.size, bitmap)) return null

        return DecodeResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = false,
        )
    }

    /**
     * Claims AVIF sources below API 31. Registered ahead of the default
     * BitmapFactory decoder by [com.cloudimage.app.CloudimageApplication];
     * on Android 12+ it declines in favor of the platform decoders.
     */
    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: coil.ImageLoader,
        ): Decoder? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
            val looksLikeAvif =
                result.mimeType == MIME_AVIF || runCatching { sniffsAvif(result) }.getOrDefault(false)
            return if (looksLikeAvif) AvifSoftwareDecoder(result.source, options) else null
        }

        override fun equals(other: Any?): Boolean = other is Factory

        override fun hashCode(): Int = FACTORY_HASH
    }

    internal companion object {
        internal const val MIME_AVIF = "image/avif"

        /** The `ftyp` box header: size(4) + "ftyp"(4) + major brand(4). */
        internal const val FTYP_HEADER_BYTES = 12

        private const val FACTORY_HASH = 0x41F1F

        private val FTYP = "ftyp".toByteArray()
        private val AVIF_BRAND = "avif".toByteArray()
        private val AVIS_BRAND = "avis".toByteArray()

        /**
         * True when the bytes open with an ISO-BMFF `ftyp` box whose major
         * brand is `avif` or `avis` — the still and sequence brands, exactly
         * what the v1.2.28 audit caught wallpapercave serving. `mif1` alone
         * is deliberately NOT claimed: it is the generic HEIF brand, and
         * claiming it would race the platform's own HEIF handling.
         */
        internal fun headerLooksLikeAvif(header: ByteArray): Boolean {
            if (header.size < FTYP_HEADER_BYTES) return false
            if (!header.regionEquals(4, 8, FTYP)) return false
            return header.regionEquals(8, 12, AVIF_BRAND) || header.regionEquals(8, 12, AVIS_BRAND)
        }

        private fun ByteArray.regionEquals(
            from: Int,
            until: Int,
            expected: ByteArray,
        ): Boolean {
            if (until - from != expected.size) return false
            for (i in expected.indices) {
                if (this[from + i] != expected[i]) return false
            }
            return true
        }

        /** Peeks the stream's first bytes without consuming them. */
        private fun sniffsAvif(result: SourceResult): Boolean {
            val peeked = result.source.source().peek()
            val header = ByteArray(FTYP_HEADER_BYTES)
            var read = 0
            while (read < FTYP_HEADER_BYTES) {
                val n = peeked.read(header, read, FTYP_HEADER_BYTES - read)
                if (n == -1) break
                read += n
            }
            return read == FTYP_HEADER_BYTES && headerLooksLikeAvif(header)
        }
    }
}
