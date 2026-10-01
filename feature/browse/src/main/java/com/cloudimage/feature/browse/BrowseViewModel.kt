package com.cloudimage.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.HistoryRepository
import com.cloudimage.core.data.repository.SourceAlbum
import com.cloudimage.core.data.repository.SourceCapability
import com.cloudimage.core.data.repository.SourceCategory
import com.cloudimage.core.data.repository.SourceFailure
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.repository.SourceSection
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.datastore.UserPreferencesRepository
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.HistoryAction
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.WallpaperSorting
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.onFailure
import com.cloudimage.core.network.onSuccess
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/**
 * What the browse screen is showing: the sectioned home (v1.0.9, the
 * CloudStream `mainPage` model), the flat staggered grid, or — when the
 * feed is pinned to an album-style source (v1.1.0) — the album paradigm's
 * own home with its category sidebar.
 */
enum class BrowseMode {
    SECTIONS,
    GRID,
    ALBUMS,
}

/**
 * Where the album UI is drilled into (v1.1.0): the single "Home" tab,
 * one category's album grid, one album's wallpaper grid, or the album-style
 * search results. Navigating forward replaces the scope; the system back
 * unwinds it — Album back to the category it was opened from, then Home.
 */
sealed interface AlbumScope {
    /** The album home: the source's own homepage selection. */
    data object Home : AlbumScope

    /** One category's albums, opened from the sidebar. */
    data class Category(
        val category: SourceCategory,
    ) : AlbumScope

    /** One album's wallpapers. [fromCategory] is the category grid the
     * album was opened from, when there was one — back unwinds to it. */
    data class Album(
        val album: SourceAlbum,
        val fromCategory: SourceCategory? = null,
    ) : AlbumScope

    /** Album-style search results for [query]. */
    data class Search(
        val query: String,
    ) : AlbumScope
}

/**
 * What the album UI's grid is showing (v1.1.0): whichever list the open
 * scope serves — albums for Home, Category and Search, wallpapers for
 * Album — with one shared loading/error state, exactly like a section
 * row's own first-page state.
 */
data class AlbumContentState(
    val albums: List<SourceAlbum> = emptyList(),
    val wallpapers: List<Wallpaper> = emptyList(),
    val isLoading: Boolean = true,
    val error: BrowseError? = null,
    /** The source's own failure reason, shown under the error (v1.0.14 style). */
    val errorDetail: String? = null,
)

/** One source that failed the last merged search, for the summary chip (v1.0.9). */
data class FailedSource(
    val sourceName: String,
    val error: BrowseError,
)

/** One entry of the source bar: an installed source the feed can be pinned to. */
data class BrowseSource(
    val id: String,
    val name: String,
    /** The source needs an API key the user has not stored yet. */
    val needsApiKey: Boolean,
)

/** The tab key of the personal "Recently applied" home tab. */
const val RECENTLY_APPLIED_TAB_KEY = "recently-applied"

/**
 * One home tab (v1.0.10): the personal Recently-applied feed or a provider
 * section. [title] is null on the personal tab — the UI owns its localized
 * string; section tabs carry the composed row title.
 */
data class HomeTab(
    val key: String,
    val title: String?,
)

/**
 * One section row of the home screen: a provider's named feed with its own
 * pagination state. Rows always load pinned to [sourceId].
 */
data class BrowseSectionState(
    /** "$sourceId:$sectionId" — the stable identity the UI addresses rows by. */
    val key: String,
    val sourceId: String,
    val sourceName: String,
    val sectionId: String,
    /** The composed row title shown in the header. */
    val title: String,
    val query: WallpaperQuery,
    val wallpapers: List<Wallpaper> = emptyList(),
    val isFirstLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: BrowseError? = null,
    /** The source's own failure reason, shown under the banner (v1.0.14). */
    val errorDetail: String? = null,
)

