package com.cloudimage.feature.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cloudimage.core.data.repository.HistoryRepository
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
 * CloudStream `mainPage` model) or the flat staggered grid.
 */
enum class BrowseMode {
    SECTIONS,
    GRID,
}

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
    /** True while the search field holds the keyboard focus. */
    val searchFocused: Boolean = false,
    /** Sources that failed the last merged grid search (v1.0.9). */
    val sourceFailures: List<FailedSource> = emptyList(),
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
     * is focused and mid-edit: a blank field with history to offer, or
     * text that differs from what the grid is already showing.
     */
    val showSearchPanel: Boolean
        get() =
            searchFocused &&
                (searchText != query.text || (searchText.isBlank() && history.isNotEmpty()))

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

        /** The in-flight sections-list request; cancelled on every restart. */
        private var sectionsJob: Job? = null

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
         * the blank state.
         */
        private fun onDebouncedSearch() {
            val text = _state.value.searchText
            if (text.isBlank()) {
                when {
                    _state.value.mode == BrowseMode.GRID && homeIsAvailable() -> onBackToSections()
                    _state.value.mode == BrowseMode.GRID -> {
                        _state.update { it.copy(query = it.query.copy(text = "")) }
                        startGrid()
                    }
                }
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

        /** Suggestions only ever chase the text being typed, never a committed query. */
        private fun onDebouncedSuggest() {
            val text = _state.value.searchText
            if (text.isBlank() || text == _state.value.query.text) {
                _state.update { it.copy(suggestions = emptyList()) }
                return
            }
            suggestJob?.cancel()
            suggestJob =
                viewModelScope.launch {
                    val tags =
                        sources.suggestTags(
                            text,
                            sourceId = _state.value.scopeSourceId ?: _state.value.selectedSourceId,
                        )
                    // Only land if the user has not typed on since.
                    if (_state.value.searchText == text) {
                        _state.update { it.copy(suggestions = tags) }
                    }
                }
        }

        private fun cancelSearchDebounces() {
            searchDebounce?.cancel()
            suggestDebounce?.cancel()
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
                    _state.value.mode == BrowseMode.GRID && homeIsAvailable() -> onBackToSections()
                    _state.value.mode == BrowseMode.GRID -> {
                        _state.update { it.copy(query = it.query.copy(text = "")) }
                        startGrid()
                    }
                }
                return
            }
            _state.update {
                it.copy(
                    query = it.query.copy(text = text),
                    scopeTitle = null,
                    scopeSourceId = null,
                    mode = BrowseMode.GRID,
                    suggestions = emptyList(),
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
            _state.update {
                it.copy(
                    mode = BrowseMode.GRID,
                    scopeTitle = section.title,
                    scopeSourceId = section.sourceId,
                    query = section.query,
                    searchText = "",
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
                    sourceFailures = emptyList(),
                )
            }
        }

        /** Retry from wherever the feed died: empty restarts, tail appends. */
        fun onRetry() {
            val current = _state.value
            when {
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

            /** Suggestions land before the search commits, so chips are visible while typing. */
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
