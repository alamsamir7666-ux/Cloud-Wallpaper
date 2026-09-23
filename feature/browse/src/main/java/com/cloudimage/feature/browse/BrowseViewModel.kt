package com.cloudimage.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.WallhavenRepository
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.WallpaperSorting
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.onFailure
import com.cloudimage.core.network.onSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** User-facing error taxonomy for the browse feed. */
enum class BrowseError {
    HTTP,
    TIMEOUT,
    OFFLINE,
    BAD_DATA,
}

/** Immutable snapshot of everything the browse screen renders. */
data class BrowseUiState(
    /** Text currently sitting in the search field; not yet committed. */
    val searchText: String = "",
    /** The committed query driving the feed. */
    val query: WallpaperQuery = WallpaperQuery(),
    val wallpapers: List<Wallpaper> = emptyList(),
    val gridColumns: Int = 2,
    val sfwOnly: Boolean = true,
    val isFirstLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: BrowseError? = null,
) {
    /** True when the filter sheet holds non-default choices. */
    val filtersActive: Boolean get() = !query.isDefault

    /** Zero items + error -> full-screen error; otherwise a footer retry. */
    val showFullscreenError: Boolean
        get() = error != null && wallpapers.isEmpty() && !isFirstLoading
}

/**
 * Drives the browse grid: owns search text, the committed query, and the
 * paging cursor. Preference changes (SFW-only, grid columns) flow in from
 * DataStore and re-shape the feed live.
 */
@HiltViewModel
class BrowseViewModel
    @Inject
    constructor(
        private val wallhavenRepository: WallhavenRepository,
        userPreferencesRepository: UserPreferencesRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(BrowseUiState())
        val state: StateFlow<BrowseUiState> = _state.asStateFlow()

        /** Page cursor for the visible list; 1 after every restart. */
        private var currentPage = 1

        /** Keeps a random sort stable across pages of one session. */
        private var randomSeed: String? = null

        /** The in-flight request; cancelled whenever a new search starts. */
        private var searchJob: Job? = null

        init {
            var preferencesSeen = false
            userPreferencesRepository.preferences
                .onEach { preferences ->
                    val sfwChanged = preferences.sfwOnly != _state.value.sfwOnly
                    _state.update {
                        it.copy(sfwOnly = preferences.sfwOnly, gridColumns = preferences.gridColumns)
                    }
                    // First emission triggers the initial load; later SFW flips
                    // restart the feed because the purity parameter changes.
                    if (!preferencesSeen || sfwChanged) restartSearch()
                    preferencesSeen = true
                }
                .launchIn(viewModelScope)
        }

        /** Typing updates the field only — nothing loads until submit. */
        fun onSearchTextChange(text: String) {
            _state.update { it.copy(searchText = text) }
        }

        /** Commits the search field and restarts the feed from page 1. */
        fun onSearchSubmit() {
            _state.update { it.copy(query = it.query.copy(text = it.searchText)) }
            restartSearch()
        }

        /** Commits a new filter set from the sheet and restarts the feed. */
        fun onQueryChange(query: WallpaperQuery) {
            _state.update { it.copy(query = query.copy(text = it.query.text)) }
            restartSearch()
        }

        /** Appends the next page when the grid approaches its end. */
        fun loadMore() {
            val current = _state.value
            if (searchJob?.isActive == true || current.endReached || current.isLoadingMore) return
            val page = currentPage + 1
            searchJob =
                viewModelScope.launch {
                    _state.update { it.copy(isLoadingMore = true) }
                    wallhavenRepository.search(effectiveQuery(), page)
                        .onSuccess { result ->
                            currentPage = page
                            _state.update { state ->
                                state.appendPage(result)
                            }
                        }
                        .onFailure { error ->
                            _state.update { it.copy(isLoadingMore = false, error = error.toBrowseError()) }
                        }
                }
        }

        /** Retry from wherever the feed died: empty restarts, tail appends. */
        fun onRetry() {
            if (_state.value.wallpapers.isEmpty()) restartSearch() else loadMore()
        }

        private fun restartSearch() {
            searchJob?.cancel()
            currentPage = 1
            randomSeed = null
            _state.update {
                it.copy(
                    wallpapers = emptyList(),
                    isFirstLoading = true,
                    isLoadingMore = false,
                    endReached = false,
                    error = null,
                )
            }
            val query = effectiveQuery()
            randomSeed = query.seed
            searchJob =
                viewModelScope.launch {
                    wallhavenRepository.search(query, page = 1)
                        .onSuccess { result ->
                            _state.update { state ->
                                state.copy(
                                    wallpapers = result.wallpapers,
                                    isFirstLoading = false,
                                    endReached = !result.hasNext,
                                    error = null,
                                )
                            }
                        }
                        .onFailure { error ->
                            _state.update { it.copy(isFirstLoading = false, error = error.toBrowseError()) }
                        }
                }
        }

        /**
         * Applies the SFW-only clamp on top of the user's query and keeps
         * random sorts stable by fixing a seed for the whole session.
         */
        private fun effectiveQuery(): WallpaperQuery =
            _state.value.query.let { query ->
                val seed =
                    if (query.sorting == WallpaperSorting.RANDOM) {
                        randomSeed ?: UUID.randomUUID().toString().take(8)
                    } else {
                        null
                    }
                val ratings =
                    if (_state.value.sfwOnly) {
                        setOf(ContentRating.SFW)
                    } else {
                        query.contentRatings - ContentRating.NSFW
                    }
                query.copy(contentRatings = ratings.ifEmpty { setOf(ContentRating.SFW) }, seed = seed)
            }

        private fun BrowseUiState.appendPage(page: Page): BrowseUiState =
            copy(
                wallpapers = wallpapers + page.wallpapers,
                isLoadingMore = false,
                endReached = !page.hasNext,
                error = null,
            )
    }

private fun NetworkError.toBrowseError(): BrowseError =
    when (this) {
        is NetworkError.Http -> BrowseError.HTTP
        NetworkError.Timeout -> BrowseError.TIMEOUT
        is NetworkError.Io -> BrowseError.OFFLINE
        is NetworkError.Serialization -> BrowseError.BAD_DATA
    }
