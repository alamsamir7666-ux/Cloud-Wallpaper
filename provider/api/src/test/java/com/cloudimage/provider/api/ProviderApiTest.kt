package com.cloudimage.provider.api

import kotlinx.coroutines.test.runTest
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

    /**
     * The album paradigm's contract surface (v1.1.0) is additive: a
     * provider compiled against the plain V1 interface — no album methods
     * overridden — must compile, load and answer the defaults, so old
     * packages keep working on the new host and [ProviderApi.VERSION]
     * stays 1.
     */
    @Test
    fun albumMethodsHaveEmptyDefaults() =
        runTest {
            val plain =
                object : WallpaperProvider {
                    override val meta = ProviderMeta(id = "cloudimage.plain", name = "Plain", versionName = "1.0.0")
                    override val capabilities = setOf(Capability.POPULAR)

                    override suspend fun popular(
                        page: Int,
                        filters: Filters,
                    ) = Result.success(Page(emptyList(), null))

                    override suspend fun search(
                        query: String,
                        page: Int,
                        filters: Filters,
                    ) = Result.success(Page(emptyList(), null))

                    override suspend fun details(id: String) = Result.success(WallpaperDetails(wallpaper.copy(id = id)))

                    override suspend fun random(): Result<List<Wallpaper>> = Result.success(emptyList())
                }

            assertTrue(plain.categories().isEmpty())
            assertTrue(plain.homeAlbums().getOrThrow().isEmpty())
            assertTrue(plain.albums("anime").getOrThrow().isEmpty())
            assertTrue(plain.albumWallpapers("attack-on-titan").getOrThrow().isEmpty())
            assertTrue(plain.searchAlbums("naruto").getOrThrow().isEmpty())
        }

    @Test
    fun categoryAndAlbumCarryTheirIdentity() {
        val category =
            Category(id = "anime", name = "Anime", iconEmoji = "💥", coverUrl = "https://example.com/cover.jpg")
        val album =
            Album(
                id = "attack-on-titan",
                providerId = "cloudimage.wallpaperaccess",
                title = "Attack On Titan",
                coverUrl = "https://example.com/thumb/36626.jpg",
                wallpaperCount = 70,
            )

        assertEquals("anime", category.id)
        assertEquals("💥", category.iconEmoji)
        assertNull(Category(id = "other", name = "Other").iconEmoji)
        assertEquals(0, Album(id = "x", providerId = "p", title = "X", coverUrl = "c").wallpaperCount)
        assertEquals("attack-on-titan", album.id)
        assertEquals(70, album.wallpaperCount)
    }
}
