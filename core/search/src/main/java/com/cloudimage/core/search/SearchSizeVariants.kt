package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.roundToInt

/** Which standard slot a download variant fills. */
enum class SearchVariantName {
    SMALL,
    MEDIUM,
    LARGE,
    HD,
    ORIGINAL,
}

/**
 * One downloadable size of a search result.
 *
 * Every tier but [SearchVariantName.ORIGINAL] is served by the backend's
 * image proxy, which fetches the original once and resizes it server-side —
 * the phone never pays for pixels it will not show. [estKb] is the same
 * rough JPEG estimate the backend computes (0 when unknowable).
 */
data class SearchSizeVariant(
    val name: SearchVariantName,
    val width: Int,
    val height: Int,
    val downloadUrl: String,
    val isOriginal: Boolean,
    val estKb: Int,
)

/** True for the wallpapers the global search mints. */
fun Wallpaper.isGlobalSearchResult(): Boolean = providerId == GLOBAL_SEARCH_PROVIDER_ID

/**
 * The download sizes a search result offers — the backend system's own
 * "more sizes" contract, mirrored client-side:
 *
 * - the standard widths (Small 320 / Medium 640 / Large 1024 / HD 1920),
 *   each capped at the original (never an upscale) with the height kept
 *   at the image's true aspect ratio;
 * - the Original itself, untouched, always last and always offered.
 *
 * Results whose dimensions the engine could not verify have no honest
 * smaller sizes to offer, so they expose the Original alone. Non-search
 * wallpapers expose nothing — their sources serve exactly one file.
 */
fun buildSizeVariants(
    wallpaper: Wallpaper,
    baseUrl: String,
): List<SearchSizeVariant> {
    if (!wallpaper.isGlobalSearchResult()) return emptyList()
    val original =
        SearchSizeVariant(
            name = SearchVariantName.ORIGINAL,
            width = wallpaper.width ?: 0,
            height = wallpaper.height ?: 0,
            downloadUrl = wallpaper.fullUrl,
            isOriginal = true,
            estKb = estimateKb(wallpaper.width, wallpaper.height),
        )
    val originWidth = wallpaper.width
    val originHeight = wallpaper.height
    if (originWidth == null || originHeight == null || originWidth <= 0 || originHeight <= 0) {
        return listOf(original)
    }
    val base = baseUrl.trimEnd('/')
    val standard =
        STANDARD_WIDTHS.mapNotNull { (width, name) ->
            if (width > originWidth) return@mapNotNull null
            val height = (width.toDouble() * originHeight / originWidth).roundToInt().coerceAtLeast(1)
            SearchSizeVariant(
                name = name,
                width = width,
                height = height,
                downloadUrl = proxyUrl(base, wallpaper.fullUrl, width),
                isOriginal = false,
                estKb = estimateKb(width, height),
            )
        }
    return standard + original
}

/**
 * The wallpaper as it must be handed to the saver to download [variant]:
 * the proxy URL in place of the full URL (and the variant's dimensions,
 * so records stay truthful), everything else — id, provider, thumbnail —
 * untouched, because a size choice never changes the image's identity.
 * The Original returns the receiver as-is.
 */
fun Wallpaper.withSizeVariant(variant: SearchSizeVariant): Wallpaper =
    if (variant.isOriginal) {
        this
    } else {
        copy(fullUrl = variant.downloadUrl, width = variant.width, height = variant.height)
    }

/** The backend proxy's contract: `?url=&w=&q=&fmt=`, JPEG at quality 90. */
private fun proxyUrl(
    base: String,
    url: String,
    width: Int,
): String = "$base/$PROXY_PATH?url=${encodeQueryValue(url)}&w=$width&q=$JPEG_QUALITY&fmt=jpeg"

/**
 * Percent-encoding for one query value — [URLEncoder]'s form-data `+` for
 * spaces is not what `URLSearchParams` on the backend decodes, so spaces
 * travel as `%20` instead.
 */
private fun encodeQueryValue(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** The backend's own estimate: ~0.5 bytes per pixel at JPEG quality ~90, floored at 8 KB. */
private fun estimateKb(
    width: Int?,
    height: Int?,
): Int {
    if (width == null || height == null || width <= 0 || height <= 0) return 0
    return max(8, (width.toDouble() * height * BYTES_PER_PIXEL / 1024).roundToInt())
}

private val STANDARD_WIDTHS =
    listOf(
        320 to SearchVariantName.SMALL,
        640 to SearchVariantName.MEDIUM,
        1024 to SearchVariantName.LARGE,
        1920 to SearchVariantName.HD,
    )

private const val PROXY_PATH = "api/proxy-image"
private const val JPEG_QUALITY = 90
private const val BYTES_PER_PIXEL = 0.5
