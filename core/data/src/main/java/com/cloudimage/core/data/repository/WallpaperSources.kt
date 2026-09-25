package com.cloudimage.core.data.repository

import com.cloudimage.core.model.Page
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.network.NetworkResult
import kotlinx.coroutines.flow.StateFlow

/**
 * One usable wallpaper source, surfaced to the UI.
 *
 * [requiresApiKey] is true when the provider is useless without a stored
 * key — the extensions screen shows a key entry affordance for those.
 */
data class SourceInfo(
    val id: String,
    val name: String,
    val requiresApiKey: Boolean,
)

/**
 * The browse pipeline over whatever providers are installed: the
 * replacement for the Part 3-era built-in Wallhaven repository. Every
 * ready extension is loaded through the engine and queried in parallel;
 * a failing source degrades to being skipped instead of taking the feed
 * down.
 */
interface WallpaperSources {
    /**
     * The usable sources, or null until the first refresh — the UI shows a
     * loading state instead of a misleading "nothing installed" one.
     */
    val sources: StateFlow<List<SourceInfo>?>

    /**
     * Load-failure reasons by source id, filled by every [refresh] — the
     * extension manager renders them as per-source diagnostics so a broken
     * source says why it is broken. Empty when everything loads.
     */
    val loadFailures: StateFlow<Map<String, String>>

    /** Rescans the extensions area; cheap and idempotent. */
    suspend fun refresh()

    /**
     * Queries the sources with [query] at [page] and merges the results.
     *
     * [WallpaperQuery.contentRatings] is translated into the host filter
     * vocabulary (`purity`) and ALSO enforced per item, so a source that
     * ignores the vocabulary cannot leak content the user excluded.
     *
     * [sourceId] pins the query to a single installed source (the v1.0.6
     * browse source switcher); null — the default — queries every ready
     * source in parallel and merges the pages.
     */
    suspend fun search(
        query: WallpaperQuery,
        page: Int,
        sourceId: String? = null,
    ): NetworkResult<Page>
}
