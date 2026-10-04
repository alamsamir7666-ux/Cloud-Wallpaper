package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download-size contract of a search result, mirrored from the backend
 * system's own "more sizes" builder: standard widths capped at the original
 * (never an upscale), heights at the true aspect ratio, the proxy's URL
 * shape, honest estimates, and the identity-preserving wallpaper mapping.
 */
class SearchSizeVariantsTest {
    private val base = "https://bridge.test"

    private fun searchWallpaper(
        width: Int?,
        height: Int?,
    ): Wallpaper =
        Wallpaper(
            id = "gs:https://z-cdn.example.com/mountain.jpg",
            providerId = GLOBAL_SEARCH_PROVIDER_ID,
            thumbUrl = "https://z-cdn.example.com/mountain.jpg",
            fullUrl = "https://z-cdn.example.com/mountain.jpg",
            title = "Unsplash",
            width = width,
            height = height,
        )

    @Test
    fun buildsTheStandardWidthsPlusTheOriginal() {
        val variants = buildSizeVariants(searchWallpaper(2560, 1600), base)

        assertEquals(
            listOf(
                SearchVariantName.SMALL,
                SearchVariantName.MEDIUM,
                SearchVariantName.LARGE,
                SearchVariantName.HD,
                SearchVariantName.ORIGINAL,
            ),
            variants.map { it.name },
        )
        // Heights ride the image's true aspect ratio.
        assertEquals(320 to 200, variants[0].width to variants[0].height)
        assertEquals(640 to 400, variants[1].width to variants[1].height)
        assertEquals(1024 to 640, variants[2].width to variants[2].height)
        assertEquals(1920 to 1200, variants[3].width to variants[3].height)
        assertEquals(2560 to 1600, variants[4].width to variants[4].height)
        // Only the Original is the untouched file.
        assertTrue(variants.take(4).none { it.isOriginal })
        assertTrue(variants.last().isOriginal)
        assertEquals("https://z-cdn.example.com/mountain.jpg", variants.last().downloadUrl)
    }

    @Test
    fun neverUpscales() {
        val variants = buildSizeVariants(searchWallpaper(800, 600), base)

        // 1024 and 1920 exceed the original and are simply not offered.
        assertEquals(listOf(SearchVariantName.SMALL, SearchVariantName.MEDIUM, SearchVariantName.ORIGINAL), variants.map { it.name })
        assertEquals(320 to 240, variants[0].width to variants[0].height)
        assertEquals(640 to 480, variants[1].width to variants[1].height)
    }

    @Test
    fun unverifiedDimensionsOfferOnlyTheOriginal() {
        val variants = buildSizeVariants(searchWallpaper(null, null), base)

        assertEquals(listOf(SearchVariantName.ORIGINAL), variants.map { it.name })
        assertEquals("https://z-cdn.example.com/mountain.jpg", variants.single().downloadUrl)
        // No honest estimate exists without dimensions.
        assertEquals(0, variants.single().estKb)
    }

    @Test
    fun otherProvidersOfferNothing() {
        val providerWallpaper =
            Wallpaper(
                id = "e1abc2",
                providerId = "wallhaven",
                thumbUrl = "https://w.wallhaven.cc/full/e1abc2/large.jpg",
                fullUrl = "https://w.wallhaven.cc/full/e1abc2/original.png",
                width = 3840,
                height = 2160,
            )

        assertTrue(buildSizeVariants(providerWallpaper, base).isEmpty())
        assertTrue(!providerWallpaper.isGlobalSearchResult())
    }

    @Test
    fun proxyUrlsFollowTheBackendsContract() {
        val variants = buildSizeVariants(searchWallpaper(2560, 1600), base)

        assertEquals(
            "https://bridge.test/api/proxy-image?url=https%3A%2F%2Fz-cdn.example.com%2Fmountain.jpg&w=320&q=90&fmt=jpeg",
            variants[0].downloadUrl,
        )
        assertEquals(
            "https://bridge.test/api/proxy-image?url=https%3A%2F%2Fz-cdn.example.com%2Fmountain.jpg&w=1920&q=90&fmt=jpeg",
            variants[3].downloadUrl,
        )
    }

    @Test
    fun estimatesFollowTheBackendsRoughMath() {
        val variants = buildSizeVariants(searchWallpaper(2560, 1600), base)

        // 0.5 bytes per pixel, JPEG: 320x200 -> 31 KB (floored at 8).
        assertEquals(31, variants[0].estKb)
        // The Original estimates off its own pixels: 2560x1600 -> 2000 KB.
        assertEquals(2000, variants.last().estKb)
        assertTrue(variants.all { it.estKb >= 8 || it.estKb == 0 })
    }

    @Test
    fun withSizeVariantSwapsTheUrlAndDimsButNeverTheIdentity() {
        val wallpaper = searchWallpaper(2560, 1600)
        val large = buildSizeVariants(wallpaper, base)[2]

        val target = wallpaper.withSizeVariant(large)

        assertEquals(large.downloadUrl, target.fullUrl)
        assertEquals(1024, target.width)
        assertEquals(640, target.height)
        // Identity, provenance and thumbnail survive a size choice.
        assertEquals(wallpaper.id, target.id)
        assertEquals(wallpaper.providerId, target.providerId)
        assertEquals(wallpaper.thumbUrl, target.thumbUrl)
        assertEquals(wallpaper.title, target.title)
    }

    @Test
    fun theOriginalVariantReturnsTheWallpaperUntouched() {
        val wallpaper = searchWallpaper(2560, 1600)
        val original = buildSizeVariants(wallpaper, base).last()

        assertEquals(wallpaper, wallpaper.withSizeVariant(original))
    }
}
