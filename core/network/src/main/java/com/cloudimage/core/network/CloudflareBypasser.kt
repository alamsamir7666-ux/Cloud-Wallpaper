package com.cloudimage.core.network

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * The app-side Cloudflare bypass engine — the piece a scraping provider
 * cannot be. A plugin is pure JVM code with no UI toolkit, so it can never
 * execute the challenge's JavaScript; the app can, with a WebView, and
 * that is exactly what [CloudimageHttpClient] now does for every source:
 * when a request comes back challenged, the client asks this engine for a
 * clearance and replays the request under it.
 *
 * Two entry points, one cheap and one expensive:
 * - [bypassStateFor] is the read every request consults first — an
 *   in-memory hit costs a map lookup; the first-ever lookup per host also
 *   asks the solver whether the WebView cookie jar survived the last
 *   launch with a valid clearance (a warm start);
 * - [solve] is the challenge path — one WebView run per host at a time.
 *
 * The engine serializes solves per host: the home screen fires a dozen
 * section rows concurrently and they all hit the same origin, and a
 * challenge clears for the whole host, not for one row — so later
 * waiters ride the state the first solver earned instead of stacking
 * WebViews. Failures are remembered too, per rung: a host that just
 * failed a SOLVE goes quiet for [FAILURE_COOLDOWN_MS] so a stubborn zone
 * cannot spawn a WebView per grid tile, and its burned state is dropped
 * so later requests stop replaying cookies that no longer pass — while a
 * host that just failed a FETCH keeps its solve lane open, because the
 * next request in the same exchange still deserves the document rung
 * (the two rungs fail for different reasons: a clearance that cannot be
 * earned versus a page that never settles).
 */
