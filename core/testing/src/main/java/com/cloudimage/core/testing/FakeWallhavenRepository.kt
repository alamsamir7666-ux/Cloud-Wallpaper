package com.cloudimage.core.testing

import com.cloudimage.core.data.repository.WallhavenRepository
import com.cloudimage.core.model.Page
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.model.WallpaperDetails
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.network.NetworkResult

/**
 * Scriptable fake for the built-in feed: tests enqueue results page by page
 * and assert on the queries the ViewModel actually sent.
 */
class FakeWallhavenRepository : WallhavenRepository {
    private val scriptedSearches = ArrayDeque<NetworkResult<Page>>()
    private val scriptedWallpapers = ArrayDeque<NetworkResult<WallpaperDetails>>()

    /** Every (query, page) pair search() was called with, in order. */
    val searchCalls = mutableListOf<Pair<WallpaperQuery, Int>>()

    /** Test hook: queues the result for the next search() call. */
    fun enqueueSearch(result: NetworkResult<Page>) {
        scriptedSearches += result
    }

    /** Test hook: queues the result for the next getWallpaper() call. */
    fun enqueueWallpaper(result: NetworkResult<WallpaperDetails>) {
        scriptedWallpapers += result
    }

    override suspend fun search(
        query: WallpaperQuery,
        page: Int,
    ): NetworkResult<Page> {
        searchCalls += query to page
        return scriptedSearches.removeFirstOrNull()
            ?: NetworkResult.Success(Page.EMPTY)
    }

    override suspend fun getWallpaper(wallpaperId: String): NetworkResult<WallpaperDetails> =
        scriptedWallpapers.removeFirstOrNull()
            ?: NetworkResult.Success(WallpaperDetails(wallpaper = fakeWallpaper(wallpaperId)))

    companion object {
        /** Builds a recognizable wallpaper for tests. */
        fun fakeWallpaper(
            id: String,
            providerId: String = "wallhaven",
            width: Int = 1920,
            height: Int = 1080,
        ) = Wallpaper(
            id = id,
            providerId = providerId,
            thumbUrl = "https://example.test/thumbs/$id.jpg",
            fullUrl = "https://example.test/full/$id.jpg",
            width = width,
            height = height,
        )
    }
}
