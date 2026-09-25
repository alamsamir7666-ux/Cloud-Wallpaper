package com.cloudimage.core.testing

import com.cloudimage.core.data.repository.SearchOutcome
import com.cloudimage.core.data.repository.SourceFailure
import com.cloudimage.core.data.repository.SourceInfo
import com.cloudimage.core.data.repository.SourceSection
import com.cloudimage.core.data.repository.WallpaperSources
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.WallpaperQuery
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
}
