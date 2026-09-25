package com.cloudimage.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.repository.WallpaperSources
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

    /** A source failed for its own reasons — NOT a connectivity problem. */
    SOURCE,
}

/** One entry of the source bar: an installed source the feed can be pinned to. */
data class BrowseSource(
    val id: String,
    val name: String,
    /** The source needs an API key the user has not stored yet. */
    val needsApiKey: Boolean,
)

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
    /** The usable sources, or null while they are being discovered. */
    val sources: List<SourceInfo>? = null,
    /** The source the feed is pinned to; null means the merged feed of all sources. */
    val selectedSourceId: String? = null,
    /** Source-selector entries; empty (selector hidden) while no usable source exists. */
    val sourceBar: List<BrowseSource> = emptyList(),
    /** The pinned source needs an API key the user has not stored yet. */
    val showApiKeyPrompt: Boolean = false,
) {
    /** True when the filter sheet holds non-default choices. */
    val filtersActive: Boolean get() = !query.isDefault

    /** Zero items + error -> full-screen error; otherwise a footer retry. */
    val showFullscreenError: Boolean
        get() = error != null && wallpapers.isEmpty() && !isFirstLoading

    /** Sources discovered, none usable -> install-a-source guidance. */
    val showNoSources: Boolean
        get() = sources != null && sources.isEmpty() && wallpapers.isEmpty() && !isFirstLoading
}

/**
 * Drives the browse grid: owns search text, the committed query, and the
 * paging cursor. Preference changes (SFW-only, grid columns) flow in from
 * DataStore and re-shape the feed live; source installs flow in from the
 * extension engine and restart the feed only when they rescue it from
 * empty. The feed can be pinned to one source (v1.0.6 source switcher,
 * restyled as a CloudStream-style selector in v1.0.7); the pin is
 * persisted, survives process death, and falls back to the merged feed
 * when the pinned source is no longer installed.
 */
