package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Two global search engines answering as one (v1.2.14) — the deployed
 * bridge lane and the in-app Reddit lane, merged page by page.
 *
 * The merge rules, in the order they matter:
 * - Both lanes run CONCURRENTLY for every page — the bridge's slow
 *   upstream (tens of seconds on a cold start) must not wait behind
 *   Reddit, and Reddit must not wait behind the bridge.
 * - The SECONDARY lane (Reddit) runs under a timeout the primary never
 *   pays: it is the lane whose network conditions vary wildly (carrier
 *   proxies, Reddit throttling a burst), and a grid tile's worth of extra
 *   results is never worth stalling the page for. The primary's own
 *   timeouts stay the honest bound for it.
 * - Results INTERLEAVE, round-robin: a wallpaper grid of mixed provenance
 *   reads as a broader search than either lane alone, and no source hogs
 *   the first screen. Ids are stable across engines (`gs:` + URL), so the
 *   same image found by both lanes collapses into one card and the view
 *   model's seen-id set keeps the merge honest across pages.
 * - A failed or timed-out secondary is INVISIBLE: the page is the
 *   primary's, exactly as before this engine existed. This is the
 *   lane-failure contract that lets Reddit be added without ever making
 *   search worse.
 * - A failed PRIMARY with a healthy secondary degrades to the secondary
 *   alone — the resilience the mortal-bridge era asked for: a recycled
 *   sandbox no longer kills search outright while Reddit still answers
 *   from the phone's own network.
 * - Only when BOTH lanes fail does the caller see a failure, and it is
 *   the PRIMARY's — the bridge's error taxonomy is the one the UI's
 *   messages were written for.
 */
class MergedSearchEngine(
    private val primary: ImageSearchEngine,
    private val secondary: ImageSearchEngine,
    /** The secondary lane's overall budget per page; the primary has none. */
    private val secondaryTimeoutMs: Long = DEFAULT_SECONDARY_TIMEOUT_MS,
) : ImageSearchEngine {
    override val name: String = "${primary.name}+${secondary.name}"

    /**
     * Only the primary has anything to warm — the bridge's config fetch.
     * Reddit's first search is its first request; warming it would burn
     * pacing budget for a page the user may never ask for.
     */
    override suspend fun prewarm() {
        primary.prewarm()
    }

    override suspend fun search(
        query: String,
        page: Int,
        filters: GlobalSearchFilters,
    ): ImageSearchResult =
        coroutineScope {
            val secondaryPage =
                async {
                    guardLane {
                        withTimeoutOrNull(secondaryTimeoutMs) { secondary.search(query, page, filters) }
                    }
                }
            // The primary lane runs unbounded by the secondary's budget:
            // its own timeouts (the bridge's 160 s worst case) are the
            // honest bound the search UI already promises.
            val primaryPage = guardLane { primary.search(query, page, filters) }
            merge(primaryPage, secondaryPage.await(), page)
        }

    /**
     * A lane guard that turns an unexpected THROW into a null lane — the
     * contract says engines fail via [ImageSearchResult.Failure], and a
     * defensive merge should survive a misbehaving engine anyway. Unlike
     * `runCatching` it never swallows cancellation: the view model cancels
     * an in-flight search the moment a new one is committed, and a guard
     * that ate the CancellationException would let the DEAD search live on
     * to completion, stashing its stale results over the fresh ones.
     */
    private suspend fun <T> guardLane(lane: suspend () -> T): T? =
        try {
            lane()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }

    /**
     * The page-lane merge: success pairs interleave, a lone success stands
     * alone, and a lone primary failure with no secondary rescue is the
     * failure the caller sees.
     */
    private fun merge(
        primary: ImageSearchResult?,
        secondary: ImageSearchResult?,
        page: Int,
    ): ImageSearchResult {
        val primarySuccess = primary as? ImageSearchResult.Success
        val secondarySuccess = secondary as? ImageSearchResult.Success
        return when {
            primarySuccess != null && secondarySuccess != null ->
                ImageSearchResult.Success(
                    page = interleave(primarySuccess.page, secondarySuccess.page, page),
                )

            primarySuccess != null -> primarySuccess
            secondarySuccess != null -> secondarySuccess
            else ->
                (primary as? ImageSearchResult.Failure)
                    ?: ImageSearchResult.Failure(
                        error = ImageSearchError.NETWORK,
                        detail = "no search engine answered",
                    )
        }
    }

    /**
     * Round-robin the two pages' results, collapsing ids both lanes
     * found. `hasMore` is either lane's — the scroll continues while ANY
     * engine still has pages, and the next page re-asks both.
     */
    private fun interleave(
        primary: ImageSearchPage,
        secondary: ImageSearchPage,
        page: Int,
    ): ImageSearchPage {
        val long = primary.results
        val short = secondary.results
        val merged = ArrayList<Wallpaper>(long.size + short.size)
        val shared = minOf(long.size, short.size)
        for (index in 0 until shared) {
            merged.add(long[index])
            merged.add(short[index])
        }
        merged.addAll(long.drop(shared))
        merged.addAll(short.drop(shared))
        return ImageSearchPage(
            results = merged.distinctBy { it.id },
            hasMore = primary.hasMore || secondary.hasMore,
            page = page,
        )
    }

    companion object {
        /**
         * The secondary lane's per-page budget. Reddit answers a search in
         * one to three seconds on a healthy network; twelve covers a slow
         * first TLS handshake and one pacing interval, and anything past
         * it is a lane worth dropping for this page, not waiting on.
         */
        internal const val DEFAULT_SECONDARY_TIMEOUT_MS = 12_000L
    }
}
