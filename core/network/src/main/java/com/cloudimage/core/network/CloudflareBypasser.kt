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
 *
 * ## Zone-keyed clearances (v1.2.11)
 *
 * Clearances, host locks and cooldowns are keyed by the request host's
 * ZONE (see [cloudflareZoneOf]), not the hostname: Cloudflare issues
 * `cf_clearance` for the whole registrable domain, so a site's solve is
 * its image CDN's solve too. wallpapersden.com is the case in the wild —
 * the site and `images.wallpapersden.com` share one zone, and before
 * this change a challenge solved for the site left the CDN's requests
 * (thumbnails in Coil, originals in the viewer) carrying nothing, while
 * the CDN's own solve lane could pop a SECOND dialog for the very zone
 * the user had just cleared. One zone, one identity, one dialog.
 *
 * The WebView-only mark stays EXACT-HOST on purpose (v1.2.6's semantics):
 * it routes DOCUMENT requests to the engine lane, and only document
 * hosts earn it — an image host that happens to sit in a marked zone
 * still walks the honest ladder, now with the zone's clearance riding
 * its first attempt.
 *
 * ## WebView-only hosts (v1.2.6)
 *
 * Some zones — wallpaperflare.com is the one in the wild — bind their
 * clearance to the WebView's TLS fingerprint, not just (IP, User-Agent):
 * every OkHttp exchange is challenged no matter which cookies ride it,
 * while the engine's own page loads sail through. For such a host the
 * ladder's first two rungs are pure theater that the USER pays for — a
 * challenge dialog flash per request, then a visible page load per
 * request, which from the outside reads as the app "blinking" between
 * itself and the website. The engine therefore remembers, per host and
 * with a sliding TTL, that only the engine gets through: [markWebViewOnly]
 * is called the moment a WebView fetch delivers a document replays could
 * not, [isWebViewOnly] routes later requests straight to [fetchDocument]
 * — the invisible lane, no OkHttp round-trips, no dialogs — and every
 * successful fetch refreshes the mark so an actively browsed host never
 * lapses back into the slow theater while its cookie is alive.
 */
class CloudflareBypasser(
    private val solver: CloudflareSolver,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One lock per zone — solves for different zones never block each other. */
    private val zoneLocks = ConcurrentHashMap<String, Mutex>()

    /** Earned clearances by zone; the User-Agent rides along inside each. */
    private val cleared = ConcurrentHashMap<String, CloudflareBypass>()

    /** Timestamps of the last failed solve per zone, for the solve cooldown. */
    private val solveFailedAt = ConcurrentHashMap<String, Long>()

    /** Timestamps of the last failed document fetch per zone — its own cooldown. */
    private val fetchFailedAt = ConcurrentHashMap<String, Long>()

    /** Zones already asked for a persisted clearance this launch. */
    private val warmChecked = ConcurrentHashMap.newKeySet<String>()

    /**
     * Hosts whose zone only the WebView engine gets through, with the
     * epoch instant each mark lapses at — see the class KDoc's
     * WebView-only section.
     */
    private val webViewOnlyUntil = ConcurrentHashMap<String, Long>()

    /**
     * The clearance to attach to a request about to run, or null when the
     * host has none on file. Cheap by design — every request calls it.
     */
    suspend fun bypassStateFor(url: String): CloudflareBypass? {
        val zone = url.toHttpUrlOrNull()?.host?.let(::cloudflareZoneOf) ?: return null
        cleared[zone]?.let { return it }
        // One warm look per zone per launch: the WebView cookie jar outlives
        // the process, so a clearance earned yesterday can still be valid —
        // and if it is not, the challenge it earns is simply solved again.
        if (!warmChecked.add(zone)) return null
        val persisted = solver.persistedStateFor(zone) ?: return null
        return persisted.also { cleared[zone] = it }
    }

    /**
     * The zone's clearance as a non-suspending snapshot — the seam the
     * app's image pipeline (Coil) consults per request from OkHttp's own
     * dispatcher threads (v1.2.11). Image loads never ride the document
     * ladder, so an image host that sits in the same Cloudflare zone as
     * its site (wallpapersden.com's `images.` CDN) used to ask for a
     * clearance that existed and receive nothing; the snapshot hands it
     * the earned (cookies, User-Agent) pair so its requests replay under
     * the identity the challenge was earned with. Null when the zone has
     * no clearance on file — the caller brands its request as usual.
     *
     * Deliberately memory-only: the persisted warm read stays on the
     * ladder's own first request for the zone, where it already lives.
     */
    fun currentBypassFor(host: String): CloudflareBypass? = cleared[cloudflareZoneOf(host)]

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
        val zone = url.toHttpUrlOrNull()?.host?.let(::cloudflareZoneOf) ?: return null
        return zoneLocks.computeIfAbsent(zone) { Mutex() }.withLock {
            val current = cleared[zone]
            if (current != null && current !== staleState) {
                // Solved while we waited on the lock — ride the newer state.
                return@withLock current
            }
            if (solveFailedAt[zone]?.let { clock() - it < FAILURE_COOLDOWN_MS } == true) {
                return@withLock null
            }
            val fresh =
                runCatching {
                    withTimeoutOrNull(SOLVE_TIMEOUT_MS) { solver.solve(url) }
                }.getOrNull()
            if (fresh != null) {
                cleared[zone] = fresh
                solveFailedAt.remove(zone)
                fresh
            } else {
                cleared.remove(zone)
                solveFailedAt[zone] = clock()
                null
            }
        }
    }

    /**
     * Whether [url]'s host is currently in the engine-only mode — the zone
     * answers every HTTP-client exchange with a challenge, so callers skip
     * straight to [fetchDocument]. Cheap: a map lookup and a clock read.
     */
    fun isWebViewOnly(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        val until = webViewOnlyUntil[host] ?: return false
        if (clock() >= until) {
            webViewOnlyUntil.remove(host)
            return false
        }
        return true
    }

    /**
     * Remembers that only the WebView engine gets through this host — the
     * mark a successful document fetch refreshes and one request in
     * [CloudimageHttpClient] sets when its replay stays challenged.
     */
    fun markWebViewOnly(url: String) {
        val host = url.toHttpUrlOrNull()?.host ?: return
        webViewOnlyUntil[host] = clock() + WEB_VIEW_ONLY_TTL_MS
    }

    /**
     * The WebView-only lane: fetch the document through the engine with no
     * replay theater around it. Serialized by the same per-host lock as
     * [solve] so one WebView runs at a time, cooled by the same FETCH
     * cooldown as [webViewFetch], and refreshing the host's WebView-only
     * mark on every success so an actively browsed host never lapses back
     * into the visible ladder while its cookie is alive.
     */
    suspend fun fetchDocument(url: String): WebViewPage? {
        val zone = url.toHttpUrlOrNull()?.host?.let(::cloudflareZoneOf) ?: return null
        return zoneLocks.computeIfAbsent(zone) { Mutex() }.withLock {
            if (fetchFailedAt[zone]?.let { clock() - it < FAILURE_COOLDOWN_MS } == true) {
                return@withLock null
            }
            val page =
                runCatching {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { solver.fetch(url) }
                }.getOrNull()
            if (page == null) {
                fetchFailedAt[zone] = clock()
                null
            } else {
                fetchFailedAt.remove(zone)
                page.clearance?.let { cleared[zone] = it }
                markWebViewOnly(url)
                page
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
        val zone = url.toHttpUrlOrNull()?.host?.let(::cloudflareZoneOf) ?: return null
        return zoneLocks.computeIfAbsent(zone) { Mutex() }.withLock {
            val current = cleared[zone]
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
            if (fetchFailedAt[zone]?.let { clock() - it < FAILURE_COOLDOWN_MS } == true) {
                return@withLock null
            }
            val page =
                runCatching {
                    withTimeoutOrNull(FETCH_TIMEOUT_MS) { solver.fetch(url) }
                }.getOrNull()
            if (page == null) {
                fetchFailedAt[zone] = clock()
                // The state this fetch's challenged request carried is dead
                // too — the replay under it already failed. Drop it so later
                // requests stop trying it.
                if (cleared[zone] === staleState) cleared.remove(zone)
                null
            } else {
                fetchFailedAt.remove(zone)
                page.clearance?.let { cleared[zone] = it }
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
         * How long a WebView-only mark lasts — long enough that an actively
         * browsed host never lapses (every fetch refreshes it), short enough
         * that a zone which later relaxes its fingerprint check gets its
         * cheap replay path back within a session.
         */
        const val WEB_VIEW_ONLY_TTL_MS = 30 * 60_000L

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
