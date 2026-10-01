package com.cloudimage.core.testing

import com.cloudimage.core.data.repository.SearchOutcome
import com.cloudimage.core.data.repository.SourceAlbum
import com.cloudimage.core.data.repository.SourceCategory
import com.cloudimage.core.data.repository.SourceFailure
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.repository.SourceSection
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperDetails
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Scriptable fake for the multi-source browse pipeline: tests enqueue
 * results page by page and assert on the queries the ViewModel actually
 * sent. The sources flow is controllable to cover the loading, empty and
 * populated states of the browse UI.
 *
 * [sectionsResult] defaults to an empty section list — a ViewModel or
 * UI built against the fake then falls back to the flat grid feed, which
 * keeps the pre-sections behavior testable side by side with the new one.
 */
class FakeWallpaperSources : WallpaperSources {
    private val scriptedSearches = ArrayDeque<NetworkResult<Page>>()

    /** Every (query, page) pair search() was called with, in order. */
    val searchCalls = mutableListOf<Pair<WallpaperQuery, Int>>()

    /** The sourceId each search() was called with, parallel to [searchCalls]. */
    val searchSourceIds = mutableListOf<String?>()

    /** The sourceId every sections() call was called with, in order. */
    val sectionsCalls = mutableListOf<String?>()

    /** Per-source failures attached to every successful search (v1.0.9). */
    var scriptedSourceFailures: List<SourceFailure> = emptyList()

    /** What suggestTags answers (v1.0.9); the raw queries land in [suggestCalls]. */
    var scriptedSuggestions: List<String> = emptyList()

    /** Every text suggestTags was called with, in order. */
    val suggestCalls = mutableListOf<String>()

    private val scriptedDetails = ArrayDeque<NetworkResult<WallpaperDetails>>()

    /** Every wallpaper details() was called with, in order (v1.0.21). */
    val detailsCalls = mutableListOf<Wallpaper>()

    /** What the album-paradigm calls answer (v1.1.0), by method. */
    var scriptedCategories: NetworkResult<List<SourceCategory>> = NetworkResult.Success(emptyList())
    var scriptedHomeAlbums: NetworkResult<List<SourceAlbum>> = NetworkResult.Success(emptyList())
    var scriptedAlbums: NetworkResult<List<SourceAlbum>> = NetworkResult.Success(emptyList())
    var scriptedAlbumWallpapers: NetworkResult<List<Wallpaper>> = NetworkResult.Success(emptyList())
    var scriptedSearchAlbums: NetworkResult<List<SourceAlbum>> = NetworkResult.Success(emptyList())

    /** The sourceId every album-paradigm call saw, in order (v1.1.0). */
    val albumSourceCalls = mutableListOf<String>()

    /** The categoryId every albums() call saw, in order (v1.1.0). */
    val albumsCategoryCalls = mutableListOf<String>()

    /** The albumId every albumWallpapers() call saw, in order (v1.1.0). */
    val albumWallpaperCalls = mutableListOf<String>()

    /** The query every searchAlbums() call saw, in order (v1.1.0). */
    val searchAlbumCalls = mutableListOf<String>()

    private var scriptedSections: NetworkResult<List<SourceSection>> =
        NetworkResult.Success(emptyList())

    private val sourcesState = MutableStateFlow<List<SourceInfo>?>(null)
    override val sources: StateFlow<List<SourceInfo>?> = sourcesState.asStateFlow()

    private val failuresState = MutableStateFlow<Map<String, String>>(emptyMap())
    override val loadFailures: StateFlow<Map<String, String>> = failuresState.asStateFlow()

    /** Test hook: queues the result for the next search() call. */
    fun enqueueSearch(result: NetworkResult<Page>) {
        scriptedSearches += result
    }

    /** Test hook: queues the result for the next details() call (v1.0.21). */
    fun enqueueDetails(result: NetworkResult<WallpaperDetails>) {
        scriptedDetails += result
    }

    /** Test hook: drives what sections() answers from now on. */
    fun setSectionsResult(result: NetworkResult<List<SourceSection>>) {
        scriptedSections = result
    }

    /** Test hook: drives the exposed sources list. */
    fun setSources(vararg sources: SourceInfo?) {
        sourcesState.value = sources.filterNotNull()
    }

    /** Test hook: drives the exposed load-failure diagnostics. */
    fun setLoadFailures(failures: Map<String, String>) {
        failuresState.value = failures
    }

    override suspend fun refresh() {
        if (sourcesState.value == null) {
            sourcesState.value = emptyList()
        }
    }

    override suspend fun search(
        query: WallpaperQuery,
        page: Int,
        sourceId: String?,
    ): NetworkResult<SearchOutcome> {
        searchCalls += query to page
        searchSourceIds += sourceId
        return when (val scripted = scriptedSearches.removeFirstOrNull()) {
            is NetworkResult.Success ->
                NetworkResult.Success(
                    SearchOutcome(page = scripted.value, sourceFailures = scriptedSourceFailures),
                )

            is NetworkResult.Failure -> scripted
            null -> NetworkResult.Success(SearchOutcome(Page.EMPTY))
        }
    }

    override suspend fun suggestTags(
        query: String,
        sourceId: String?,
    ): List<String> {
        suggestCalls += query
        return scriptedSuggestions
    }

    override suspend fun sections(sourceId: String?): NetworkResult<List<SourceSection>> {
        sectionsCalls += sourceId
        return scriptedSections
    }

    override suspend fun details(wallpaper: Wallpaper): NetworkResult<WallpaperDetails> {
        detailsCalls += wallpaper
        // Unscripted calls fail loudly rather than inventing a record —
        // a test that expects details must script them.
        return scriptedDetails.removeFirstOrNull()
            ?: NetworkResult.Failure(NetworkError.Source("no scripted details for ${wallpaper.id}"))
    }

    override suspend fun categories(sourceId: String): NetworkResult<List<SourceCategory>> {
        albumSourceCalls += sourceId
        return scriptedCategories
    }

    override suspend fun homeAlbums(sourceId: String): NetworkResult<List<SourceAlbum>> {
        albumSourceCalls += sourceId
        return scriptedHomeAlbums
    }

    override suspend fun albums(
        sourceId: String,
        categoryId: String,
    ): NetworkResult<List<SourceAlbum>> {
        albumSourceCalls += sourceId
        albumsCategoryCalls += categoryId
        return scriptedAlbums
    }

    override suspend fun albumWallpapers(
        sourceId: String,
        albumId: String,
    ): NetworkResult<List<Wallpaper>> {
        albumSourceCalls += sourceId
        albumWallpaperCalls += albumId
        return scriptedAlbumWallpapers
    }

    override suspend fun searchAlbums(
        sourceId: String,
        query: String,
    ): NetworkResult<List<SourceAlbum>> {
        albumSourceCalls += sourceId
        searchAlbumCalls += query
        return scriptedSearchAlbums
    }
}
