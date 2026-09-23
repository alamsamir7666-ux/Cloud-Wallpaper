package com.cloudimage.core.data.repository

import com.cloudimage.core.model.Page
import com.cloudimage.core.model.WallpaperDetails
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.network.NetworkResult

/**
 * The built-in Wallhaven feed. Part 5/6 turns this into a loaded extension,
 * which is why features depend on this interface — never on the Wallhaven API
 * types directly.
 */
interface WallhavenRepository {
    /**
     * Fetches one page of results for [query]. The repository clamps
     * [WallpaperQuery.contentRatings] to what the app may request before
     * anything hits the network.
     */
    suspend fun search(
        query: WallpaperQuery,
        page: Int,
    ): NetworkResult<Page>

    /** Fetches the full payload for one wallpaper. */
    suspend fun getWallpaper(wallpaperId: String): NetworkResult<WallpaperDetails>
}
