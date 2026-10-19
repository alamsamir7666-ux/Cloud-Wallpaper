package com.cloudimage.core.network

import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.ConcurrentHashMap

/**
 * The image hosts' penalty box (v1.2.28): a short-lived circuit breaker
 * for hosts that answer image requests with rate-limit responses.
 *
 * ## Why this exists
 *
 * Wallpaper sites throttle image traffic, and they do not always use
 * 429 to say it. The v1.2.28 live audit of wallpapercave.com caught its
 * image CDN answering whole minutes of `/dwp1x/` requests with **415
 * Unsupported Media Type** — a code that also has a genuine meaning
 * (the site's `dwp2x`/`dwp1x` families really do refuse user uploads
 * with 415), so it cannot be read as "slow down" on its face. The
 * windows came and went server-side; nothing about the request could
 * bring an image back during one.
 *
 * The damage multiplier was on our side. A grid of twenty cards fires
 * twenty thumb loads; the fullscreen viewer adds a display tier and the
 * original behind it; v1.2.26's single auto-retry fires again; scrolling
 * fires more. During a limit window every one of those requests fails,
 * the cards render their gray error painter, and the retry pressure
 * keeps the host's bucket drained — the user sees a dead source for as
 * long as they keep looking at it. That is the exact shape of the field
 * report behind this class: "wallpapercave not working, it don't show
 * any images".
 *
 * ## The policy
 *
 * - **429 answers penalize the host immediately.** A 429 says nothing
 *   else but "too many requests"; trusting it outright is safe.
 * - **415 answers count toward a trip: three inside 30 seconds
 *   penalize the host.** The genuine family-mismatch 415s a stray URL
 *   can still produce are one-offs, not bursts — the app's own URL
 *   flows (extension preview families, the viewer's display tiers)
 *   never request the combinations the site refuses. A burst of them
 *   is the limiter, and it needs the break.
 * - **A penalty lasts 75 seconds** — comfortably inside the observed
 *   recovery time (windows closed within two to three minutes; probing
 *   after ~60s already answered 200 again).
 * - **While penalized, image requests to the host fail fast** with a
 *   synthetic 503 and touch no network (see the Coil interceptor in
 *   `CloudimageApplication`). Coil's memory and disk caches sit in
 *   front of the network fetch, so already-seen images keep rendering
 *   from cache; only fresh fetches pause. The host's bucket refills
 *   in the quiet, and the next request after the window closes
 *   succeeds instead of re-tripping the limit.
 *
 * The box is deliberately narrow: it gates the app's image loader only.
 * Provider document fetches ([CloudimageHttpClient]) keep their own
 * Cloudflare-aware ladder — HTML surfaces were never observed rate
 * limited, and a source's feeds must keep flowing while its images
 * cool down.
 *
 * Thread safety: one [HostState] per host, created atomically, guarded
 * by its own monitor — the image loader runs requests on multiple
 * dispatchers.
 */
object ImageHostPenaltyBox {
    /** How many 415s inside [LIMIT_WINDOW_MS] trip the penalty. */
    private const val TRIP_ON_LIMITS = 3

    /** The window the 415 count rolls over. */
    private const val LIMIT_WINDOW_MS = 30_000L

    /** How long a penalized host stays in the box. */
    private const val PENALTY_MS = 75_000L

    /** Per-host bookkeeping: recent 415 timestamps and the penalty deadline. */
    private class HostState {
        var penalizedUntil = 0L
        val recentLimits = ArrayDeque<Long>()
    }

    private val hosts = ConcurrentHashMap<String, HostState>()

    /**
     * Records a limit-shaped response ([code] 415 or 429) for [host].
     * Returns true when this response put the host in the box — the
     * caller may log that transition.
     */
    fun noteLimitResponse(
        host: String,
        code: Int,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val state = hosts.computeIfAbsent(host.lowercase()) { HostState() }
        synchronized(state) {
            if (code == 429) {
                state.penalizedUntil = now + PENALTY_MS
                state.recentLimits.clear()
                return true
            }
            if (code != 415) return false
            // Drop timestamps that left the window, then count this one.
            while (state.recentLimits.isNotEmpty() && now - state.recentLimits.first() > LIMIT_WINDOW_MS) {
                state.recentLimits.removeFirst()
            }
            state.recentLimits.addLast(now)
            if (state.hasLimitBurst()) {
                state.penalizedUntil = now + PENALTY_MS
                state.recentLimits.clear()
                return true
            }
            return false
        }
    }

    /** True while [host] serves no fresh image fetches. */
    fun isPenalized(
        host: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean = penaltyRemainingMs(host, now) > 0

    /**
     * How much longer [host] stays penalized; zero when it is free (or
     * was never limited).
     */
    fun penaltyRemainingMs(
        host: String,
        now: Long = System.currentTimeMillis(),
    ): Long {
        val state = hosts[host.lowercase()] ?: return 0
        return synchronized(state) {
            (state.penalizedUntil - now).coerceAtLeast(0)
        }
    }

    /** For tests: forget every host. */
    fun clear() {
        hosts.clear()
    }

    /**
     * The OkHttp interceptor that gates image fetches on this box — the
     * one the app-wide Coil loader installs ahead of its branding
     * interceptors. While the request's host is penalized it answers a
     * synthetic 503 and touches no network; otherwise it passes through
     * and records 415/429 answers toward the next trip.
     */
    fun interceptor(): Interceptor =
        Interceptor { chain ->
            val host = chain.request().url.host
            if (isPenalized(host)) {
                Response
                    .Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(503)
                    .message("image host cooling down (rate limit)")
                    .body("".toResponseBody(null))
                    .build()
            } else {
                val response = chain.proceed(chain.request())
                if (response.code == 415 || response.code == 429) {
                    noteLimitResponse(host, response.code)
                }
                response
            }
        }

    private fun HostState.hasLimitBurst(): Boolean = recentLimits.size >= TRIP_ON_LIMITS
}
