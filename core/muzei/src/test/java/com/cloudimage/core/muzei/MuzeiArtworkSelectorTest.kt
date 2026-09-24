package com.cloudimage.core.muzei

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.Favorite
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.core.testing.FakeFavoritesRepository
import com.cloudimage.core.testing.FakeWallpaperSources
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class MuzeiArtworkSelectorTest {
    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private fun TestScope.newPreferences(): UserPreferencesRepository =
        UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                tmpFolder.newFile("muzei_${System.nanoTime()}.preferences_pb")
            },
        )

    private fun TestScope.newSelector(
        favorites: FakeFavoritesRepository = FakeFavoritesRepository(),
        sources: FakeWallpaperSources = FakeWallpaperSources(),
        preferences: UserPreferencesRepository = newPreferences(),
    ): FavoriteMuzeiArtworkSelector = FavoriteMuzeiArtworkSelector(favorites, sources, preferences)

    // ---- favorites carousel ----

    @Test
    fun servesFavoritesInSaveOrderFromCursorZero() =
        runTest {
            val favorites = FakeFavoritesRepository()
            favorites.setFavorites(
                listOf(
                    favorite(id = "old", addedAtMillis = 1),
                    favorite(id = "middle", addedAtMillis = 2),
                    favorite(id = "new", addedAtMillis = 3),
                ),
            )
            val sources = FakeWallpaperSources()
            val selector = newSelector(favorites = favorites, sources = sources)

            val batch = selector.nextBatch()

            assertTrue(batch is MuzeiBatch.Artworks)
            assertEquals(
                listOf("old", "middle", "new"),
                (batch as MuzeiBatch.Artworks).wallpapers.map { it.id },
            )
            // Favorites win: the feed is never queried when one exists.
            assertTrue(sources.searchCalls.isEmpty())
        }

    @Test
    fun cursorAdvancesByOneEachLoad() =
        runTest {
            val favorites = FakeFavoritesRepository()
            favorites.setFavorites(
                listOf(
                    favorite(id = "a", addedAtMillis = 1),
                    favorite(id = "b", addedAtMillis = 2),
                    favorite(id = "c", addedAtMillis = 3),
                ),
            )
            val selector = newSelector(favorites = favorites)

            selector.nextBatch()
            val second = selector.nextBatch() as MuzeiBatch.Artworks
            val third = selector.nextBatch() as MuzeiBatch.Artworks

            assertEquals(listOf("b", "c", "a"), second.wallpapers.map { it.id })
            assertEquals(listOf("c", "a", "b"), third.wallpapers.map { it.id })
        }

    @Test
    fun staleCursorWrapsIntoRange() =
        runTest {
            val favorites = FakeFavoritesRepository()
            favorites.setFavorites(
                listOf(
                    favorite(id = "a", addedAtMillis = 1),
                    favorite(id = "b", addedAtMillis = 2),
                    favorite(id = "c", addedAtMillis = 3),
                ),
            )
            val preferences = newPreferences()
            // Three favorites were un-favorited since the last load.
            preferences.setMuzeiCursor(7)
            val selector = newSelector(favorites = favorites, preferences = preferences)

            val batch = selector.nextBatch() as MuzeiBatch.Artworks

            assertEquals(listOf("b", "c", "a"), batch.wallpapers.map { it.id })
        }

    @Test
    fun singleFavoriteServesItselfEveryLoad() =
        runTest {
            val favorites = FakeFavoritesRepository()
            favorites.setFavorites(listOf(favorite(id = "only", addedAtMillis = 1)))
            val selector = newSelector(favorites = favorites)

            val first = selector.nextBatch() as MuzeiBatch.Artworks
            val second = selector.nextBatch() as MuzeiBatch.Artworks

            assertEquals(listOf("only"), first.wallpapers.map { it.id })
            assertEquals(listOf("only"), second.wallpapers.map { it.id })
        }

    @Test
    fun sfwOnlyFiltersNonSfwFavorites() =
        runTest {
            val favorites = FakeFavoritesRepository()
            favorites.setFavorites(
                listOf(
                    favorite(id = "sfw", addedAtMillis = 1),
                    favorite(id = "sketchy", addedAtMillis = 2, rating = ContentRating.SKETCHY),
                    favorite(id = "nsfw", addedAtMillis = 3, rating = ContentRating.NSFW),
                ),
            )
            val selector = newSelector(favorites = favorites)

            val batch = selector.nextBatch() as MuzeiBatch.Artworks

            assertEquals(listOf("sfw"), batch.wallpapers.map { it.id })
        }

    @Test
    fun sfwOffKeepsSketchyButNeverNsfw() =
        runTest {
            val favorites = FakeFavoritesRepository()
            favorites.setFavorites(
                listOf(
                    favorite(id = "sfw", addedAtMillis = 1),
                    favorite(id = "sketchy", addedAtMillis = 2, rating = ContentRating.SKETCHY),
                    favorite(id = "nsfw", addedAtMillis = 3, rating = ContentRating.NSFW),
                ),
            )
            val preferences = newPreferences()
            preferences.setSfwOnly(false)
            val selector = newSelector(favorites = favorites, preferences = preferences)

            val batch = selector.nextBatch() as MuzeiBatch.Artworks

            assertEquals(listOf("sfw", "sketchy"), batch.wallpapers.map { it.id })
        }

    @Test
    fun hugeLibrariesServeTheMostRecentWindow() =
        runTest {
            val favorites = FakeFavoritesRepository()
            favorites.setFavorites(
                (1..501).map { index -> favorite(id = "f$index", addedAtMillis = index.toLong()) },
            )
            val selector = newSelector(favorites = favorites)

            val batch = selector.nextBatch() as MuzeiBatch.Artworks

            assertEquals(FavoriteMuzeiArtworkSelector.MAX_ARTWORKS, batch.wallpapers.size)
            assertEquals("f302", batch.wallpapers.first().id)
            assertEquals("f501", batch.wallpapers.last().id)
        }

    // ---- feed fallback ----

    @Test
    fun emptyFavoritesServeTheDefaultFeed() =
        runTest {
            val sources = FakeWallpaperSources()
            sources.enqueueSearch(
                NetworkResult.Success(
                    Page(
                        wallpapers = listOf(feedWallpaper("w1"), feedWallpaper("w2")),
                        nextPage = 2,
                    ),
                ),
            )
            val selector = newSelector(sources = sources)

            val batch = selector.nextBatch() as MuzeiBatch.Artworks

            assertEquals(listOf("w1", "w2"), batch.wallpapers.map { it.id })
            assertEquals(listOf(1), sources.searchCalls.map { it.second })
        }

    @Test
    fun feedPageFollowsTheNextPageCursorAndWraps() =
        runTest {
            val sources = FakeWallpaperSources()
            sources.enqueueSearch(
                NetworkResult.Success(
                    Page(wallpapers = listOf(feedWallpaper("w1")), nextPage = 3),
                ),
            )
            sources.enqueueSearch(
                NetworkResult.Success(
                    Page(wallpapers = listOf(feedWallpaper("w3")), nextPage = null),
                ),
            )
            sources.enqueueSearch(
                NetworkResult.Success(
                    Page(wallpapers = listOf(feedWallpaper("w1")), nextPage = 2),
                ),
            )
            val selector = newSelector(sources = sources)

            selector.nextBatch()
            selector.nextBatch()
            selector.nextBatch()

            // Page 1, then the cursor said 3, and a null next page wrapped
            // the carousel back to the first page.
            assertEquals(listOf(1, 3, 1), sources.searchCalls.map { it.second })
        }

    @Test
    fun feedQueryHonorsTheSfwSetting() =
        runTest {
            val sources = FakeWallpaperSources()
            sources.enqueueSearch(NetworkResult.Success(Page.EMPTY))
            val preferences = newPreferences()
            preferences.setSfwOnly(false)
            val selector = newSelector(sources = sources, preferences = preferences)

            selector.nextBatch()

            val queried: WallpaperQuery = sources.searchCalls.single().first
            assertEquals(setOf(ContentRating.SFW, ContentRating.SKETCHY), queried.contentRatings)
        }

    @Test
    fun emptyFeedPageReportsEmptyAndResetsThePage() =
        runTest {
            val sources = FakeWallpaperSources()
            sources.enqueueSearch(NetworkResult.Success(Page(wallpapers = emptyList(), nextPage = null)))
            sources.enqueueSearch(
                NetworkResult.Success(
                    Page(wallpapers = listOf(feedWallpaper("w1")), nextPage = 2),
                ),
            )
            val selector = newSelector(sources = sources)

            val first = selector.nextBatch()

            assertEquals(MuzeiBatch.Empty, first)
            // The reset is visible to the next load: page 1 again.
            val second = selector.nextBatch() as MuzeiBatch.Artworks
            assertEquals(listOf("w1"), second.wallpapers.map { it.id })
        }

    @Test
    fun offlineFeedFailureIsRetryable() =
        runTest {
            val sources = FakeWallpaperSources()
            sources.enqueueSearch(NetworkResult.Failure(NetworkError.Io(IOException("offline"))))
            val selector = newSelector(sources = sources)

            val batch = selector.nextBatch()

            assertTrue(batch is MuzeiBatch.Retryable)
        }

    @Test
    fun httpFeedFailureIsRetryable() =
        runTest {
            val sources = FakeWallpaperSources()
            sources.enqueueSearch(NetworkResult.Failure(NetworkError.Http(code = 503, url = "https://example.com")))
            val selector = newSelector(sources = sources)

            val batch = selector.nextBatch()

            assertTrue(batch is MuzeiBatch.Retryable)
        }

    @Test
    fun sourceFailureIsNotRetryable() =
        runTest {
            val sources = FakeWallpaperSources()
            sources.enqueueSearch(NetworkResult.Failure(NetworkError.Source("no sources installed")))
            val selector = newSelector(sources = sources)

            val batch = selector.nextBatch()

            assertEquals(MuzeiBatch.Empty, batch)
        }

    // ---- pure helpers ----

    @Test
    fun rotateByHandlesTheEdges() {
        assertTrue(emptyList<Int>().rotateBy(3).isEmpty())
        assertEquals(listOf(1), listOf(1).rotateBy(5))
        assertEquals(listOf(1, 2, 3), listOf(1, 2, 3).rotateBy(0))
        assertEquals(listOf(3, 1, 2), listOf(1, 2, 3).rotateBy(2))
    }

    @Test
    fun artworkSpecMapsFieldsWithFallbacks() {
        val wallpaper =
            Wallpaper(
                id = "42",
                providerId = "wallhaven",
                thumbUrl = "https://example.com/42-thumb.jpg",
                fullUrl = "https://example.com/42.jpg",
                title = "  ",
                sourceUrl = null,
            )

        val spec = wallpaper.toMuzeiArtworkSpec()

        assertEquals("wallhaven/42", spec.token)
        assertEquals("Untitled", spec.title)
        assertEquals("wallhaven", spec.byline)
        assertEquals("Cloudimage", spec.attribution)
        assertEquals("https://example.com/42.jpg", spec.persistentUri)
        assertNull(spec.webUri)
    }

    @Test
    fun artworkSpecKeepsRealTitlesAndSourceLinks() {
        val wallpaper =
            Wallpaper(
                id = "42",
                providerId = "wallhaven",
                thumbUrl = "https://example.com/42-thumb.jpg",
                fullUrl = "https://example.com/42.jpg",
                title = "Mountain at dusk",
                sourceUrl = "https://wallhaven.cc/w/42",
            )

        val spec = wallpaper.toMuzeiArtworkSpec()

        assertEquals("Mountain at dusk", spec.title)
        assertEquals("https://wallhaven.cc/w/42", spec.webUri)
    }

    private fun favorite(
        id: String,
        addedAtMillis: Long,
        rating: ContentRating = ContentRating.SFW,
    ): Favorite =
        Favorite(
            wallpaper =
                Wallpaper(
                    id = id,
                    providerId = "wallhaven",
                    thumbUrl = "https://example.com/$id-thumb.jpg",
                    fullUrl = "https://example.com/$id.jpg",
                    contentRating = rating,
                ),
            addedAtMillis = addedAtMillis,
        )

    private fun feedWallpaper(id: String): Wallpaper =
        Wallpaper(
            id = id,
            providerId = "wallhaven",
            thumbUrl = "https://example.com/$id-thumb.jpg",
            fullUrl = "https://example.com/$id.jpg",
        )
}
