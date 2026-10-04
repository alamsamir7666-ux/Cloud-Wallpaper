package com.cloudimage.core.search

/**
 * A global image search engine — the whole web as a wallpaper source, no
 * API key required.
 *
 * Implementations must:
 * - be safe to call from any dispatcher (they perform IO internally),
 * - return typed failures via [ImageSearchResult.Failure], never throw,
 * - keep result ids stable across pages, so the caller's seen-id set can
 *   deduplicate the engine's overlapping pagination.
 */
interface ImageSearchEngine {
    /** Human-readable engine name, surfaced by switching UIs. */
    val name: String

    /**
     * Warms whatever the first search would otherwise pay for upfront — a
     * config fetch, a token handshake. The view model calls it once at
     * init so the user's first search starts warm. Default: nothing to do.
     */
    suspend fun prewarm() {}

    /**
     * Searches for [query], returning page [page] (1-based) filtered by
     * [filters]. An empty result list with `hasMore = true` is legal — it
     * means the engine's own filters (size bias) removed everything on this
     * page and the caller should keep scrolling.
     */
    suspend fun search(
        query: String,
        page: Int,
        filters: GlobalSearchFilters,
    ): ImageSearchResult
}
