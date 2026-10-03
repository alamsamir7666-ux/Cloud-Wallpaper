package com.cloudimage.feature.search

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.search.GLOBAL_SEARCH_PROVIDER_ID
import com.cloudimage.core.search.GlobalSearchFilters
import com.cloudimage.core.search.ImageSearchEngine
import com.cloudimage.core.search.ImageSearchError
import com.cloudimage.core.search.ImageSearchPage
import com.cloudimage.core.search.ImageSearchResult
import com.cloudimage.core.search.SearchSizeTier
import com.cloudimage.core.testing.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The global search state machine: commits, pagination with cross-page
 * dedup, the barren-page walk, tier re-runs, history and failures.
 */
class SearchViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val tmpFolder: TemporaryFolder = TemporaryFolder.builder().assureDeletion().build()

    private fun newViewModel(
        engine: ImageSearchEngine,
        scope: kotlinx.coroutines.CoroutineScope,
    ): SearchViewModel {
        val preferences =
            UserPreferencesRepository(
                PreferenceDataStoreFactory.create(scope = scope) {
                    tmpFolder.newFile("preferences_${System.nanoTime()}.preferences_pb")
                },
            )
        return SearchViewModel(engine = engine, userPreferencesRepository = preferences)
    }

    @Test
    fun initialStateIsIdle() =
        runTest {
            val viewModel = newViewModel(FakeEngine(), backgroundScope)

            val state = viewModel.state.first { it.gridColumns == 2 }

            assertTrue(state.isIdle)
            assertNull(state.activeQuery)
            assertTrue(state.results.isEmpty())
            assertTrue(state.history.isEmpty())
        }

    @Test
    fun committedSearchRecordsHistoryAndShowsResults() =
        runTest {
            val engine = FakeEngine()
            engine.script = { _, page, _ ->
                ImageSearchResult.Success(
                    ImageSearchPage(
                        results = listOf(result("a"), result("b")),
                        hasMore = true,
                        page = page,
                        relatedSearches = listOf("mountain 4k"),
                    ),
                )
            }
            val viewModel = newViewModel(engine, backgroundScope)

            viewModel.onQueryChange("mountain")
            viewModel.onSearchSubmit()

            val state = viewModel.state.first { it.results.isNotEmpty() }
            assertEquals("mountain", state.activeQuery)
            assertEquals(listOf("a", "b"), state.results.map { it.id })
            assertTrue(state.hasMore)
            assertEquals(listOf("mountain 4k"), state.relatedSearches)
            assertNull(state.error)
            assertEquals("mountain", engine.calls.single().first)
            assertEquals(1, engine.calls.single().second)
            assertTrue(
                viewModel.state
                    .first { it.history.contains("mountain") }
                    .history
                    .contains("mountain"),
            )
        }

    @Test
    fun blankCommitsAreIgnored() =
        runTest {
            val engine = FakeEngine()
            val viewModel = newViewModel(engine, backgroundScope)

            viewModel.onQueryChange("   ")
            viewModel.onSearchSubmit()

            assertEquals(0, engine.calls.size)
            assertTrue(viewModel.state.value.isIdle)
        }

    @Test
    fun loadMoreAppendsAcrossPagesAndDedups() =
        runTest {
            val engine = FakeEngine()
            engine.script = { _, page, _ ->
                val ids =
                    when (page) {
                        1 -> listOf("a", "b")
                        else -> listOf("b", "c") // "b" again: DDG pagination overlaps
                    }
                ImageSearchResult.Success(
                    ImageSearchPage(results = ids.map { result(it) }, hasMore = page == 1, page = page),
                )
            }
            val viewModel = newViewModel(engine, backgroundScope)
            viewModel.onQueryChange("mountain")
            viewModel.onSearchSubmit()
            viewModel.state.first { it.results.isNotEmpty() }

            viewModel.loadMore()

            val state = viewModel.state.first { !it.isLoadingMore }
            assertEquals(listOf("a", "b", "c"), state.results.map { it.id })
            assertEquals(2, engine.calls.size)
            assertEquals(2, engine.calls[1].second)
        }

    @Test
    fun barrenPageWalksOnUntilSomethingNewLands() =
        runTest {
            val engine = FakeEngine()
            engine.script = { _, page, _ ->
                // Pages 1-2 are all-filtered (empty) while the RAW batch still
                // says more; page 3 finally lands something new.
                val ids =
                    when (page) {
                        1, 2 -> emptyList()
                        else -> listOf("a")
                    }
                ImageSearchResult.Success(
                    ImageSearchPage(results = ids.map { result(it) }, hasMore = true, page = page),
                )
            }
            val viewModel = newViewModel(engine, backgroundScope)
            viewModel.onQueryChange("mountain")
            viewModel.onSearchSubmit()

            val state = viewModel.state.first { engine.calls.size >= 3 }

            assertEquals(listOf("a"), state.results.map { it.id })
            assertEquals(listOf(1, 2, 3), engine.calls.map { it.second })
        }

    @Test
    fun failureSurfacesTheError() =
        runTest {
            val engine = FakeEngine()
            engine.script = { _, _, _ ->
                ImageSearchResult.Failure(ImageSearchError.RATE_LIMITED, "the engine is busy")
            }
            val viewModel = newViewModel(engine, backgroundScope)

            viewModel.onQueryChange("mountain")
            viewModel.onSearchSubmit()

            val state = viewModel.state.first { it.error != null }
            assertEquals(ImageSearchError.RATE_LIMITED, state.error)
            assertEquals("the engine is busy", state.errorDetail)
            assertTrue(state.results.isEmpty())
        }

    @Test
    fun loadMoreWithoutMorePagesDoesNothing() =
        runTest {
            val engine = FakeEngine()
            engine.script = { _, page, _ ->
                ImageSearchResult.Success(
                    ImageSearchPage(results = listOf(result("a")), hasMore = false, page = page),
                )
            }
            val viewModel = newViewModel(engine, backgroundScope)
            viewModel.onQueryChange("mountain")
            viewModel.onSearchSubmit()
            viewModel.state.first { it.results.isNotEmpty() }

            viewModel.loadMore()
            viewModel.loadMore()

            assertEquals(1, engine.calls.size)
        }

    @Test
    fun pickingATierReRunsTheActiveSearch() =
        runTest {
            val engine = FakeEngine()
            engine.script = { _, page, _ ->
                ImageSearchResult.Success(
                    ImageSearchPage(results = listOf(result("a")), hasMore = false, page = page),
                )
            }
            val viewModel = newViewModel(engine, backgroundScope)
            viewModel.onQueryChange("mountain")
            viewModel.onSearchSubmit()
            viewModel.state.first { it.results.isNotEmpty() }

            viewModel.onSizeTierSelected(SearchSizeTier.UHD)

            // The tier flag flips synchronously; the re-search rides a
            // DataStore write first, so wait for the call itself.
            val state = viewModel.state.first { engine.calls.size >= 2 }
            assertEquals(SearchSizeTier.UHD, engine.calls[1].third.sizeTier)
            assertEquals(SearchSizeTier.UHD, state.sizeTier)
            assertTrue(state.results.isNotEmpty())
        }

    @Test
    fun historySelectionSearchesVerbatim() =
        runTest {
            val engine = FakeEngine()
            engine.script = { _, page, _ ->
                ImageSearchResult.Success(
                    ImageSearchPage(results = listOf(result("a")), hasMore = false, page = page),
                )
            }
            val viewModel = newViewModel(engine, backgroundScope)

            viewModel.onSuggestionSelected("neon city")

            val state = viewModel.state.first { it.results.isNotEmpty() }
            assertEquals("neon city", state.activeQuery)
            assertEquals("neon city", engine.calls.single().first)
        }

    @Test
    fun clearingHistoryEmptiesTheIdleScreen() =
        runTest {
            val engine = FakeEngine()
            val preferences =
                UserPreferencesRepository(
                    PreferenceDataStoreFactory.create(scope = backgroundScope) {
                        tmpFolder.newFile("preferences_${System.nanoTime()}.preferences_pb")
                    },
                )
            preferences.addSearchQuery("cat")
            preferences.addSearchQuery("dog")
            val viewModel = SearchViewModel(engine = engine, userPreferencesRepository = preferences)
            viewModel.state.first { it.history.size == 2 }

            viewModel.onClearHistory()

            assertTrue(
                viewModel.state
                    .first { it.history.isEmpty() }
                    .history
                    .isEmpty(),
            )
        }

    private fun result(id: String): Wallpaper =
        Wallpaper(
            id = id,
            providerId = GLOBAL_SEARCH_PROVIDER_ID,
            thumbUrl = "https://thumbs.example.com/$id",
            fullUrl = "https://images.example.com/$id",
            title = "result $id",
            width = 3840,
            height = 2160,
            sourceUrl = "https://www.example.com/$id",
        )

    /** A scripted engine that records every call. */
    private class FakeEngine : ImageSearchEngine {
        override val name = "fake"

        val calls = mutableListOf<Triple<String, Int, GlobalSearchFilters>>()

        var script: (String, Int, GlobalSearchFilters) -> ImageSearchResult = { _, _, _ ->
            ImageSearchResult.Failure(ImageSearchError.NETWORK)
        }

        override suspend fun search(
            query: String,
            page: Int,
            filters: GlobalSearchFilters,
        ): ImageSearchResult {
            calls += Triple(query, page, filters)
            return script(query, page, filters)
        }
    }
}
