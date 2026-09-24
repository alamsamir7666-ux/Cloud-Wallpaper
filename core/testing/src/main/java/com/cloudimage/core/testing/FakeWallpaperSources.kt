package com.cloudimage.core.testing

import com.cloudimage.core.data.repository.SourceInfo
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
 */
class FakeWallpaperSources : WallpaperSources {
    private val scriptedSearches = ArrayDeque<NetworkResult<Page>>()

    /** Every (query, page) pair search() was called with, in order. */
    val searchCalls = mutableListOf<Pair<WallpaperQuery, Int>>()

    private val sourcesState = MutableStateFlow<List<SourceInfo>?>(null)
    override val sources: StateFlow<List<SourceInfo>?> = sourcesState.asStateFlow()

    private val failuresState = MutableStateFlow<Map<String, String>>(emptyMap())
    override val loadFailures: StateFlow<Map<String, String>> = failuresState.asStateFlow()

    /** Test hook: queues the result for the next search() call. */
    fun enqueueSearch(result: NetworkResult<Page>) {
        scriptedSearches += result
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
    ): NetworkResult<Page> {
        searchCalls += query to page
        return scriptedSearches.removeFirstOrNull()
            ?: NetworkResult.Success(Page.EMPTY)
    }
}
