package com.cloudimage.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderApiTest {
    private val wallpaper =
        Wallpaper(
            id = "42",
            providerId = "wallhaven",
            thumbUrl = "https://example.com/thumb.jpg",
            fullUrl = "https://example.com/full.jpg",
        )

    @Test
    fun pageExposesWallpapersAndNextPageCursor() {
        val page = Page(wallpapers = listOf(wallpaper), nextPage = 2)

        assertEquals(1, page.wallpapers.size)
        assertEquals(2, page.nextPage)
        assertTrue(page.hasNext)
    }

    @Test
    fun pageWithoutCursorHasNoNextPage() {
        val page = Page(wallpapers = emptyList(), nextPage = null)

        assertNull(page.nextPage)
        assertFalse(page.hasNext)
    }

    @Test
    fun aspectRatioComputedFromDimensions() {
        val wide = wallpaper.copy(width = 3840, height = 2160)
        val unknown = wallpaper

        assertEquals(16f / 9f, wide.aspectRatio!!, 0.0001f)
        assertNull(unknown.aspectRatio)
    }

    @Test
    fun providerApiVersionStaysPositive() {
        assertTrue(ProviderApi.VERSION > 0)
    }
}