/** Immutable snapshot of everything the browse screen renders. */
data class BrowseUiState(
    /** Text currently sitting in the search field; not yet committed. */
    val searchText: String = "",
    /** The committed query driving the grid. */
    val query: WallpaperQuery = WallpaperQuery(),
    /** What the screen is showing — the sectioned home or the flat grid. */
    val mode: BrowseMode = BrowseMode.SECTIONS,
    /** The home rows; empty in the grid fallback (the v1.0.8 flat feed). */
    val sections: List<BrowseSectionState> = emptyList(),
    /**
     * Recently applied wallpapers, most recent first (v1.0.9) — the
     * personal feed that leads the home tabs when it exists. Empty hides
     * its tab: a fresh install shows no ghost tab.
     */
    val recentlyApplied: List<Wallpaper> = emptyList(),
    /**
     * The user's home-tab pick (v1.0.10); null means untouched — the bar
     * then defaults to its first tab (Recently applied when present, else
     * the first section).
     */
    val homeTabKey: String? = null,
    /** The section title when the grid was opened through See-all. */
    val scopeTitle: String? = null,
    /** The section's source when the grid is scoped; the search then runs pinned to it. */
    val scopeSourceId: String? = null,
    val wallpapers: List<Wallpaper> = emptyList(),
    val gridColumns: Int = 2,
    val sfwOnly: Boolean = true,
    val isFirstLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val endReached: Boolean = false,
    val error: BrowseError? = null,
    /** The failing source's own reason, shown under the banner (v1.0.14). */
    val errorDetail: String? = null,
    /** The usable sources, or null while they are being discovered. */
    val sources: List<SourceInfo>? = null,
    /** The source the feed is pinned to; null means the merged feed of all sources. */
    val selectedSourceId: String? = null,
    /** Source-selector entries; empty (selector hidden) while no usable source exists. */
    val sourceBar: List<BrowseSource> = emptyList(),
    /** The pinned source needs an API key the user has not stored yet. */
    val showApiKeyPrompt: Boolean = false,
    /** Past committed searches, most recent first (v1.0.9). */
    val history: List<String> = emptyList(),
    /** Tag suggestions for the text being typed, from TAGS-capable sources. */
    val suggestions: List<String> = emptyList(),
    /** True while suggestions for the text being typed are still in flight (v1.0.19). */
    val suggestLoading: Boolean = false,
    /** True while the search field holds the keyboard focus. */
    val searchFocused: Boolean = false,
    /** Sources that failed the last merged grid search (v1.0.9). */
    val sourceFailures: List<FailedSource> = emptyList(),
    /** The album paradigm's categories (v1.1.0): the pinned source's own
     * list, for the sidebar; empty until it loads. */
    val categories: List<SourceCategory> = emptyList(),
    /** Where the album UI is drilled into (v1.1.0). */
    val albumScope: AlbumScope = AlbumScope.Home,
    /** What the album UI's grid shows (v1.1.0). */
    val albumContent: AlbumContentState = AlbumContentState(),
) {
    /** True when the filter sheet holds non-default choices. */
    val filtersActive: Boolean get() = !query.isDefault

    /**
     * The home tab bar's entries in order (v1.0.10): the personal feed
     * first when it exists, then every declared section.
     */
    val homeTabs: List<HomeTab>
        get() =
            buildList {
                if (recentlyApplied.isNotEmpty()) add(HomeTab(RECENTLY_APPLIED_TAB_KEY, null))
                sections.forEach { add(HomeTab(it.key, it.title)) }
            }

    /**
     * The active home tab: the user's pick while it still exists, else the
     * bar's first tab — so the default is Recently applied whenever the
     * user has applied anything, and a vanished pick (source switched,
     * sections re-declared) degrades to the head instead of a dead index.
     */
    val selectedHomeTab: HomeTab?
        get() = homeTabs.firstOrNull { it.key == homeTabKey } ?: homeTabs.firstOrNull()

    /**
     * The search panel (history + tag suggestions) shows while the field
     * is focused and there is something to offer: text the grid is not
     * showing yet, suggestions that landed or are still in flight, or a
     * blank field with history. The debounced search commit (v1.0.19) no
     * longer ends the session — chips survive the grid catching up with
     * the typed text; only a submit or losing the focus closes it.
     */
    val showSearchPanel: Boolean
        get() =
            searchFocused &&
                (
                    searchText != query.text ||
                        suggestions.isNotEmpty() ||
                        suggestLoading ||
                        (searchText.isBlank() && history.isNotEmpty())
                )

    /**
     * A pinned search with nothing to show offers the merged feed as the
     * next move (v1.0.9): maybe the other sources have it.
     */
    val showTryAllSourcesCta: Boolean
        get() =
            mode == BrowseMode.GRID &&
                scopeTitle == null &&
                selectedSourceId != null &&
                wallpapers.isEmpty() &&
                !isFirstLoading &&
                error == null &&
                !showApiKeyPrompt

    /** The merged-grid failure chip (v1.0.9): some sources failed while the results still show. */
    val showSourceFailureChip: Boolean
        get() =
            mode == BrowseMode.GRID &&
                scopeSourceId == null &&
                selectedSourceId == null &&
                sourceFailures.isNotEmpty() &&
                !isFirstLoading

    /** Zero items + error -> full-screen error; otherwise a footer retry. */
    val showFullscreenError: Boolean
        get() = error != null && wallpapers.isEmpty() && !isFirstLoading

    /** Sources discovered, none usable -> install-a-source guidance. */
    val showNoSources: Boolean
        get() = sources != null && sources.isEmpty() && wallpapers.isEmpty() && sections.isEmpty() && !isFirstLoading
}

/**
 * Drives the browse screen: the sectioned home (v1.0.9) with the flat grid
 * for search, filter drill-downs and See-all. Preference changes (SFW-only,
 * grid columns) flow in from DataStore and re-shape the feed live; source
 * installs flow in from the extension engine and restart the feed only when
 * they rescue it from empty. The feed can be pinned to one source (v1.0.6
 * source switcher); the pin is persisted, survives process death, and falls
 * back to the merged feed when the pinned source is no longer installed.
 *
 * The home loads sections-first: the pinned source's own section list, or
 * one primary section per ready source. When no provider declares anything
 * — a degenerate install — the feed degrades to the flat merged grid (the
 * v1.0.8 home), so the screen is never empty for a structural reason.
 */
