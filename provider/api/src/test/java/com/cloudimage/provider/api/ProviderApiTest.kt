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

    @Test
    fun versionGateOnlyAcceptsExactMatch() {
        assertTrue(ProviderApi.isSupported(ProviderApi.VERSION))
        assertFalse(ProviderApi.isSupported(ProviderApi.VERSION - 1))
        assertFalse(ProviderApi.isSupported(ProviderApi.VERSION + 1))
        assertFalse(ProviderApi.isSupported(0))
        assertFalse(ProviderApi.isSupported(-1))
    }

    @Test
    fun wallpaperDefaultsToSfwRating() {
        assertEquals(ContentRating.SFW, wallpaper.contentRating)
    }

    @Test
    fun responseExposesBodyTextAndHeaderLookup() {
        val response =
            ProviderHttpResponse(
                statusCode = 200,
                headers = mapOf("Content-Type" to listOf("application/json"), "X-Api" to listOf("1")),
                body = "hello".toByteArray(),
            )

        assertTrue(response.isSuccessful)
        assertEquals("hello", response.bodyText)
        assertEquals("application/json", response.header("content-type"))
        assertEquals("1", response.header("X-API"))
        assertNull(response.header("Missing"))
    }

    @Test
    fun responseReportsNonSuccessfulStatuses() {
        val response =
            ProviderHttpResponse(
                statusCode = 404,
                headers = emptyMap(),
                body = "gone".toByteArray(),
            )

        assertFalse(response.isSuccessful)
        assertEquals("gone", response.bodyText)
    }

    @Test
    fun filtersBuildFromPairsAndAccumulateValues() {
        val filters = Filters.of("categories" to "anime", "purity" to "110", "purity" to "100")

        assertFalse(filters.isEmpty)
        assertTrue(filters.isSelected("categories", "anime"))
        assertFalse(filters.isSelected("categories", "nature"))
        assertEquals(setOf("110", "100"), filters.valuesFor("purity"))
        assertTrue(filters.valuesFor("sorting").isEmpty())
    }

    @Test
    fun neutralFiltersAreEmptyAndDroppable() {
        val empty = Filters.of(mapOf("categories" to emptySet<String>()))

        assertTrue(Filters.None.isEmpty)
        assertTrue(empty.isEmpty)
        assertEquals(Filters.None, empty)
    }

    @Test
    fun filtersValueEqualityFollowsSelections() {
        val left = Filters.of("purity" to "110")
        val right = Filters.of(mapOf("purity" to setOf("110")))
        val other = Filters.of("purity" to "100")

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
        assertFalse(left == other)
    }
}
