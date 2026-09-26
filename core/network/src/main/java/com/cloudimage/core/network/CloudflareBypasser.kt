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
 * WebViews. Failures are remembered too: a host that just failed to
 * clear goes quiet for [FAILURE_COOLDOWN_MS] so a stubborn zone cannot
 * spawn a WebView per grid tile, and its burned state is dropped so
 * later requests stop replaying cookies that no longer pass.
 */
class CloudflareBypasser(
    private val solver: CloudflareSolver,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One lock per host — solves for different hosts never block each other. */
    private val hostLocks = ConcurrentHashMap<String, Mutex>()

    /** Earned clearances by host; the User-Agent rides along inside each. */
    private val cleared = ConcurrentHashMap<String, CloudflareBypass>()

    /** Timestamps of the last failed solve per host, for the cooldown. */
    private val failedAt = ConcurrentHashMap<String, Long>()

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
            if (failedAt[host]?.let { clock() - it < FAILURE_COOLDOWN_MS } == true) {
                return@withLock null
            }
            val fresh =
                runCatching {
                    withTimeoutOrNull(SOLVE_TIMEOUT_MS) { solver.solve(url) }
                }.getOrNull()
            if (fresh != null) {
                cleared[host] = fresh
                failedAt.remove(host)
                fresh
            } else {
                cleared.remove(host)
                failedAt[host] = clock()
                null
            }
        }
    }

    companion object {
        /** One WebView solve gets this long to settle; managed challenges take seconds. */
        const val SOLVE_TIMEOUT_MS = 20_000L

        /** A failed host goes this quiet before another solve is attempted. */
        const val FAILURE_COOLDOWN_MS = 60_000L

        /**
         * A bypasser with no machinery behind it: never a state, never a
         * solve. The default [CloudimageHttpClient] constructor argument,
         * so the client stays constructible exactly as before in every
         * existing test, with the app graph wiring the WebView-backed one.
         */
        val DISABLED: CloudflareBypasser = CloudflareBypasser(DisabledSolver)

        private object DisabledSolver : CloudflareSolver {
            override suspend fun persistedStateFor(host: String): CloudflareBypass? = null

            override suspend fun solve(url: String): CloudflareBypass? = null
        }
    }
}