@HiltViewModel
class BrowseViewModel
    @Inject
    constructor(
        private val sources: WallpaperSources,
        private val userPreferencesRepository: UserPreferencesRepository,
        private val historyRepository: HistoryRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(BrowseUiState())
        val state: StateFlow<BrowseUiState> = _state.asStateFlow()

        /** Page cursor for the grid; 1 after every restart. */
        private var currentPage = 1

        /** Keeps a random grid sort stable across pages of one session. */
        private var randomSeed: String? = null

        /** The in-flight grid request; cancelled whenever a new search starts. */
        private var searchJob: Job? = null

        /** Debounces as-you-type search; an explicit submit cancels it. */
        private var searchDebounce: Job? = null

        /** Debounces tag suggestions; faster than the search so chips land first. */
        private var suggestDebounce: Job? = null

        /** The in-flight tag suggestion request. */
        private var suggestJob: Job? = null

        /** Bumped per suggestion request; a stale completion must not clear the loading flag. */
        private var suggestGeneration = 0

        /** The in-flight sections-list request; cancelled on every restart. */
        private var sectionsJob: Job? = null

        /** The in-flight album-paradigm content request (v1.1.0). */
        private var albumJob: Job? = null

        /** The sidebar's category-list request (v1.1.0), one per album session. */
        private var categoriesJob: Job? = null

        /** Bumped per album navigation; a stale completion must not clobber the
         * scope it landed after (v1.1.0). */
        private var albumGeneration = 0

        /** The Home tab's albums, cached for instant back navigation (v1.1.0). */
        private var homeAlbumsCache: List<SourceAlbum> = emptyList()

        /** True once the Home tab's albums loaded — distinguishes "empty source"
         * from "never loaded" so back never refetches a known-empty home. */
        private var homeAlbumsLoaded = false

        /** Category albums by category id, cached for instant back navigation (v1.1.0). */
        private val categoryAlbumsCache = mutableMapOf<String, List<SourceAlbum>>()

        /** Which source the cached categories belong to (v1.1.0) — switching
         * between two album-style sources must not serve the other's sidebar. */
        private var categoriesSourceId: String? = null

        /** Per-row page cursors, random-sort seeds and jobs, by section key. */
        private val sectionPages = mutableMapOf<String, Int>()
        private val sectionSeeds = mutableMapOf<String, String>()
        private val sectionJobs = mutableMapOf<String, Job>()

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
                    if (!preferencesSeen || sfwChanged || selectionChanged) restartFeed()
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
                    // The album paradigm rides on capabilities, which only
                    // this flow knows: a pin whose source just gained (or
                    // lost) ALBUMS switches between the sectioned home and
                    // the album UI here — exactly one restart, once the
                    // preferences collector has done its first pass, so the
                    // classic cold-start race cannot double-fire it
                    // (v1.1.0).
                    val albumNow = albumModeActive()
                    val albumShowing = _state.value.mode == BrowseMode.ALBUMS
                    if (preferencesSeen && albumNow != albumShowing) restartFeed()
                    // A feed that is empty or failed while sources were
                    // still being discovered gets a second chance once the
                    // engine finishes loading — the classic cold-start race.
                    // Only once the first load has SETTLED though: an initial
                    // request still in flight has an empty list too, and
                    // rescuing it would fire a duplicate restart.
                    val feedNeedsRetry =
                        !_state.value.isFirstLoading && feedIsEmpty()
                    val nowUsable = !available.orEmpty().isEmpty()
                    if (feedNeedsRetry && nowUsable && sourcesSeen.orEmpty().isEmpty()) restartFeed()
                    sourcesSeen = available
                }.launchIn(viewModelScope)

            userPreferencesRepository.providerApiKeys
                .onEach { keys ->
                    storedApiKeyIds = keys.keys
                    val wasPrompting = _state.value.showApiKeyPrompt
                    rebuildSourceBar()
                    // The user may have just added the missing key from the
                    // Extensions tab — unprompt the feed immediately.
                    if (wasPrompting && !selectedNeedsApiKey()) restartFeed()
                }.launchIn(viewModelScope)

            userPreferencesRepository.searchHistory
                .onEach { history -> _state.update { it.copy(history = history) } }
                .launchIn(viewModelScope)

            // The personal row (v1.0.9): applies only — views and downloads
            // have their own trails in Library — deduped so re-applying a
            // wallpaper keeps it in place, newest first, capped to one row.
            historyRepository
                .observeRecent(HISTORY_SCAN_LIMIT)
                .onEach { entries ->
                    val applied =
                        entries
                            .filter { it.action == HistoryAction.APPLIED }
                            .distinctBy { "${it.wallpaper.providerId}:${it.wallpaper.id}" }
                            .map { it.wallpaper }
                            .take(RECENTLY_APPLIED_LIMIT)
                    _state.update { it.copy(recentlyApplied = applied) }
                }.launchIn(viewModelScope)
        }

        /**
         * Typing updates the field immediately and everything else after a
         * debounce (v1.0.9): suggestions at [SUGGEST_DEBOUNCE_MS] — chips
         * first, while the user is still deciding — and the search itself
         * at [SEARCH_DEBOUNCE_MS], past the hesitation but under the
         * keyless rate limits. An explicit submit cancels both and commits
         * at once; the IME action stays the fast path.
         */
        fun onSearchTextChange(text: String) {
            _state.update { it.copy(searchText = text) }
            scheduleSearchDebounce()
            scheduleSuggestDebounce()
        }

        private fun scheduleSearchDebounce() {
            searchDebounce?.cancel()
            searchDebounce =
                viewModelScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    onDebouncedSearch()
                }
        }

        private fun scheduleSuggestDebounce() {
            suggestDebounce?.cancel()
            suggestDebounce =
                viewModelScope.launch {
                    delay(SUGGEST_DEBOUNCE_MS)
                    onDebouncedSuggest()
                }
        }

        /**
         * The paused-typing commit: search-as-you-type. Records NO history —
         * only explicit commits (IME submit, suggestion tap, history tap)
         * do, so the history reads as searches the user chose, not prefixes
         * they typed past. A blank mirrors the blank submit: the home is
         * the blank state. Committing does NOT close the search panel
         * (v1.0.19) — the field still holds its focus and its suggestion
         * chips; a submit or a focus loss ends the session.
         */
        private fun onDebouncedSearch() {
            val text = _state.value.searchText
            if (text.isBlank()) {
                when {
                    _state.value.mode == BrowseMode.ALBUMS -> {
                        // The blank state of the album UI is its Home scope
                        // (v1.1.0) — the search was a detour.
                        if (_state.value.albumScope !is AlbumScope.Home) onAlbumBack()
                    }
                    _state.value.mode == BrowseMode.GRID && homeIsAvailable() -> onBackToSections()
                    _state.value.mode == BrowseMode.GRID -> {
                        _state.update { it.copy(query = it.query.copy(text = "")) }
                        startGrid()
                    }
                }
                return
            }
            if (_state.value.mode == BrowseMode.ALBUMS) {
                if ((_state.value.albumScope as? AlbumScope.Search)?.query == text) return
                startAlbumSearch(text)
                return
            }
            if (text == _state.value.query.text) return // the grid already shows it
            _state.update {
                it.copy(
                    query = it.query.copy(text = text),
                    scopeTitle = null,
                    scopeSourceId = null,
                    mode = BrowseMode.GRID,
                )
            }
            startGrid()
        }

        /**
         * Suggestions chase the text being typed — including a re-typed
         * committed query (v1.0.19): the edit session lives until the
         * field loses focus or the text is submitted, not until the grid
         * catches up. The loading flag holds the panel open across the
         * debounced search commit so the chips never flash away.
         */
        private fun onDebouncedSuggest() {
            val text = _state.value.searchText
            if (text.isBlank()) {
                suggestJob?.cancel()
                _state.update { it.copy(suggestions = emptyList(), suggestLoading = false) }
                return
            }
            suggestJob?.cancel()
            val generation = ++suggestGeneration
            suggestJob =
                viewModelScope.launch {
                    _state.update { it.copy(suggestLoading = true) }
                    try {
                        val tags =
                            sources.suggestTags(
                                text,
                                sourceId = _state.value.scopeSourceId ?: _state.value.selectedSourceId,
                            )
                        // Only land if the user has not typed on since.
                        if (_state.value.searchText == text) {
                            _state.update { it.copy(suggestions = tags) }
                        }
                    } finally {
                        // A newer request owns the flag; only the current one clears it.
                        if (generation == suggestGeneration) {
                            _state.update { it.copy(suggestLoading = false) }
                        }
                    }
                }
        }

        private fun cancelSearchDebounces() {
            searchDebounce?.cancel()
            suggestDebounce?.cancel()
            suggestJob?.cancel()
        }

        /**
         * Commits the search field. A blank submit while the grid shows the
         * default feed returns to the sectioned home (the search was a
         * detour); a blank submit without a home to return to reloads the
         * flat feed so the field and the query never disagree.
         */
        fun onSearchSubmit() {
            cancelSearchDebounces()
            val text = _state.value.searchText
            if (text.isBlank()) {
                when {
                    _state.value.mode == BrowseMode.ALBUMS -> {
                        if (_state.value.albumScope !is AlbumScope.Home) onAlbumBack()
                    }
                    _state.value.mode == BrowseMode.GRID && homeIsAvailable() -> onBackToSections()
                    _state.value.mode == BrowseMode.GRID -> {
                        _state.update { it.copy(query = it.query.copy(text = "")) }
                        startGrid()
                    }
                }
                return
            }
            if (_state.value.mode == BrowseMode.ALBUMS) {
                startAlbumSearch(text)
                recordSearch(text)
                return
            }
            _state.update {
                it.copy(
                    query = it.query.copy(text = text),
                    scopeTitle = null,
                    scopeSourceId = null,
                    mode = BrowseMode.GRID,
                    suggestions = emptyList(),
                    suggestLoading = false,
                )
            }
            recordSearch(text)
            startGrid()
        }

        /** Explicit commits only — IME submit, suggestion tap, history tap. */
        private fun recordSearch(text: String) {
            if (text.isBlank()) return
            viewModelScope.launch { userPreferencesRepository.addSearchQuery(text) }
        }

        /** The field gained or lost the keyboard focus — drives the search panel. */
        fun onSearchFocusChange(focused: Boolean) {
            _state.update { it.copy(searchFocused = focused) }
        }

        /** A tag chip: the suggestion becomes the query, committed and recorded. */
        fun onSuggestionSelected(tag: String) {
            cancelSearchDebounces()
            _state.update { it.copy(searchText = tag) }
            onSearchSubmit()
        }

        /** A history row: re-runs that search, re-recorded as the most recent. */
        fun onHistorySelected(query: String) {
            _state.update { it.copy(searchText = query) }
            onSearchSubmit()
        }

        /** Clears the stored search history (behind the confirm dialog). */
        fun onClearHistory() {
            viewModelScope.launch { userPreferencesRepository.clearSearchHistory() }
        }

        /** The failure chip's retry: re-runs the merged search under the committed query. */
        fun onRetrySearch() {
            restartFeed()
        }

        /** Commits a new filter set from the sheet and restarts the grid. */
        fun onQueryChange(query: WallpaperQuery) {
            _state.update {
                it.copy(
                    query = query.copy(text = it.query.text),
                    mode = BrowseMode.GRID,
                    scopeTitle = null,
                    scopeSourceId = null,
                )
            }
            startGrid()
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
                        .search(effectiveGridQuery(), page, sourceId = current.scopeSourceId ?: current.selectedSourceId)
                        .onSuccess { result ->
                            currentPage = page
                            _state.update { state ->
                                state
                                    .appendPage(result.page)
                                    .copy(sourceFailures = result.sourceFailures.map { it.toFailedSource() })
                            }
                        }.onFailure { error ->
                            _state.update {
                                it.copy(isLoadingMore = false, error = error.toBrowseError(), errorDetail = error.failureDetail())
                            }
                        }
                }
        }

        /**
         * Appends the next page when one section's carousel approaches its
         * end. A row whose FIRST page failed retries page 1 — there is
         * nothing to append onto.
         */
        fun loadMoreSection(key: String) {
            val section = _state.value.sections.firstOrNull { it.key == key } ?: return
            if (sectionJobs[key]?.isActive == true || section.endReached || section.isLoadingMore) return
            if (section.wallpapers.isEmpty() && section.error != null) {
                loadSectionFirstPage(section)
                return
            }
            val page = (sectionPages[key] ?: 1) + 1
            sectionJobs[key] =
                viewModelScope.launch {
                    updateSection(key) { it.copy(isLoadingMore = true) }
                    sources
                        .search(effectiveSectionQuery(section), page, sourceId = section.sourceId)
                        .onSuccess { result ->
                            sectionPages[key] = page
                            updateSection(key) { state ->
                                state.copy(
                                    wallpapers = state.wallpapers + result.page.wallpapers,
                                    isLoadingMore = false,
                                    endReached = !result.page.hasNext,
                                    error = null,
                                    errorDetail = null,
                                )
                            }
                        }.onFailure { error ->
                            updateSection(key) {
                                it.copy(isLoadingMore = false, error = error.toBrowseError(), errorDetail = error.failureDetail())
                            }
                        }
                }
        }

        /** Opens the flat grid scoped to one section — the See-all header. */
        fun onSeeAll(key: String) {
            val section = _state.value.sections.firstOrNull { it.key == key } ?: return
            suggestJob?.cancel()
            _state.update {
                it.copy(
                    mode = BrowseMode.GRID,
                    scopeTitle = section.title,
                    scopeSourceId = section.sourceId,
                    query = section.query,
                    searchText = "",
                    suggestions = emptyList(),
                    suggestLoading = false,
                )
            }
            startGrid()
        }

        /**
         * The user picked a home tab (v1.0.10) — a tap or a settled swipe.
         * No-op on an unknown key or a repeat of the current pick.
         */
        fun onHomeTabSelected(key: String) {
            if (_state.value.homeTabKey == key) return
            if (_state.value.homeTabs.none { it.key == key }) return
            _state.update { it.copy(homeTabKey = key) }
        }

        /** Returns from the grid to the sectioned home; rows keep their state. */
        fun onBackToSections() {
            if (!homeIsAvailable()) return
            searchJob?.cancel()
            cancelSearchDebounces()
            _state.update {
                it.copy(
                    mode = BrowseMode.SECTIONS,
                    scopeTitle = null,
                    scopeSourceId = null,
                    query = WallpaperQuery(),
                    searchText = "",
                    wallpapers = emptyList(),
                    isLoadingMore = false,
                    endReached = false,
                    error = null,
                    errorDetail = null,
                    suggestions = emptyList(),
                    suggestLoading = false,
                    sourceFailures = emptyList(),
                )
            }
        }

        /** Retry from wherever the feed died: empty restarts, tail appends. */
        fun onRetry() {
            val current = _state.value
            when {
                current.mode == BrowseMode.ALBUMS -> retryAlbumContent()
                current.mode == BrowseMode.SECTIONS -> restartFeed()
                current.wallpapers.isEmpty() -> restartFeed()
                else -> loadMore()
            }
        }

        /** True when the sectioned home exists to go back to. */
        private fun homeIsAvailable(): Boolean = _state.value.sections.isNotEmpty()

        /** True when neither the home rows nor the grid hold anything. */
        private fun feedIsEmpty(): Boolean = _state.value.let { it.sections.isEmpty() && it.wallpapers.isEmpty() }

        /**
         * The one restart for feed-level causes (preferences, discovery,
         * retries). A user-driven grid — live search text or filters —
         * keeps its place and reloads under the new conditions; a scoped
         * grid is reset (its filters belong to the previously pinned
         * source); the sectioned home reloads its rows.
         */
        private fun restartFeed() {
            // The album paradigm replaces BOTH homes when the pin points at
            // an album-style source (v1.1.0) — checked first, so a pin that
            // lands while a merged grid search is showing switches to the
            // album UI instead of re-running the flat grid under the pin.
            if (albumModeActive()) {
                restartAlbumFeed()
                return
            }
            val current = _state.value
            if (current.mode == BrowseMode.GRID) {
                if (current.scopeTitle != null) {
                    _state.update { it.copy(query = WallpaperQuery(), scopeTitle = null, scopeSourceId = null) }
                }
                startGrid()
                return
            }
            restartSections()
        }

        /**
         * Reloads the home: the section list first, then each row's first
         * page in parallel. A pinned source without its API key cannot
         * answer anything — the prompt replaces the load instead of firing
         * requests destined to fail (the honest-taxonomy principle).
         */
        private fun restartSections() {
            sectionsJob?.cancel()
            suggestJob?.cancel()
            sectionJobs.values.forEach { it.cancel() }
            sectionJobs.clear()
            sectionPages.clear()
            sectionSeeds.clear()
            val needsKey = selectedNeedsApiKey()
            _state.update {
                it.copy(
                    sections = emptyList(),
                    isFirstLoading = !needsKey,
                    isLoadingMore = false,
                    endReached = false,
                    error = null,
                    errorDetail = null,
                    showApiKeyPrompt = needsKey,
                    suggestions = emptyList(),
                    suggestLoading = false,
                    sourceFailures = emptyList(),
                )
            }
            if (needsKey) return
            sectionsJob =
                viewModelScope.launch {
                    sources
                        .sections(_state.value.selectedSourceId)
                        .onSuccess { result ->
                            if (result.isEmpty()) {
                                // Degenerate home: nothing declared sections.
                                // Fall back to the flat merged grid (the
                                // v1.0.8 home) instead of an empty screen.
                                _state.update { it.copy(sections = emptyList(), isFirstLoading = false) }
                                enterGridFallback()
                            } else {
                                val pinned = _state.value.selectedSourceId != null
                                val rows = result.map { it.toSectionState(pinned) }
                                _state.update {
                                    it.copy(
                                        sections = rows,
                                        mode = BrowseMode.SECTIONS,
                                        scopeTitle = null,
                                        isFirstLoading = false,
                                        error = null,
                                        errorDetail = null,
                                    )
                                }
                                rows.forEach { loadSectionFirstPage(it) }
                            }
                        }.onFailure { error ->
                            _state.update {
                                it.copy(isFirstLoading = false, error = error.toBrowseError(), errorDetail = error.failureDetail())
                            }
                        }
                }
        }

        /** Loads (or retries) one row's first page. */
        private fun loadSectionFirstPage(section: BrowseSectionState) {
            sectionPages[section.key] = 1
            sectionJobs[section.key] =
                viewModelScope.launch {
                    updateSection(section.key) { it.copy(isFirstLoading = true, error = null, errorDetail = null) }
                    sources
                        .search(
                            effectiveSectionQuery(section),
                            page = 1,
                            sourceId = section.sourceId,
                        ).onSuccess { result ->
                            updateSection(section.key) { state ->
                                state.copy(
                                    wallpapers = result.page.wallpapers,
                                    isFirstLoading = false,
                                    endReached = !result.page.hasNext,
                                    error = null,
                                    errorDetail = null,
                                )
                            }
                        }.onFailure { error ->
                            updateSection(section.key) {
                                it.copy(isFirstLoading = false, error = error.toBrowseError(), errorDetail = error.failureDetail())
                            }
                        }
                }
        }

        /** The grid as the flat default feed, after the home came up empty. */
        private fun enterGridFallback() {
            _state.update { it.copy(mode = BrowseMode.GRID, scopeTitle = null, scopeSourceId = null, showApiKeyPrompt = false) }
            startGrid()
        }

        // --------------------------------------------------------------
        // The album paradigm (v1.1.0): the pinned source declares
        // SourceCapability.ALBUMS, so the home becomes the source's own —
        // a single "Home" tab of its homepage albums plus a category
        // sidebar — and search answers albums. Scope navigation is a
        // stack: category → album, unwound by the system back.
        // --------------------------------------------------------------

        /** True when the pinned source declares the album paradigm. */
        private fun albumModeActive(): Boolean {
            val selected = _state.value.selectedSourceId ?: return false
            val info = _state.value.sources?.firstOrNull { it.id == selected } ?: return false
            return SourceCapability.ALBUMS in info.capabilities
        }

        /**
         * The album home: the source's homepage albums under the single
         * "Home" tab, and — once per session, and only for the source the
         * cache belongs to — the sidebar's category list. Category
         * failures degrade silently (an empty sidebar is a nuisance, not
         * a dead feed); home failures surface like any feed failure, so
         * the cold-start rescue can retry them.
         */
        private fun restartAlbumFeed() {
            albumJob?.cancel()
            categoriesJob?.cancel()
            categoryAlbumsCache.clear()
            homeAlbumsCache = emptyList()
            homeAlbumsLoaded = false
            albumGeneration++
            val needsKey = selectedNeedsApiKey()
            _state.update {
                it.copy(
                    mode = BrowseMode.ALBUMS,
                    albumScope = AlbumScope.Home,
                    albumContent = AlbumContentState(isLoading = !needsKey),
                    sections = emptyList(),
                    wallpapers = emptyList(),
                    isFirstLoading = !needsKey,
                    isLoadingMore = false,
                    endReached = false,
                    error = null,
                    errorDetail = null,
                    showApiKeyPrompt = needsKey,
                    sourceFailures = emptyList(),
                    suggestions = emptyList(),
                    suggestLoading = false,
                )
            }
            if (needsKey) return
            val sourceId = _state.value.selectedSourceId ?: return
            if (categoriesSourceId != sourceId) {
                _state.update { it.copy(categories = emptyList()) }
                categoriesSourceId = sourceId
                categoriesJob =
                    viewModelScope.launch {
                        sources
                            .categories(sourceId)
                            .onSuccess { list -> _state.update { it.copy(categories = list) } }
                            .onFailure {
                                // The sidebar stays empty for this session;
                                // the next feed restart retries it.
                            }
                    }
            }
            loadHomeAlbums()
        }

        /** Loads (or retries) the Home tab's albums. */
        private fun loadHomeAlbums() {
            albumJob?.cancel()
            val generation = ++albumGeneration
            val sourceId = _state.value.selectedSourceId ?: return
            _state.update { it.copy(albumContent = AlbumContentState(isLoading = true), isFirstLoading = true) }
            albumJob =
                viewModelScope.launch {
                    sources
                        .homeAlbums(sourceId)
                        .onSuccess { albums ->
                            if (generation != albumGeneration) return@onSuccess
                            homeAlbumsCache = albums
                            homeAlbumsLoaded = true
                            _state.update {
                                it.copy(
                                    albumContent = AlbumContentState(albums = albums, isLoading = false),
                                    isFirstLoading = false,
                                )
                            }
                        }.onFailure { error ->
                            if (generation != albumGeneration) return@onFailure
                            _state.update {
                                it.copy(
                                    albumContent =
                                        AlbumContentState(
                                            isLoading = false,
                                            error = error.toBrowseError(),
                                            errorDetail = error.failureDetail(),
                                        ),
                                    isFirstLoading = false,
                                )
                            }
                        }
                }
        }

        /** The user picked a category in the sidebar. */
        fun onCategorySelected(category: SourceCategory) {
            if (_state.value.mode != BrowseMode.ALBUMS) return
            albumJob?.cancel()
            val generation = ++albumGeneration
            _state.update {
                it.copy(
                    albumScope = AlbumScope.Category(category),
                    albumContent = AlbumContentState(isLoading = true),
                )
            }
            loadCategoryAlbums(category, generation)
        }

        /** Loads (or retries) one category's albums. */
        private fun loadCategoryAlbums(
            category: SourceCategory,
            generation: Int = ++albumGeneration,
        ) {
            albumJob?.cancel()
            val sourceId = _state.value.selectedSourceId ?: return
            albumJob =
                viewModelScope.launch {
                    sources
                        .albums(sourceId, category.id)
                        .onSuccess { albums ->
                            if (generation != albumGeneration) return@onSuccess
                            categoryAlbumsCache[category.id] = albums
                            _state.update { it.copy(albumContent = AlbumContentState(albums = albums, isLoading = false)) }
                        }.onFailure { error ->
                            if (generation != albumGeneration) return@onFailure
                            _state.update {
                                it.copy(
                                    albumContent =
                                        AlbumContentState(
                                            isLoading = false,
                                            error = error.toBrowseError(),
                                            errorDetail = error.failureDetail(),
                                        ),
                                )
                            }
                        }
                }
        }

        /** The user opened an album — its wallpapers replace the grid. */
        fun onAlbumSelected(album: SourceAlbum) {
            if (_state.value.mode != BrowseMode.ALBUMS) return
            albumJob?.cancel()
            val fromCategory = (_state.value.albumScope as? AlbumScope.Category)?.category
            _state.update {
                it.copy(
                    albumScope = AlbumScope.Album(album, fromCategory),
                    albumContent = AlbumContentState(isLoading = true),
                )
            }
            loadAlbumWallpapers(AlbumScope.Album(album, fromCategory))
        }

        /** Loads (or retries) one album's wallpapers without touching the scope. */
        private fun loadAlbumWallpapers(scope: AlbumScope.Album) {
            albumJob?.cancel()
            val generation = ++albumGeneration
            val sourceId = _state.value.selectedSourceId ?: return
            albumJob =
                viewModelScope.launch {
                    sources
                        .albumWallpapers(sourceId, scope.album.id)
                        .onSuccess { wallpapers ->
                            if (generation != albumGeneration) return@onSuccess
                            _state.update {
                                it.copy(albumContent = AlbumContentState(wallpapers = wallpapers, isLoading = false))
                            }
                        }.onFailure { error ->
                            if (generation != albumGeneration) return@onFailure
                            _state.update {
                                it.copy(
                                    albumContent =
                                        AlbumContentState(
                                            isLoading = false,
                                            error = error.toBrowseError(),
                                            errorDetail = error.failureDetail(),
                                        ),
                                )
                            }
                        }
                }
        }

        /**
         * Search, album-style: the source's own answer — albums the user
         * drills into. The WallpaperQuery is deliberately left untouched;
         * the album UI does not run through the flat grid's pipeline.
         */
        private fun startAlbumSearch(text: String) {
            albumJob?.cancel()
            val generation = ++albumGeneration
            _state.update {
                it.copy(
                    albumScope = AlbumScope.Search(text),
                    albumContent = AlbumContentState(isLoading = true),
                    suggestions = emptyList(),
                    suggestLoading = false,
                )
            }
            val sourceId = _state.value.selectedSourceId ?: return
            albumJob =
                viewModelScope.launch {
                    sources
                        .searchAlbums(sourceId, text)
                        .onSuccess { albums ->
                            if (generation != albumGeneration) return@onSuccess
                            _state.update { it.copy(albumContent = AlbumContentState(albums = albums, isLoading = false)) }
                        }.onFailure { error ->
                            if (generation != albumGeneration) return@onFailure
                            _state.update {
                                it.copy(
                                    albumContent =
                                        AlbumContentState(
                                            isLoading = false,
                                            error = error.toBrowseError(),
                                            errorDetail = error.failureDetail(),
                                        ),
                                )
                            }
                        }
                }
        }

        /**
         * The system back inside the album UI: unwinds the scope stack —
         * an album returns to the category it was opened from (when there
         * was one), a category or a search returns to Home. Returns false
         * on Home itself so the caller lets the system back leave the
         * screen. Cached listings restore instantly; only an uncached
         * category refetches.
         */
        fun onAlbumBack(): Boolean {
            val scope = _state.value.albumScope
            if (scope is AlbumScope.Home) return false
            albumJob?.cancel()
            when (scope) {
                is AlbumScope.Album -> {
                    val category = scope.fromCategory
                    if (category != null) {
                        val cached = categoryAlbumsCache[category.id]
                        _state.update {
                            it.copy(
                                albumScope = AlbumScope.Category(category),
                                albumContent =
                                    if (cached != null) {
                                        AlbumContentState(albums = cached, isLoading = false)
                                    } else {
                                        AlbumContentState(isLoading = true)
                                    },
                            )
                        }
                        if (cached == null) loadCategoryAlbums(category)
                    } else {
                        backToAlbumHome()
                    }
                }
                is AlbumScope.Category, is AlbumScope.Search -> backToAlbumHome()
                is AlbumScope.Home -> return false
            }
            return true
        }

        /** Restores the Home scope from its cache, refetching only never-loaded homes. */
        private fun backToAlbumHome() {
            _state.update {
                it.copy(
                    albumScope = AlbumScope.Home,
                    albumContent =
                        AlbumContentState(
                            albums = homeAlbumsCache,
                            isLoading = !homeAlbumsLoaded,
                        ),
                )
            }
            if (!homeAlbumsLoaded) loadHomeAlbums()
        }

        /** The album grid's retry: reloads whatever the open scope serves. */
        private fun retryAlbumContent() {
            when (val scope = _state.value.albumScope) {
                is AlbumScope.Home -> loadHomeAlbums()
                is AlbumScope.Category -> loadCategoryAlbums(scope.category)
                is AlbumScope.Album -> loadAlbumWallpapers(scope)
                is AlbumScope.Search -> startAlbumSearch(scope.query)
            }
        }

        /**
         * (Re)loads the grid from page 1 under the committed query. The
         * key guard mirrors the sections path: a pinned source without its
         * key gets the prompt, not a doomed request.
         */
        private fun startGrid() {
            searchJob?.cancel()
            currentPage = 1
            randomSeed = null
            val needsKey = selectedNeedsApiKey()
            _state.update {
                it.copy(
                    wallpapers = emptyList(),
                    isFirstLoading = !needsKey,
                    isLoadingMore = false,
                    endReached = false,
                    error = null,
                    errorDetail = null,
                    showApiKeyPrompt = needsKey,
                    sourceFailures = emptyList(),
                )
            }
            if (needsKey) return
            val query = effectiveGridQuery()
            searchJob =
                viewModelScope.launch {
                    sources
                        .search(query, page = 1, sourceId = _state.value.scopeSourceId ?: _state.value.selectedSourceId)
                        .onSuccess { result ->
                            _state.update { state ->
                                state.copy(
                                    wallpapers = result.page.wallpapers,
                                    isFirstLoading = false,
                                    endReached = !result.page.hasNext,
                                    error = null,
                                    errorDetail = null,
                                    sourceFailures = result.sourceFailures.map { it.toFailedSource() },
                                )
                            }
                        }.onFailure { error ->
                            _state.update {
                                it.copy(isFirstLoading = false, error = error.toBrowseError(), errorDetail = error.failureDetail())
                            }
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
         * The row a [SourceSection] becomes: the display title composes the
         * source name into the merged view (every default "Popular" row
         * would read the same otherwise) but keeps the provider's own title
         * when the feed is pinned — CloudStream homes are titled by their
         * lists.
         */
        private fun SourceSection.toSectionState(pinned: Boolean): BrowseSectionState =
            BrowseSectionState(
                key = "$sourceId:$sectionId",
                sourceId = sourceId,
                sourceName = sourceName,
                sectionId = sectionId,
                title =
                    when {
                        pinned -> title
                        isDefault -> sourceName
                        else -> "$sourceName · $title"
                    },
                query = query,
            )

        private fun updateSection(
            key: String,
            transform: (BrowseSectionState) -> BrowseSectionState,
        ) {
            _state.update { state ->
                state.copy(
                    sections = state.sections.map { if (it.key == key) transform(it) else it },
                )
            }
        }

        /**
         * The grid query: the committed query with the SFW-only clamp and a
         * session-stable seed for random sorts.
         */
        private fun effectiveGridQuery(): WallpaperQuery {
            val query = _state.value.query
            val seed =
                if (query.sorting == WallpaperSorting.RANDOM) {
                    randomSeed ?: UUID.randomUUID().toString().take(8)
                } else {
                    null
                }
            randomSeed = seed
            return query.withClamp(seed)
        }

        /** A row's query: its preset with the same clamp and a per-row seed. */
        private fun effectiveSectionQuery(section: BrowseSectionState): WallpaperQuery {
            val query = section.query
            val seed =
                if (query.sorting == WallpaperSorting.RANDOM) {
                    sectionSeeds.getOrPut(section.key) { UUID.randomUUID().toString().take(8) }
                } else {
                    null
                }
            return query.withClamp(seed)
        }

        private fun WallpaperQuery.withClamp(seed: String?): WallpaperQuery {
            val ratings =
                if (_state.value.sfwOnly) {
                    setOf(ContentRating.SFW)
                } else {
                    contentRatings - ContentRating.NSFW
                }
            return copy(contentRatings = ratings.ifEmpty { setOf(ContentRating.SFW) }, seed = seed)
        }

        private fun BrowseUiState.appendPage(page: Page): BrowseUiState =
            copy(
                wallpapers = wallpapers + page.wallpapers,
                isLoadingMore = false,
                endReached = !page.hasNext,
                error = null,
                errorDetail = null,
            )

        private fun SourceFailure.toFailedSource(): FailedSource =
            FailedSource(
                sourceName = sourceName,
                error = error.toBrowseError(),
            )

        private companion object {
            /** As-you-type search delay: past typing hesitation, under the keyless rate limits. */
            const val SEARCH_DEBOUNCE_MS = 450L

            /** Suggestions fire while typing; the panel holds them past the search commit (v1.0.19). */
            const val SUGGEST_DEBOUNCE_MS = 200L

            /** How much history the personal row scans before filtering. */
            const val HISTORY_SCAN_LIMIT = 50

            /** How many recently-applied cards the home row shows. */
            const val RECENTLY_APPLIED_LIMIT = 10
        }
    }

/**
 * The reason a SOURCE error happened, verbatim — "wallpaperflare answered
 * HTTP 403 for …", "source 'x' is disabled — …". The other taxonomy
 * entries already say what they mean in their banner string; only SOURCE
 * hid its cause behind a generic message the Extensions tab could not
 * back up (v1.0.14).
 */
private fun NetworkError.failureDetail(): String? = (this as? NetworkError.Source)?.reason

private fun NetworkError.toBrowseError(): BrowseError =
    when (this) {
        is NetworkError.Http -> BrowseError.HTTP
        NetworkError.Timeout -> BrowseError.TIMEOUT
        is NetworkError.Io -> BrowseError.OFFLINE
        is NetworkError.Serialization -> BrowseError.BAD_DATA
        is NetworkError.Source -> BrowseError.SOURCE
    }
