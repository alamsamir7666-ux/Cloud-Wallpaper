package com.cloudimage.feature.browse

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.data.repository.SourceFailure
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.repository.SourceSection
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.HistoryEntry
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.WallpaperSorting
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.core.testing.FakeHistoryRepository
import com.cloudimage.core.testing.FakeWallpaperSources
import com.cloudimage.core.testing.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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

    /** History fed to the personal row (v1.0.9); fresh per test method. */
    private val history = FakeHistoryRepository()

    @Test
    fun recentlyAppliedRowShowsAppliesOnlyNewestFirstDeduped() =
        runTest {
            val fake = FakeWallpaperSources()
            val newest = fakeWallpaper("a1")
            val reapplied = fakeWallpaper("a1")
            val oldest = fakeWallpaper("a2")
            history.setEntries(
                listOf(
                    HistoryEntry(newest, HistoryAction.APPLIED, atMillis = 5L),
                    HistoryEntry(fakeWallpaper("v1"), HistoryAction.VIEWED, atMillis = 4L),
                    HistoryEntry(reapplied, HistoryAction.APPLIED, atMillis = 3L),
                    HistoryEntry(fakeWallpaper("d1"), HistoryAction.DOWNLOADED, atMillis = 2L),
                    HistoryEntry(oldest, HistoryAction.APPLIED, atMillis = 1L),
                ),
            )

            val viewModel = newViewModel(fake, backgroundScope)
            val state = viewModel.state.first { it.recentlyApplied.isNotEmpty() }

            assertEquals(listOf("a1", "a2"), state.recentlyApplied.map { it.id })
        }

    @Test
    fun recentlyAppliedRowCapsAtOneCarousel() =
        runTest {
            val fake = FakeWallpaperSources()
            // Newest first, like the Room DAO orders them (the fake returns
            // entries in insertion order, so we insert in that convention).
            history.setEntries(
                (12 downTo 1).map { index ->
                    HistoryEntry(fakeWallpaper("w$index"), HistoryAction.APPLIED, atMillis = index.toLong())
                },
            )

            val viewModel = newViewModel(fake, backgroundScope)
            val state = viewModel.state.first { it.recentlyApplied.isNotEmpty() }

            assertEquals(10, state.recentlyApplied.size)
            // The row keeps the newest ten — w12 down to w3 — and drops
            // the two oldest.
            assertFalse(state.recentlyApplied.any { it.id == "w1" || it.id == "w2" })
            assertEquals("w12", state.recentlyApplied.first().id)
        }

    @Test
    fun recentlyAppliedRowStaysHiddenWithoutApplies() =
        runTest {
            val fake = FakeWallpaperSources()
            history.setEntries(
                listOf(
                    HistoryEntry(fakeWallpaper("v1"), HistoryAction.VIEWED, atMillis = 2L),
                    HistoryEntry(fakeWallpaper("d1"), HistoryAction.DOWNLOADED, atMillis = 1L),
                ),
            )

            val viewModel = newViewModel(fake, backgroundScope)
            advanceUntilIdle()

            val settled = viewModel.state.value
            assertTrue(settled.recentlyApplied.isEmpty())
        }

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
                    historyRepository = history,
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
                    historyRepository = history,
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
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences, historyRepository = history)
            viewModel.state.first { it.sourceBar.isNotEmpty() && !it.isFirstLoading }

            fake.enqueueSearch(page(ids = listOf("px1"), nextPage = null))
            viewModel.onSourceSelected("pexels")
            val state = viewModel.state.first { it.selectedSourceId == "pexels" && !it.isFirstLoading }

            assertEquals(listOf("px1"), state.wallpapers.map { it.id })
            assertEquals("pexels", fake.searchSourceIds.last())
            assertEquals(1, fake.searchCalls.last().second)
            assertEquals("pexels", preferences.preferences.first().browseSourceId)
            // No key needed — no hint on the menu item.
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

            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences, historyRepository = history)

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
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences, historyRepository = history)
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
            // The key hint left the menu item too.
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
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences, historyRepository = history)
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
            // One source left — the selector stays and names the survivor.
            assertEquals(listOf("wallhaven"), recovered.sourceBar.map { it.id })
        }

    @Test
    fun aSingleSourceStillShowsTheSelectorNamingIt() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.enqueueSearch(page(ids = listOf("w1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)

            // The selector doubles as feed provenance, so it shows with a
            // single usable source too (its menu also links to Extensions).
            val state = viewModel.state.first { it.sourceBar.isNotEmpty() && !it.isFirstLoading }

            assertEquals(listOf(BrowseSource("wallhaven", "Wallhaven", needsApiKey = false)), state.sourceBar)
        }

    // ---- Home sections (v1.0.9) ----

    private fun section(
        sourceId: String = "wallhaven",
        sectionId: String,
        title: String,
        query: WallpaperQuery = WallpaperQuery(),
        isDefault: Boolean = false,
    ): SourceSection =
        SourceSection(
            sourceId = sourceId,
            sourceName = "Wallhaven",
            sectionId = sectionId,
            title = title,
            query = query,
            isDefault = isDefault,
        )

    @Test
    fun initialLoadShowsSectionsWithFirstPagesPerRow() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(
                    listOf(
                        section(sectionId = "trending", title = "Trending", query = WallpaperQuery(sorting = WallpaperSorting.TOPLIST)),
                        section(sectionId = "latest", title = "Latest", query = WallpaperQuery(sorting = WallpaperSorting.DATE)),
                    ),
                ),
            )
            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = null))
            fake.enqueueSearch(page(ids = listOf("l1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)

            val state = viewModel.state.first { !it.isFirstLoading }

            assertEquals(BrowseMode.SECTIONS, state.mode)
            assertEquals(listOf("wallhaven:trending", "wallhaven:latest"), state.sections.map { it.key })
            assertEquals(listOf("Wallhaven · Trending", "Wallhaven · Latest"), state.sections.map { it.title })
            val populated =
                viewModel.state.first { it.sections.isNotEmpty() && it.sections.all { s -> !s.isFirstLoading } }
            assertEquals(listOf("t1"), populated.sections[0].wallpapers.map { it.id })
            assertEquals(listOf("l1"), populated.sections[1].wallpapers.map { it.id })
            // Each row loads pinned to its own source with its own preset.
            assertEquals(listOf("wallhaven", "wallhaven"), fake.searchSourceIds.take(2))
            assertEquals(
                listOf(WallpaperSorting.TOPLIST, WallpaperSorting.DATE),
                fake.searchCalls.take(2).map { it.first.sorting },
            )
            assertEquals(listOf(1, 1), fake.searchCalls.take(2).map { it.second })
        }

    @Test
    fun mergedViewTitlesDefaultRowsWithTheSourceName() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(
                    listOf(section(sectionId = "popular", title = "Popular", isDefault = true)),
                ),
            )
            fake.enqueueSearch(page(ids = listOf("w1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)

            val state = viewModel.state.first { !it.isFirstLoading }

            // The default "Popular" row reads as the source itself — not a
            // home full of identical "Popular" headers.
            assertEquals(listOf("Wallhaven"), state.sections.map { it.title })
        }

    @Test
    fun sectionPaginationAppendsAndStops() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(listOf(section(sectionId = "trending", title = "Trending"))),
            )
            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = 2))
            fake.enqueueSearch(page(ids = listOf("t2"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first {
                it.sections
                    .singleOrNull()
                    ?.wallpapers
                    ?.isNotEmpty() == true
            }

            viewModel.loadMoreSection("wallhaven:trending")
            val done =
                viewModel.state.first {
                    it.sections
                        .singleOrNull()
                        ?.wallpapers
                        ?.size == 2
                }

            assertTrue(done.sections.single().endReached)
            viewModel.loadMoreSection("wallhaven:trending")
            assertEquals(2, fake.searchCalls.size)
            assertEquals(listOf(1, 2), fake.searchCalls.map { it.second })
            assertEquals(
                listOf("t1", "t2"),
                done.sections
                    .single()
                    .wallpapers
                    .map { it.id },
            )
        }

    @Test
    fun sectionFirstPageFailureRetriesPageOne() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(listOf(section(sectionId = "trending", title = "Trending"))),
            )
            fake.enqueueSearch(NetworkResult.Failure(NetworkError.Timeout))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { it.sections.singleOrNull()?.error != null }

            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = null))
            viewModel.loadMoreSection("wallhaven:trending")
            val recovered =
                viewModel.state.first {
                    it.sections
                        .singleOrNull()
                        ?.wallpapers
                        ?.isNotEmpty() == true
                }

            // A failed FIRST page retries page 1 — there is nothing to
            // append onto, so advancing the cursor would skip content.
            assertEquals(listOf(1, 1), fake.searchCalls.map { it.second })
            assertNull(recovered.sections.single().error)
        }

    @Test
    fun seeAllOpensTheGridScopedToTheSectionAndBackReturnsHome() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(
                    listOf(
                        section(
                            sectionId = "trending",
                            title = "Trending",
                            query = WallpaperQuery(sorting = WallpaperSorting.TOPLIST),
                        ),
                    ),
                ),
            )
            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first {
                it.sections
                    .singleOrNull()
                    ?.wallpapers
                    ?.isNotEmpty() == true
            }

            fake.enqueueSearch(page(ids = listOf("g1", "g2"), nextPage = null))
            viewModel.onSeeAll("wallhaven:trending")
            val scoped = viewModel.state.first { it.wallpapers.isNotEmpty() }

            assertEquals(BrowseMode.GRID, scoped.mode)
            assertEquals("Wallhaven · Trending", scoped.scopeTitle)
            assertEquals(WallpaperSorting.TOPLIST, scoped.query.sorting)
            // The scoped grid loads pinned to the section's source with the
            // section's preset, from page 1.
            assertEquals("wallhaven", fake.searchSourceIds.last())
            assertEquals(
                WallpaperSorting.TOPLIST,
                fake.searchCalls
                    .last()
                    .first.sorting,
            )
            assertEquals(1, fake.searchCalls.last().second)

            viewModel.onBackToSections()
            val home = viewModel.state.first { it.mode == BrowseMode.SECTIONS }

            // Rows keep their state; the grid is cleared.
            assertEquals(
                listOf("t1"),
                home.sections
                    .single()
                    .wallpapers
                    .map { it.id },
            )
            assertTrue(home.wallpapers.isEmpty())
            assertNull(home.scopeTitle)
            assertTrue(home.query.isDefault)
        }

    // ---- Home tab bar (v1.0.10) ----

    @Test
    fun homeTabsLeadWithThePersonalTabAndDefaultToIt() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(
                    listOf(
                        section(sectionId = "trending", title = "Trending"),
                        section(sectionId = "latest", title = "Latest"),
                    ),
                ),
            )
            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = null))
            fake.enqueueSearch(page(ids = listOf("l1"), nextPage = null))
            history.setEntries(
                listOf(HistoryEntry(fakeWallpaper("a1"), HistoryAction.APPLIED, atMillis = 1L)),
            )
            val viewModel = newViewModel(fake, backgroundScope)

            val state =
                viewModel.state.first {
                    it.sections.isNotEmpty() && it.recentlyApplied.isNotEmpty()
                }

            assertEquals(
                listOf(RECENTLY_APPLIED_TAB_KEY, "wallhaven:trending", "wallhaven:latest"),
                state.homeTabs.map { it.key },
            )
            // The personal tab carries no title — the UI owns its localized
            // string; section tabs carry the composed row titles.
            assertNull(state.homeTabs.first().title)
            assertEquals(
                listOf("Wallhaven · Trending", "Wallhaven · Latest"),
                state.homeTabs.drop(1).map { it.title },
            )
            // The default active tab on load is Recently applied.
            assertEquals(RECENTLY_APPLIED_TAB_KEY, state.selectedHomeTab?.key)
        }

    @Test
    fun homeTabsSkipThePersonalTabWithoutAppliesAndDefaultToTheFirstSection() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(
                    listOf(section(sectionId = "trending", title = "Trending")),
                ),
            )
            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)

            val state = viewModel.state.first { it.sections.isNotEmpty() }

            assertEquals(listOf("wallhaven:trending"), state.homeTabs.map { it.key })
            assertEquals("wallhaven:trending", state.selectedHomeTab?.key)
        }

    @Test
    fun selectingAHomeTabMovesTheSelectionAndIgnoresUnknownKeys() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(
                    listOf(
                        section(sectionId = "trending", title = "Trending"),
                        section(sectionId = "latest", title = "Latest"),
                    ),
                ),
            )
            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = null))
            fake.enqueueSearch(page(ids = listOf("l1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { it.sections.isNotEmpty() }

            viewModel.onHomeTabSelected("wallhaven:latest")

            val selection = viewModel.state.value.selectedHomeTab
            assertEquals("wallhaven:latest", selection?.key)
            assertEquals("wallhaven:latest", viewModel.state.value.homeTabKey)

            // Unknown keys cannot aim the bar at a ghost tab.
            viewModel.onHomeTabSelected("wallhaven:ghost")
            val afterGhost = viewModel.state.value.selectedHomeTab
            assertEquals("wallhaven:latest", afterGhost?.key)

            // A repeat of the current pick is a no-op, not a state churn.
            viewModel.onHomeTabSelected("wallhaven:latest")
            val afterRepeat = viewModel.state.value.selectedHomeTab
            assertEquals("wallhaven:latest", afterRepeat?.key)
        }

    @Test
    fun vanishedHomeTabSelectionFallsBackToTheHead() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(
                SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false),
                SourceInfo("pexels", "Pexels", requiresApiKey = false),
            )
            fake.setSectionsResult(
                NetworkResult.Success(
                    listOf(
                        section(sourceId = "wallhaven", sectionId = "trending", title = "Trending"),
                        section(sourceId = "wallhaven", sectionId = "latest", title = "Latest"),
                    ),
                ),
            )
            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = null))
            fake.enqueueSearch(page(ids = listOf("l1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { it.sections.isNotEmpty() }

            viewModel.onHomeTabSelected("wallhaven:latest")
            val picked = viewModel.state.value.selectedHomeTab
            assertEquals("wallhaven:latest", picked?.key)

            // The sections re-declare without the picked one (a source
            // switch): the pick degrades to the bar's head, not a dead index.
            fake.setSectionsResult(
                NetworkResult.Success(
                    listOf(section(sourceId = "pexels", sectionId = "curated", title = "Curated")),
                ),
            )
            fake.enqueueSearch(page(ids = listOf("px1"), nextPage = null))
            viewModel.onSourceSelected("pexels")
            val pinned = viewModel.state.first { it.selectedSourceId == "pexels" && it.sections.isNotEmpty() }

            assertEquals("pexels:curated", pinned.selectedHomeTab?.key)
        }

    @Test
    fun searchLeavesTheHomeAndClearingItReturns() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(listOf(section(sectionId = "trending", title = "Trending"))),
            )
            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first {
                it.sections
                    .singleOrNull()
                    ?.wallpapers
                    ?.isNotEmpty() == true
            }

            fake.enqueueSearch(page(ids = listOf("s1"), nextPage = null))
            viewModel.onSearchTextChange("nature")
            viewModel.onSearchSubmit()
            val searching = viewModel.state.first { it.wallpapers.isNotEmpty() }

            assertEquals(BrowseMode.GRID, searching.mode)
            assertNull(searching.scopeTitle)

            viewModel.onSearchTextChange("")
            viewModel.onSearchSubmit()
            val home = viewModel.state.first { it.mode == BrowseMode.SECTIONS }

            assertEquals(
                listOf("t1"),
                home.sections
                    .single()
                    .wallpapers
                    .map { it.id },
            )
        }

    @Test
    fun selectingASourceReloadsItsSections() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(
                SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false),
                SourceInfo("pexels", "Pexels", requiresApiKey = false),
            )
            fake.setSectionsResult(
                NetworkResult.Success(
                    listOf(
                        section(sourceId = "wallhaven", sectionId = "trending", title = "Trending"),
                    ),
                ),
            )
            fake.enqueueSearch(page(ids = listOf("w1"), nextPage = null))
            val preferences = newPreferences()
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences, historyRepository = history)
            viewModel.state.first {
                it.sections.isNotEmpty() &&
                    it.sections
                        .single()
                        .wallpapers
                        .isNotEmpty()
            }

            fake.setSectionsResult(
                NetworkResult.Success(
                    listOf(
                        section(sourceId = "pexels", sectionId = "curated", title = "Curated", isDefault = false),
                    ),
                ),
            )
            fake.enqueueSearch(page(ids = listOf("px1"), nextPage = null))
            viewModel.onSourceSelected("pexels")
            val pinned = viewModel.state.first { it.selectedSourceId == "pexels" && it.sections.isNotEmpty() }

            // Pinned mode keeps the provider's own titles, not the merged
            // "Source · Section" composition.
            assertEquals(listOf("Curated"), pinned.sections.map { it.title })
            assertEquals("pexels", fake.sectionsCalls.last())
            assertEquals("pexels", fake.searchSourceIds.last())
        }

    @Test
    fun degenerateEmptySectionsFallBackToTheFlatGrid() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.enqueueSearch(page(ids = listOf("f1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)

            // No sections declared anywhere: the v1.0.8 flat merged feed.
            val state = viewModel.state.first { !it.isFirstLoading }

            assertEquals(BrowseMode.GRID, state.mode)
            assertEquals(listOf("f1"), state.wallpapers.map { it.id })
        }

    @Test
    fun sfwFlipReloadsTheSections() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(listOf(section(sectionId = "trending", title = "Trending"))),
            )
            fake.enqueueSearch(page(ids = listOf("a"), nextPage = null))
            val preferences = newPreferences()
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences, historyRepository = history)
            viewModel.state.first {
                it.sections
                    .singleOrNull()
                    ?.wallpapers
                    ?.isNotEmpty() == true
            }

            fake.setSectionsResult(
                NetworkResult.Success(listOf(section(sectionId = "trending", title = "Trending"))),
            )
            fake.enqueueSearch(page(ids = listOf("b"), nextPage = null))
            preferences.setSfwOnly(false)
            val state =
                viewModel.state.first {
                    it.sections
                        .singleOrNull()
                        ?.wallpapers
                        ?.map { it.id } == listOf("b")
                }

            assertEquals(BrowseMode.SECTIONS, state.mode)
        }

    // ---- Search UX (v1.0.9) ----

    /** Drives the Main dispatcher's clock past the debounce windows. */
    private fun advanceMs(millis: Long) {
        mainDispatcherRule.testDispatcher.scheduler.apply {
            advanceTimeBy(millis)
            runCurrent()
        }
    }

    /**
     * Lets a DataStore write land on both clocks: the test scheduler runs
     * the write, the Main scheduler runs the ViewModel collector that
     * receives it. They are separate clocks under MainDispatcherRule.
     */
    private fun TestScope.settle() {
        advanceUntilIdle()
        advanceMs(0)
    }

    @Test
    fun `typing searches by itself after a pause without recording history`() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(listOf(section(sectionId = "trending", title = "Trending"))),
            )
            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { !it.isFirstLoading }

            fake.enqueueSearch(page(ids = listOf("nat1"), nextPage = null))
            viewModel.onSearchTextChange("nature")
            // Suggestions land first (200ms), the search at 450ms — the home
            // is still up while the chips would show.
            advanceMs(250)
            assertEquals(BrowseMode.SECTIONS, viewModel.state.value.mode)
            assertEquals(1, fake.searchCalls.size)
            assertEquals(listOf("nature"), fake.suggestCalls)

            advanceMs(250)

            val state = viewModel.state.first { it.wallpapers.isNotEmpty() }
            assertEquals(BrowseMode.GRID, state.mode)
            assertEquals("nature", state.query.text)
            assertEquals(
                "nature",
                fake.searchCalls
                    .last()
                    .first.text,
            )
            // Live search commits are NOT history — only explicit ones are.
            assertTrue(state.history.isEmpty())
        }

    @Test
    fun `clearing the text by pausing returns to the home`() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(SourceInfo("wallhaven", "Wallhaven", requiresApiKey = false))
            fake.setSectionsResult(
                NetworkResult.Success(listOf(section(sectionId = "trending", title = "Trending"))),
            )
            fake.enqueueSearch(page(ids = listOf("t1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first {
                it.sections
                    .singleOrNull()
                    ?.wallpapers
                    ?.isNotEmpty() == true
            }

            fake.enqueueSearch(page(ids = listOf("s1"), nextPage = null))
            viewModel.onSearchTextChange("nature")
            advanceMs(600)
            viewModel.state.first { it.mode == BrowseMode.GRID && it.wallpapers.isNotEmpty() }

            viewModel.onSearchTextChange("")
            advanceMs(600)

            val home = viewModel.state.first { it.mode == BrowseMode.SECTIONS }
            assertTrue(home.query.isDefault)
        }

    @Test
    fun `submitting records the search and repeats do not duplicate it`() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = listOf("a"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { !it.isFirstLoading }

            fake.enqueueSearch(page(ids = listOf("b"), nextPage = null))
            viewModel.onSearchTextChange("nature")
            viewModel.onSearchSubmit()
            viewModel.state.first { it.wallpapers.map { it.id } == listOf("b") }
            settle()

            assertEquals(listOf("nature"), viewModel.state.value.history)

            // The debounced commit was cancelled by the submit.
            assertEquals(2, fake.searchCalls.size)

            fake.enqueueSearch(page(ids = listOf("c"), nextPage = null))
            viewModel.onSearchSubmit()
            viewModel.state.first { it.wallpapers.map { it.id } == listOf("c") }
            settle()

            assertEquals(listOf("nature"), viewModel.state.value.history)
        }

    @Test
    fun `tag suggestions land while typing and a chip commits the search`() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.enqueueSearch(page(ids = listOf("a"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)
            viewModel.state.first { !it.isFirstLoading }

            fake.scriptedSuggestions = listOf("landscape", "land art")
            viewModel.onSearchTextChange("land")
            advanceMs(250)

            assertEquals(listOf("landscape", "land art"), viewModel.state.value.suggestions)
            assertEquals(listOf("land"), fake.suggestCalls)

            fake.enqueueSearch(page(ids = listOf("s1"), nextPage = null))
            viewModel.onSuggestionSelected("landscape")
            val state = viewModel.state.first { it.wallpapers.isNotEmpty() }

            assertEquals(BrowseMode.GRID, state.mode)
            assertEquals("landscape", state.query.text)
            assertTrue(state.suggestions.isEmpty())
            settle()
            assertEquals(listOf("landscape"), viewModel.state.value.history)
        }

    @Test
    fun `a history row re-runs its search and records it as most recent`() =
        runTest {
            val fake = FakeWallpaperSources()
            val preferences = newPreferences()
            preferences.addSearchQuery("nature")
            preferences.addSearchQuery("space")
            fake.enqueueSearch(page(ids = listOf("a"), nextPage = null))
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences, historyRepository = history)
            viewModel.state.first { it.history == listOf("space", "nature") }

            fake.enqueueSearch(page(ids = listOf("s1"), nextPage = null))
            viewModel.onHistorySelected("nature")
            viewModel.state.first { it.wallpapers.isNotEmpty() }
            settle()

            assertEquals("nature", viewModel.state.value.query.text)
            assertEquals(listOf("nature", "space"), viewModel.state.value.history)
        }

    @Test
    fun `the search panel follows focus and history`() =
        runTest {
            val fake = FakeWallpaperSources()
            val preferences = newPreferences()
            preferences.addSearchQuery("nature")
            fake.enqueueSearch(page(ids = listOf("a"), nextPage = null))
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences, historyRepository = history)
            viewModel.state.first { it.history.isNotEmpty() }

            viewModel.onSearchFocusChange(true)
            assertTrue(viewModel.state.value.showSearchPanel)

            viewModel.onSearchFocusChange(false)
            assertFalse(viewModel.state.value.showSearchPanel)
        }

    @Test
    fun `partial source failures surface in the grid state`() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(
                SourceInfo("cloudimage.a", "A", requiresApiKey = false),
                SourceInfo("cloudimage.b", "B", requiresApiKey = false),
            )
            fake.scriptedSourceFailures = listOf(SourceFailure("cloudimage.b", "B", NetworkError.Timeout))
            fake.enqueueSearch(page(ids = listOf("a1"), nextPage = null))
            val viewModel = newViewModel(fake, backgroundScope)

            val state = viewModel.state.first { !it.isFirstLoading }

            assertTrue(state.showSourceFailureChip)
            assertEquals(listOf(FailedSource("B", BrowseError.TIMEOUT)), state.sourceFailures)
            // The surviving results still show.
            assertEquals(listOf("a1"), state.wallpapers.map { it.id })

            // Retry re-runs the merged search and clears the chip when all answer.
            fake.scriptedSourceFailures = emptyList()
            fake.enqueueSearch(page(ids = listOf("a2"), nextPage = null))
            viewModel.onRetrySearch()
            val recovered = viewModel.state.first { it.wallpapers.map { it.id } == listOf("a2") }

            assertFalse(recovered.showSourceFailureChip)
        }

    @Test
    fun `a pinned search with no results offers the merged feed`() =
        runTest {
            val fake = FakeWallpaperSources()
            fake.setSources(
                SourceInfo("cloudimage.a", "A", requiresApiKey = false),
                SourceInfo("cloudimage.b", "B", requiresApiKey = false),
            )
            val preferences = newPreferences()
            preferences.setBrowseSourceId("cloudimage.a")
            fake.enqueueSearch(page(ids = emptyList(), nextPage = null))
            val viewModel = BrowseViewModel(sources = fake, userPreferencesRepository = preferences, historyRepository = history)

            val state = viewModel.state.first { !it.isFirstLoading }

            assertEquals("cloudimage.a", state.selectedSourceId)
            assertTrue(state.showTryAllSourcesCta)

            fake.enqueueSearch(page(ids = listOf("m1"), nextPage = null))
            viewModel.onSourceSelected(null)
            val merged = viewModel.state.first { it.wallpapers.isNotEmpty() }

            assertFalse(merged.showTryAllSourcesCta)
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
        return BrowseViewModel(sources = fake, userPreferencesRepository = preferences, historyRepository = history)
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