class CloudflareBypasser(
    private val solver: CloudflareSolver,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One lock per host — solves for different hosts never block each other. */
    private val hostLocks = ConcurrentHashMap<String, Mutex>()

    /** Earned clearances by host; the User-Agent rides along inside each. */
    private val cleared = ConcurrentHashMap<String, CloudflareBypass>()

    /** Timestamps of the last failed solve per host, for the solve cooldown. */
    private val solveFailedAt = ConcurrentHashMap<String, Long>()

    /** Timestamps of the last failed document fetch per host — its own cooldown. */
    private val fetchFailedAt = ConcurrentHashMap<String, Long>()

    /** Hosts already asked for a persisted clearance this launch. */
    private val warmChecked = ConcurrentHashMap.newKeySet<String>()

    /**
     * The clearance to attach to a request about to run, or null when the
     * host has none on file. Cheap by design — every request calls it.
     */
    suspend fun bypassStateFor(url: String): CloudflareBypass? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        cleared[host]?.let { return it }
        // One warm look per host per launch: the WebView cookie jar outlives
        // the process, so a clearance earned yesterday can still be valid —
        // and if it is not, the challenge it earns is simply solved again.
        if (!warmChecked.add(host)) return null
        val persisted = solver.persistedStateFor(host) ?: return null
        return persisted.also { cleared[host] = it }
    }

    /**
     * Solves a challenge for the host of [url] and returns the fresh
     * clearance, or null when none could be earned.
     *
     * [staleState] is the clearance the challenged request itself carried
     * (null when it carried none). Its identity is what tells a genuinely
     * stale on-file state — cookies that stopped passing, the usual
     * half-hour expiry — from a state another coroutine earned while this
     * one waited on the host lock: the first is re-solved, the second is
     * simply returned.
     */
    suspend fun solve(
        url: String,
        staleState: CloudflareBypass? = null,
    ): CloudflareBypass? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        return hostLocks.computeIfAbsent(host) { Mutex() }.withLock {
            val current = cleared[host]
            if (current != null && current !== staleState) {
                // Solved while we waited on the lock — ride the newer state.
                return@withLock current
            }
            if (solveFailedAt[host]?.let { clock() - it < FAILURE_COOLDOWN_MS } == true) {
                return@withLock null
            }
            val fresh =
                runCatching {
                    withTimeoutOrNull(SOLVE_TIMEOUT_MS) { solver.solve(url) }
                }.getOrNull()
            if (fresh != null) {
                cleared[host] = fresh
                solveFailedAt.remove(host)
                fresh
            } else {
                cleared.remove(host)
                solveFailedAt[host] = clock()
                null
            }
        }
    }

    /**
     * Fetches a document through the WebView when neither a plain request
     * nor a clearance replay got past the zone's challenge — the one path
     * no fingerprint check can distinguish from a user browsing, because
     * it IS a browser fetching the page.
     *
     * Same lock as [solve]: one WebView at a time per host, and a caller
     * that waited on the lock hands back any clearance earned meanwhile
     * ([WebViewPage.html] null, [WebViewPage.clearance] set) so its request
     * can be replayed under it instead of stacking another page load.
     * [staleState] is the identity the last challenged attempt carried —
     * the same staleness rule solve applies. A failed fetch cools the host
     * down exactly like a failed solve, and a clearance the fetch earns is
     * cached for later requests to ride.
     */
    suspend fun webViewFetch(
        url: String,
        staleState: CloudflareBypass? = null,
    ): WebViewPage? {
        val host = url.toHttpUrlOrNull()?.host ?: return null
        return hostLocks.computeIfAbsent(host) { Mutex() }.withLock {
            val current = cleared[host]
            if (current != null && current !== staleState) {
                // Earned while we waited — cheaper than a page load; the
                // caller replays under this instead.
                return@withLock WebViewPage(html = null, clearance = current)
            }
            // The FETCH cooldown, not the solve one: a solve that just
            // failed must not stop this rung from trying the document —
            // they fail for different reasons, and the exchange that
            // could not earn a clearance is the one that most needs the
            // page fetched through the WebView.
            if (fetchFailedAt[host]?.let { clock() - it < FAILURE_COOLDOWN_MS } == true) {
                return@withLock null
            }
            val page =
                runCatching {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { solver.fetch(url) }
                }.getOrNull()
            if (page == null) {
                fetchFailedAt[host] = clock()
                // The state this fetch's challenged request carried is dead
                // too — the replay under it already failed. Drop it so later
                // requests stop trying it.
                if (cleared[host] === staleState) cleared.remove(host)
                null
            } else {
                fetchFailedAt.remove(host)
                page.clearance?.let { cleared[host] = it }
                page
            }
        }
    }

    companion object {
        /**
         * One WebView solve gets this long to settle — v1.2.4 raised it from
         * 20s to CloudStream's proven 60s window: managed challenges take
         * seconds, but INTERACTIVE ones (Turnstile checkboxes in the visible
         * dialog) need a human to notice and tap, and 20s cut them off
         * mid-gesture.
         */
        const val SOLVE_TIMEOUT_MS = 60_000L

        /**
         * A document fetch through the WebView — challenge settle plus page
         * load, with the same human-in-the-loop window as a solve.
         */
        const val FETCH_TIMEOUT_MS = 60_000L

        /**
         * A failed host goes this quiet before another solve or fetch is
         * attempted — v1.2.5 cut it from 60s to 15s. The cooldown's real job
         * is within one screen load: the home grid fires a dozen rows at the
         * same host, the host lock serializes them, and after the first
         * failure the rest must fail fast instead of stacking WebView trips.
         * That whole stampede lands within seconds, so 15s covers it — while
         * 60s had the side effect of swallowing a human's Retry: pressing
         * Retry a minute after a failure got an instant 403 with no dialog
         * at all, which reads as "the app is dead" rather than "the ladder
         * is cooling down".
         */
        const val FAILURE_COOLDOWN_MS = 15_000L

        /**
         * A bypasser with no machinery behind it: never a state, never a
         * solve, never a fetched page. The default [CloudimageHttpClient]
         * constructor argument, so the client stays constructible exactly
         * as before in every existing test, with the app graph wiring the
         * WebView-backed one.
         */
        val DISABLED: CloudflareBypasser = CloudflareBypasser(DisabledSolver)

        private object DisabledSolver : CloudflareSolver {
            override suspend fun persistedStateFor(host: String): CloudflareBypass? = null

            override suspend fun solve(url: String): CloudflareBypass? = null
        }
    }
}
