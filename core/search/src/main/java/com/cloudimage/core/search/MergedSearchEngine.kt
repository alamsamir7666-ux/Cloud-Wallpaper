package com.cloudimage.core.search

import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Two global search engines answering as one (v1.2.14) — the deployed
 * bridge lane and the in-app Reddit lane, merged page by page. v1.2.23
 * adds an optional THIRD lane (the Supabase corpus) that rides the same
 * contract: concurrent, interleaved, individually droppable.
 *
 * The merge rules, in the order they matter:
 * - All lanes run CONCURRENTLY for every page — the bridge's slow
 *   upstream (tens of seconds on a cold start) must not wait behind
 *   Reddit or the corpus, and neither must wait behind the bridge.
 * - The SECONDARY lanes (Reddit, corpus) run under timeouts the primary
 *   never pays: they are the lanes whose network conditions vary wildly
 *   (carrier proxies, Reddit throttling a burst, a paused free-tier
 *   database), and a grid tile's worth of extra results is never worth
 *   stalling the page for. The primary's own timeouts stay the honest
 *   bound for it.
 * - Results INTERLEAVE, round-robin, in lane order: a wallpaper grid of
 *   mixed provenance reads as a broader search than any lane alone, and
 *   no source hogs the first screen. Ids are stable across engines
 *   (`gs:` + URL), so the same image found by two lanes collapses into
 *   one card and the view model's seen-id set keeps the merge honest
 *   across pages.
 * - A failed or timed-out secondary lane is INVISIBLE: the page is the
 *   remaining lanes', exactly as before this engine existed. This is the
 *   lane-failure contract that lets lanes be added without ever making
 *   search worse.
 * - A failed PRIMARY with healthy secondaries degrades to the
 *   secondaries alone — the resilience the mortal-bridge era asked for:
 *   a recycled sandbox no longer kills search outright while Reddit and
 *   the corpus still answer.
 * - Only when ALL lanes fail does the caller see a failure, and it is
 *   the PRIMARY's — the bridge's error taxonomy is the one the UI's
 *   messages were written for.
 */
class MergedSearchEngine(
    private val primary: ImageSearchEngine,
    private val secondary: ImageSearchEngine,
    /** Optional third lane (v1.2.23: the Supabase corpus); null keeps the v1.2.14 two-lane behavior. */
    private val tertiary: ImageSearchEngine? = null,
    /** The secondary lanes' overall budget per page; the primary has none. */
    private val secondaryTimeoutMs: Long = DEFAULT_SECONDARY_TIMEOUT_MS,
    /** The tertiary lane's budget; defaults to the secondary's. */
    private val tertiaryTimeoutMs: Long = secondaryTimeoutMs,
) : ImageSearchEngine {
    override val name: String =
        buildString {
            append(primary.name)
            append('+')
            append(secondary.name)
            tertiary?.let {
                append('+')
                append(it.name)
            }
        }

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
            val tertiaryPage =
                tertiary?.let { lane ->
                    async {
                        guardLane {
                            withTimeoutOrNull(tertiaryTimeoutMs) { lane.search(query, page, filters) }
                        }
                    }
                }
            // The primary lane runs unbounded by the secondaries' budgets:
            // its own timeouts (the bridge's 160 s worst case) are the
            // honest bound the search UI already promises.
            val primaryPage = guardLane { primary.search(query, page, filters) }
            merge(primaryPage, secondaryPage.await(), tertiaryPage?.await(), page)
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
     * The page-lane merge: success pages interleave in lane order, any
     * lone success stands alone, and only an all-lanes failure surfaces —
     * as the primary's failure, the taxonomy the UI's messages expect.
     */
    private fun merge(
        primary: ImageSearchResult?,
        secondary: ImageSearchResult?,
        tertiary: ImageSearchResult?,
        page: Int,
    ): ImageSearchResult {
        val successes =
            listOfNotNull(
                primary as? ImageSearchResult.Success,
                secondary as? ImageSearchResult.Success,
                tertiary as? ImageSearchResult.Success,
            )
        return when {
            successes.isNotEmpty() ->
                ImageSearchResult.Success(
                    page = interleave(successes.map { it.page }, page),
                )

            else ->
                (primary as? ImageSearchResult.Failure)
                    ?: ImageSearchResult.Failure(
                        error = ImageSearchError.NETWORK,
                        detail = "no search engine answered",
                    )
        }
    }

    /**
     * Round-robin the pages' results in lane order, collapsing ids two
     * lanes found. `hasMore` is any lane's — the scroll continues while
     * ANY engine still has pages, and the next page re-asks all of them.
     */
    private fun interleave(
        pages: List<ImageSearchPage>,
        page: Int,
    ): ImageSearchPage {
        val merged = ArrayList<Wallpaper>(pages.sumOf { it.results.size })
        val longest = pages.maxOfOrNull { it.results.size } ?: 0
        for (index in 0 until longest) {
            for (lane in pages) {
                lane.results.getOrNull(index)?.let(merged::add)
            }
        }
        return ImageSearchPage(
            results = merged.distinctBy { it.id },
            hasMore = pages.any { it.hasMore },
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
