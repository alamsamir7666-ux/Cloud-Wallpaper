package com.cloudimage.feature.browse

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.WallpaperSorting
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.core.testing.FakeWallpaperSources
import com.cloudimage.core.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BrowseViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    @Test
    fun initialLoadShowsFirstPage() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = listOf("a1", "a2"), nextPage = 2))

            val viewModel = newViewModel(fake, backgroundScope)

            val state = viewModel.state.first { !it.isFirstLoading }
            assertEquals(listOf("a1", "a2"), state.wallpapers.map { it.id })
            assertEquals(1, fake.searchCalls.single().second)
            assertNull(state.error)
        }

    @Test
    fun loadMoreAppendsPagesAndStopsAtTheEnd() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = listOf("p1"), nextPage = 2))
            fake.enqueueSearch(page(ids = listOf("p2"), nextPage = 3))
            fake.enqueueSearch(page(ids = listOf("p3"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { !it.isFirstLoading }

            viewModel.loadMore()
            viewModel.state.first { it.wallpapers.size == 2 }
            viewModel.loadMore()
            viewModel.state.first { it.wallpapers.size == 3 }

            assertTrue(viewModel.state.value.endReached)
            viewModel.loadMore()
            assertEquals(3, fake.searchCalls.size)
            assertEquals(listOf(1, 2, 3), fake.searchCalls.map { it.second })
            assertEquals(
                listOf("p1", "p2", "p3"),
                viewModel.state.value.wallpapers
                    .map { it.id },
            )
        }

    @Test
    fun searchSubmitRestartsFromPageOne() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = listOf("old"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { !it.isFirstLoading }

            fake.enqueueSearch(page(ids = listOf("new1", "new2"), nextPage = null))
            viewModel.onSearchTextChange("nature")
            viewModel.onSearchSubmit()
            viewModel.state.first { it.wallpapers.isNotEmpty() && it.wallpapers.first().id == "new1" }

            assertEquals(
                "nature",
                fake.searchCalls
                    .last()
                    .first.text,
            )
            assertEquals(1, fake.searchCalls.last().second)
            assertEquals(2, fake.searchCalls.size)
        }

    @Test
    fun sfwOnlyClampsRatingsWhileOn() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = listOf("initial"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { !it.isFirstLoading }

            // The user (somehow) selects everything; the VM still sends SFW only.
            fake.enqueueSearch(page(ids = listOf("clamped"), nextPage = null))
            viewModel.onQueryChange(
                WallpaperQuery(
                    contentRatings = setOf(ContentRating.SFW, ContentRating.SKETCHY, ContentRating.NSFW),
                ),
            )
            viewModel.state.first { it.wallpapers.map { it.id } == listOf("clamped") }

            assertEquals(
                setOf(ContentRating.SFW),
                fake.searchCalls
                    .last()
                    .first.contentRatings,
            )
        }

    @Test
    fun turningSfwOffUnlocksSketchyAndRestarts() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = listOf("a"), nextPage = null))
            val preferences = newPreferences()
            val viewModel =
                BrowseViewModel(
                    sources = fake,
                    userPreferencesRepository = preferences,
                )
            viewModel.state.first { !it.isFirstLoading }

            fake.enqueueSearch(page(ids = listOf("b"), nextPage = null))
            preferences.setSfwOnly(false)
            viewModel.state.first { !it.sfwOnly }

            assertEquals(
                setOf(ContentRating.SFW),
                fake.searchCalls
                    .last()
                    .first.contentRatings,
            )

            fake.enqueueSearch(page(ids = listOf("c"), nextPage = null))
            viewModel.onQueryChange(WallpaperQuery(contentRatings = setOf(ContentRating.SFW, ContentRating.SKETCHY)))
            viewModel.state.first { it.wallpapers.map { it.id } == listOf("c") }

            val ratings =
                fake.searchCalls
                    .last()
                    .first.contentRatings
            assertEquals(setOf(ContentRating.SFW, ContentRating.SKETCHY), ratings)
        }

    @Test
    fun nsfwIsNeverSentEvenWhenSfwOnlyIsOff() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = listOf("a"), nextPage = null))
            val preferences = newPreferences()
            val viewModel =
                BrowseViewModel(
                    sources = fake,
                    userPreferencesRepository = preferences,
                )
            viewModel.state.first { !it.isFirstLoading }
            preferences.setSfwOnly(false)
            viewModel.state.first { !it.sfwOnly }

            fake.enqueueSearch(page(ids = listOf("b"), nextPage = null))
            viewModel.onQueryChange(WallpaperQuery(contentRatings = setOf(ContentRating.NSFW)))
            viewModel.state.first { it.wallpapers.map { it.id } == listOf("b") }

            assertEquals(
                setOf(ContentRating.SFW),
                fake.searchCalls
                    .last()
                    .first.contentRatings,
            )
        }

    @Test
    fun randomSortingKeepsOneSeedAcrossPages() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = listOf("initial"), nextPage = null))
            fake.enqueueSearch(page(ids = listOf("r1"), nextPage = 2))
            fake.enqueueSearch(page(ids = listOf("r2"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { !it.isFirstLoading }

            viewModel.onQueryChange(WallpaperQuery(sorting = WallpaperSorting.RANDOM))
            viewModel.state.first { it.wallpapers.map { it.id } == listOf("r1") }
            val firstSeed =
                fake.searchCalls
                    .last()
                    .first.seed
            assertNotNull(firstSeed)

            viewModel.loadMore()
            viewModel.state.first { it.wallpapers.size == 2 }

            assertEquals(
                firstSeed,
                fake.searchCalls
                    .last()
                    .first.seed,
            )
        }

    @Test
    fun errorSurfacesAndRetryRecovers() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(NetworkResult.Failure(NetworkError.Timeout))
            val viewModel = newViewModel(fake, backgroundScope)
            val failed = viewModel.state.first { !it.isFirstLoading }

            assertEquals(BrowseError.TIMEOUT, failed.error)
            assertTrue(failed.showFullscreenError)

            fake.enqueueSearch(page(ids = listOf("fixed"), nextPage = null))
            viewModel.onRetry()
            val recovered = viewModel.state.first { it.wallpapers.isNotEmpty() }

            assertNull(recovered.error)
            assertEquals(listOf("fixed"), recovered.wallpapers.map { it.id })
        }

    @Test
    fun sourceFailureSurfacesAsSourceErrorNotOffline() =
        runTest {
            val fake = FakeWallpaperSources()
            // A plugin that crashed — the v1.0.0 failure mode. Must NOT
            // surface as a connectivity ("offline") error.
            fake.enqueueSearch(NetworkResult.Failure(NetworkError.Source("source failed to load")))
            val viewModel = newViewModel(fake, backgroundScope)
            val failed = viewModel.state.first { !it.isFirstLoading }

            assertEquals(BrowseError.SOURCE, failed.error)
            assertTrue(failed.showFullscreenError)

            fake.enqueueSearch(page(ids = listOf("fixed"), nextPage = null))
            viewModel.onRetry()
            val recovered = viewModel.state.first { it.wallpapers.isNotEmpty() }

            assertNull(recovered.error)
        }

    @Test
    fun loadMoreFailureKeepsListAndRetryAppends() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = listOf("p1"), nextPage = 2))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { !it.isFirstLoading }

            fake.enqueueSearch(NetworkResult.Failure(NetworkError.Io(java.io.IOException())))
            viewModel.loadMore()
            val failed = viewModel.state.first { it.error != null }

            assertEquals(listOf("p1"), failed.wallpapers.map { it.id })
            assertFalse(failed.showFullscreenError)
            assertFalse(failed.isLoadingMore)

            fake.enqueueSearch(page(ids = listOf("p2"), nextPage = null))
            viewModel.onRetry()
            val recovered = viewModel.state.first { it.wallpapers.size == 2 }

            assertNull(recovered.error)
            assertTrue(recovered.endReached)
        }

    @Test
    fun noSourcesEmptyStateSurfacesWhenSourcesDiscoveredEmpty() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = emptyList(), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { !it.isFirstLoading }

            fake.setSources()

            val state = viewModel.state.first { it.sources != null }
            assertTrue(state.showNoSources)
        }

    @Test
    fun sourceArrivalRescuesAFailedFeed() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(NetworkResult.Failure(NetworkError.Io(java.io.IOException())))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { it.error != null && !it.isFirstLoading }

            fake.enqueueSearch(page(ids = listOf("s1"), nextPage = null))
            fake.setSources(SourceInfo("cloudimage.test", "Test", false))

            val recovered = viewModel.state.first { it.wallpapers.isNotEmpty() }

            assertEquals(listOf("s1"), recovered.wallpapers.map { it.id })
            assertNull(recovered.error)
        }

    // ---- Source switcher (v1.0.6) ----

    @Test
    fun selectingASourceRoutesTheFeedToItAndPersists() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(
                SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false),
                SourceInfo("pexels", "Pexels", requiresApiKey = false),
            )
            fake.enqueueSearch(page(ids = listOf("w1"), nextPage = null))
            val preferences = newPreferences()
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences)
            viewModel.state.first { it.sourceBar.isNotEmpty() && !it.isFirstLoading }

            fake.enqueueSearch(page(ids = listOf("px1"), nextPage = null))
            viewModel.onSourceSelected("pexels")
            val state = viewModel.state.first { it.selectedSourceId == "pexels" && !it.isFirstLoading }

            assertEquals(listOf("px1"), state.wallpapers.map { it.id })
            assertEquals("pexels", fake.searchSourceIds.last())
            assertEquals(1, fake.searchCalls.last().second)
            assertEquals("pexels", preferences.preferences.first().browseSourceId)
            // No key needed — no hint on the chip.
            assertFalse(state.sourceBar.single { it.id == "pexels" }.needsApiKey)
        }

    @Test
    fun storedSelectionDrivesTheFeedAfterRecreation() =
        runTest {
            val preferences = newPreferences()
            preferences.setBrowseSourceId("pexels")
            val fake = FakeWallpaperSources()
            fake.setSources(
                SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false),
                SourceInfo("pexels", "Pexels", requiresApiKey = false),
            )
            fake.enqueueSearch(page(ids = listOf("p1"), nextPage = null))

            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences)

            val state = viewModel.state.first { !it.isFirstLoading }
            assertEquals("pexels", state.selectedSourceId)
            assertEquals("pexels", fake.searchSourceIds.single())
            assertEquals(listOf("p1"), state.wallpapers.map { it.id })
        }

    @Test
    fun sourceNeedingAKeyPromptsInsteadOfFailingAndAddingTheKeyRecovers() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(
                SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false),
                SourceInfo("unsplash", "Unsplash", requiresApiKey = true),
            )
            fake.enqueueSearch(page(ids = listOf("w1"), nextPage = null))
            val preferences = newPreferences()
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences)
            viewModel.state.first { !it.isFirstLoading }

            viewModel.onSourceSelected("unsplash")

            val prompted = viewModel.state.first { it.showApiKeyPrompt }
            assertFalse(prompted.isFirstLoading)
            // The guard caught it before any request fired.
            assertEquals(1, fake.searchCalls.size)

            fake.enqueueSearch(page(ids = listOf("u1"), nextPage = null))
            preferences.setProviderApiKey("unsplash", "user-key")
            val recovered = viewModel.state.first { it.wallpapers.map { it.id } == listOf("u1") }

            assertFalse(recovered.showApiKeyPrompt)
            assertEquals("unsplash", fake.searchSourceIds.last())
            // The key hint left the chip too.
            assertFalse(recovered.sourceBar.single { it.id == "unsplash" }.needsApiKey)
        }

    @Test
    fun unpinningFallsBackToTheMergedFeedWhenTheSourceDisappears() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(
                SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false),
                SourceInfo("pexels", "Pexels", requiresApiKey = false),
            )
            fake.enqueueSearch(page(ids = listOf("w1"), nextPage = null))
            val preferences = newPreferences()
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences)
            viewModel.state.first { !it.isFirstLoading }

            fake.enqueueSearch(page(ids = listOf("p1"), nextPage = null))
            viewModel.onSourceSelected("pexels")
            viewModel.state.first { it.selectedSourceId == "pexels" && !it.isFirstLoading }

            // The pinned source is uninstalled while pinned.
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.enqueueSearch(page(ids = listOf("w2"), nextPage = null))

            val recovered = viewModel.state.first { it.selectedSourceId == null && !it.isFirstLoading }

            assertEquals("", preferences.preferences.first().browseSourceId)
            assertNull(fake.searchSourceIds.last())
            assertEquals(listOf("w2"), recovered.wallpapers.map { it.id })
            // One source left — the bar hides instead of offering no choice.
            assertTrue(recovered.sourceBar.isEmpty())
        }

    private fun newViewModel(
        fake: FakeWallpaperSources,
        scope: CoroutineScope,
    ): BrowseViewModel {
        val preferences =
            UserPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) {
                    tmpFolder.newFile("preferences_${System.nanoTime()}.preferences_pb")
                },
            )
        return BrowseViewModel(sources = fake, userPreferencesRepository = preferences)
    }

    private fun TestScope.newPreferences(): UserPreferencesRepository =
        UserPreferencesRepository(
            PreferenceDataStoreFactory.create(scope = backgroundScope) {
                tmpFolder.newFile("preferences_${System.nanoTime()}.preferences_pb")
            },
        )

    private fun fakeWallpaper(
        id: String,
        width: Int = 1920,
        height: Int = 1080,
    ) = com.cloudimage.core.model.Wallpaper(
        id = id,
        providerId = "test",
        thumbUrl = "https://example.test/thumbs/$id.jpg",
        fullUrl = "https://example.test/full/$id.jpg",
        width = width,
        height = height,
    )

    private fun page(
        ids: List<String>,
        nextPage: Int?,
    ): NetworkResult.Success<Page> =
        NetworkResult.Success(
            Page(
                wallpapers =
                    ids.map {
                        fakeWallpaper(it)
                    },
                nextPage = nextPage,
            ),
        )
}