@HiltViewModel
class BrowseViewModel
    @Inject
    constructor(
        private val sources: WallpaperSources,
        private val userPreferencesRepository: UserPreferencesRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(BrowseUiState())
        val state: StateFlow<BrowseUiState> = _state.asStateFlow()

        /** Page cursor for the visible list; 1 after every restart. */
        private var currentPage = 1

        /** Keeps a random sort stable across pages of one session. */
        private var randomSeed: String? = null

        /** The in-flight request; cancelled whenever a new search starts. */
        private var searchJob: Job? = null

        /** Provider ids that have an API key stored; drives the key prompts. */
        private var storedApiKeyIds: Set<String> = emptySet()

        init {
            var preferencesSeen = false
            userPreferencesRepository.preferences
                .onEach { preferences ->
                    val sfwChanged = preferences.sfwOnly != _state.value.sfwOnly
                    val storedSelection = preferences.browseSourceId.ifEmpty { null }
                    val selectionChanged = storedSelection != _state.value.selectedSourceId
                    _state.update {
                        it.copy(
                            sfwOnly = preferences.sfwOnly,
                            gridColumns = preferences.gridColumns,
                            selectedSourceId = storedSelection,
                        )
                    }
                    rebuildSourceBar()
                    // First emission triggers the initial load; later SFW flips
                    // restart the feed because the purity parameter changes;
                    // a selection change restarts it because the provider set
                    // changed. Never both — one restart per cause.
                    if (!preferencesSeen || sfwChanged || selectionChanged) restartSearch()
                    preferencesSeen = true
                }.launchIn(viewModelScope)

            var sourcesSeen: List<SourceInfo>? = null
            sources.sources
                .onEach { available ->
                    _state.update { it.copy(sources = available) }
                    // A pin that outlived its package (uninstalled elsewhere)
                    // falls back to the merged feed instead of a dead screen.
                    val selected = _state.value.selectedSourceId
                    val selectionDangling =
                        available != null && selected != null && available.none { it.id == selected }
                    if (selectionDangling) {
                        viewModelScope.launch { userPreferencesRepository.setBrowseSourceId(null) }
                    }
                    rebuildSourceBar()
                    // A feed that is empty or failed while sources were
                    // still being discovered gets a second chance once the
                    // engine finishes loading — the classic cold-start race.
                    // Only once the first load has SETTLED though: an initial
                    // request still in flight has an empty list too, and
                    // rescuing it would fire a duplicate restart.
                    val feedNeedsRetry =
                        !_state.value.isFirstLoading && _state.value.wallpapers.isEmpty()
                    val nowUsable = !available.orEmpty().isEmpty()
                    if (feedNeedsRetry && nowUsable && sourcesSeen.orEmpty().isEmpty()) restartSearch()
                    sourcesSeen = available
                }.launchIn(viewModelScope)

            userPreferencesRepository.providerApiKeys
                .onEach { keys ->
                    storedApiKeyIds = keys.keys
                    val wasPrompting = _state.value.showApiKeyPrompt
                    rebuildSourceBar()
                    // The user may have just added the missing key from the
                    // Extensions tab — unprompt the feed immediately.
                    if (wasPrompting && !selectedNeedsApiKey()) restartSearch()
                }.launchIn(viewModelScope)
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

        /**
         * Pins the feed to [sourceId] (null = the merged feed of every
         * source). The write round-trips through DataStore, so the restart
         * happens exactly once, in the preferences collector.
         */
        fun onSourceSelected(sourceId: String?) {
            viewModelScope.launch { userPreferencesRepository.setBrowseSourceId(sourceId) }
        }

        /** Appends the next page when the grid approaches its end. */
        fun loadMore() {
            val current = _state.value
            if (searchJob?.isActive == true || current.endReached || current.isLoadingMore) return
            val page = currentPage + 1
            searchJob =
                viewModelScope.launch {
                    _state.update { it.copy(isLoadingMore = true) }
                    sources
                        .search(effectiveQuery(), page, sourceId = current.selectedSourceId)
                        .onSuccess { result ->
                            currentPage = page
                            _state.update { state ->
                                state.appendPage(result)
                            }
                        }.onFailure { error ->
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
            // A pinned source without its API key cannot answer anything —
            // say that instead of firing a request destined to fail with a
            // misleading generic error (the honest-taxonomy principle).
            val needsKey = selectedNeedsApiKey()
            _state.update {
                it.copy(
                    wallpapers = emptyList(),
                    isFirstLoading = !needsKey,
                    isLoadingMore = false,
                    endReached = false,
                    error = null,
                    showApiKeyPrompt = needsKey,
                )
            }
            if (needsKey) return
            val query = effectiveQuery()
            randomSeed = query.seed
            searchJob =
                viewModelScope.launch {
                    sources
                        .search(query, page = 1, sourceId = _state.value.selectedSourceId)
                        .onSuccess { result ->
                            _state.update { state ->
                                state.copy(
                                    wallpapers = result.wallpapers,
                                    isFirstLoading = false,
                                    endReached = !result.hasNext,
                                    error = null,
                                )
                            }
                        }.onFailure { error ->
                            _state.update { it.copy(isFirstLoading = false, error = error.toBrowseError()) }
                        }
                }
        }

        /** True when the pinned source is useless until the user stores its key. */
        private fun selectedNeedsApiKey(): Boolean {
            val selected = _state.value.selectedSourceId ?: return false
            val info = _state.value.sources?.firstOrNull { it.id == selected } ?: return false
            return info.requiresApiKey && selected !in storedApiKeyIds
        }

        /** Rebuilds the source selector entries from the discovered sources and stored keys. */
        private fun rebuildSourceBar() {
            _state.update { state ->
                val available = state.sources.orEmpty()
                state.copy(
                    sourceBar =
                        if (available.isEmpty()) {
                            // Zero usable sources needs the install-a-source
                            // empty state instead.
                            emptyList()
                        } else {
                            // Even a single source is worth naming — the
                            // selector doubles as provenance for the feed, and
                            // its menu always offers the Extensions shortcut.
                            available.map { info ->
                                BrowseSource(
                                    id = info.id,
                                    name = info.name,
                                    needsApiKey = info.requiresApiKey && info.id !in storedApiKeyIds,
                                )
                            }
                        },
                )
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
        is NetworkError.Source -> BrowseError.SOURCE
    }
