package com.cloudimage.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.search.GlobalSearchFilters
import com.cloudimage.core.search.ImageSearchEngine
import com.cloudimage.core.search.ImageSearchError
import com.cloudimage.core.search.ImageSearchPage
import com.cloudimage.core.search.ImageSearchResult
import com.cloudimage.core.search.SearchSizeTier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The global image search (v1.2.0): one field, the whole web behind it.
 *
 * The engine's pagination overlaps and its size bias is fuzzy, so the view
 * model owns three guarantees the raw pages cannot make:
 * - every result id appears at most once across pages (seen-id set),
 * - a page that appended nothing new does not end the scroll — the fetch
 *   walks on to the next page (bounded, so a pathological engine cannot
 *   loop) while `hasMore` still rides the RAW batch,
 * - a new search or a filter change cancels whatever was in flight.
 *
 * Searches commit into the app's shared search history — the same ledger
 * the browse tab reads — because "what did I search for" is one question
 * no matter which field asked it.
 */
@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        private val engine: ImageSearchEngine,
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : ViewModel() {
        data class SearchUiState(
            /** Live text-field content; not committed until submit. */
            val query: String = "",
            /** The query the current results answer; null on the idle screen. */
            val activeQuery: String? = null,
            val sizeTier: SearchSizeTier = SearchSizeTier.ANY,
            val results: List<Wallpaper> = emptyList(),
            val isFirstLoading: Boolean = false,
            val isLoadingMore: Boolean = false,
            val hasMore: Boolean = false,
            val error: ImageSearchError? = null,
            val errorDetail: String? = null,
            val history: List<String> = emptyList(),
            val gridColumns: Int = 2,
        ) {
            val isIdle: Boolean get() = activeQuery == null && !isFirstLoading
            val showEmptyState: Boolean
                get() = activeQuery != null && !isFirstLoading && results.isEmpty() && error == null
        }

        private val _state = MutableStateFlow(SearchUiState())
        val state: StateFlow<SearchUiState> = _state

        /** Result ids already on screen — the cross-page dedup. */
        private val seenIds = HashSet<String>()

        /** The page the next loadMore will fetch. */
        private var nextPage = 1

        private var searchJob: Job? = null

        init {
            viewModelScope.launch { engine.prewarm() }
            viewModelScope.launch {
                combine(
                    userPreferencesRepository.searchHistory,
                    userPreferencesRepository.preferences,
                ) { history, preferences -> history to preferences.gridColumns }
                    .collect { (history, columns) ->
                        _state.update { it.copy(history = history, gridColumns = columns) }
                    }
            }
        }

        fun onQueryChange(text: String) = _state.update { it.copy(query = text) }

        /** Commits the field's text as a search. Blank commits are ignored. */
        fun onSearchSubmit() {
            val query = _state.value.query.trim()
            if (query.isEmpty()) return
            runSearch(query)
        }

        /** A history row: search [text] verbatim. */
        fun onSuggestionSelected(text: String) {
            _state.update { it.copy(query = text) }
            runSearch(text.trim())
        }

        /**
         * Picks a new size tier. With results on screen the query re-runs —
         * a filter that only shaped future searches would read as broken.
         */
        fun onSizeTierSelected(tier: SearchSizeTier) {
            if (tier == _state.value.sizeTier) return
            _state.update { it.copy(sizeTier = tier) }
            _state.value.activeQuery?.let { runSearch(it) }
        }

        fun onRetry() {
            _state.value.activeQuery?.let { runSearch(it) }
        }

        /** The grid's near-the-end hook; guards make it safe to call freely. */
        fun loadMore() {
            val current = _state.value
            if (current.activeQuery == null || current.isFirstLoading || current.isLoadingMore) return
            if (!current.hasMore || current.error != null) return
            continueJob(current.activeQuery, append = true)
        }

        fun onClearHistory() {
            viewModelScope.launch { userPreferencesRepository.clearSearchHistory() }
        }

        private fun runSearch(query: String) {
            searchJob?.cancel()
            seenIds.clear()
            nextPage = 1
            _state.update {
                it.copy(
                    activeQuery = query,
                    results = emptyList(),
                    isFirstLoading = true,
                    isLoadingMore = false,
                    hasMore = false,
                    error = null,
                    errorDetail = null,
                )
            }
            searchJob =
                viewModelScope.launch {
                    userPreferencesRepository.addSearchQuery(query)
                    fetchPages(query)
                }
        }

        private fun continueJob(
            query: String,
            append: Boolean,
        ) {
            searchJob?.cancel()
            searchJob =
                viewModelScope.launch {
                    if (append) _state.update { it.copy(isLoadingMore = true, error = null, errorDetail = null) }
                    fetchPages(query)
                }
        }

        /**
         * Walks the engine's pages until one of them contributes something
         * new — or the walk hits its bounds. The bound exists because the
         * engine legally returns all-filtered pages with `hasMore = true`
         * (raw batch vs. exact tier); an endless walk would spin the
         * footer's spinner forever on a query the tier cannot satisfy.
         */
        private suspend fun fetchPages(query: String) {
            var barrenPages = 0
            while (true) {
                val page = nextPage
                val filters = GlobalSearchFilters(sizeTier = _state.value.sizeTier, safeSearch = true)
                when (val outcome = engine.search(query, page, filters)) {
                    is ImageSearchResult.Success -> {
                        val before = _state.value.results.size
                        appendPage(outcome.page)
                        nextPage = page + 1
                        val appended = _state.value.results.size - before
                        if (appended == 0 && outcome.page.hasMore && barrenPages < MAX_BARREN_PAGES) {
                            barrenPages++
                            continue
                        }
                        return
                    }
                    is ImageSearchResult.Failure -> {
                        _state.update {
                            it.copy(
                                isFirstLoading = false,
                                isLoadingMore = false,
                                error = outcome.error,
                                errorDetail = outcome.detail,
                            )
                        }
                        return
                    }
                }
            }
        }

        private fun appendPage(page: ImageSearchPage) {
            val fresh = page.results.filter { seenIds.add(it.id) }
            _state.update { current ->
                current.copy(
                    results = current.results + fresh,
                    hasMore = page.hasMore,
                    isFirstLoading = false,
                    isLoadingMore = false,
                    error = null,
                    errorDetail = null,
                )
            }
        }

        private companion object {
            /** How many all-duplicate/all-filtered pages the walk will skip past. */
            const val MAX_BARREN_PAGES = 3
        }
    }
