package com.cloudimage.core.network

/**
 * One solved Cloudflare state for a single host: the clearance cookies a
 * WebView earned, plus the User-Agent it earned them with.
 *
 * Cloudflare binds `cf_clearance` to the (IP, User-Agent) pair that solved
 * the challenge, so the two values are inseparable — replaying the cookies
 * under any other agent, be it the host app's or a provider's browser
 * identity, is rejected as if they had never been earned. The pair
 * therefore travels and is stored together, and [CloudimageHttpClient]
 * applies both when a request carries one.
 */
data class CloudflareBypass(
    /** Complete cookie header value for the host, e.g. `__cf_bm=…; cf_clearance=…`. */
    val cookieHeader: String,
    /** The User-Agent the clearance was earned with; replayed verbatim. */
    val userAgent: String,
)

/**
 * Recognizes Cloudflare bot-management challenge responses — the "Just a
 * moment…" interstitial a site like wallpaperflare.com answers automated
 * traffic with.
 *
 * A challenge is one of:
 * - a 403/429/503 carrying the modern `cf-mitigated: challenge` response
 *   header (the strongest signal, checked first), or
 * - a 403/429/503 whose body carries an interstitial marker (the
 *   `cdn-cgi/challenge-platform` scripts every current page loads, the
 *   interstitial title, or the legacy `jschl` forms).
 *
 * Deliberately NOT recognized: the "Attention Required" hard block page.
 * A WebView solve cannot lift an IP-level block — it would burn its whole
 * timeout and a cooldown for nothing — so blocked hosts fall through as
 * ordinary non-2xx responses and sources surface their own readable
 * errors, exactly as they did before the bypass existed. Only challenges
 * that JavaScript in a real WebView can settle are worth waking it for.
 *
 * Telling the two apart needs more than the interstitial markers: the LIVE
 * block page also loads a `challenge-platform` script (its ray-ID copy
 * button), so markers alone misclassify blocks as challenges — verified
 * against a real "Attention Required!" response captured from
 * wallpaperflare.com. The block page's own copy is what separates it, so
 * [BLOCK_MARKERS] are consulted between the header and the interstitial
 * markers, and a body carrying block copy is never a challenge no matter
 * which scripts it loads. The header stays authoritative: an answer that
 * explicitly says `cf-mitigated: challenge` is one.
 */
object CloudflareChallenge {
    private val CHALLENGE_STATUSES = setOf(403, 429, 503)

    private const val HEADER_CF_MITIGATED = "cf-mitigated"

    /** Body markers of the challenge interstitials, current and legacy. */
    private val MARKERS =
        listOf(
            // /cdn-cgi/challenge-platform/ scripts — every current interstitial.
            "challenge-platform",
            // The interstitial title, present since the "Just a moment…" era.
            "Just a moment",
            // Managed-challenge copy shown while the browser works.
            "Enable JavaScript and cookies to continue",
            // Challenge form and script tokens (managed + legacy).
            "__cf_chl",
            // Legacy "Checking your browser" interstitial.
            "cf-browser-verification",
            // Legacy challenge answer field.
            "jschl",
        )

    /**
     * Body copy that identifies Cloudflare's BLOCK pages — IP blocks and
     * WAF denies. A WebView cannot lift these; matching copy means the
     * response must fall through as the ordinary error it is.
     */
    private val BLOCK_MARKERS =
        listOf(
            // The block page title, every variant since the classic page.
            "Attention Required",
            // The block body's apology line.
            "Sorry, you have been blocked",
            // The block body's access-denied line.
            "You are unable to access",
            // WAF custom-rule (ACL) denies.
            "error code: 1020",
        )

    /** Whether a completed exchange [payload] is a Cloudflare challenge page. */
    fun isChallenge(payload: HttpPayload): Boolean = isChallenge(payload.statusCode, payload.headers, payload.bodyText)

    /**
     * The detection rule on raw parts — status gate first (a challenge is
     * never a 2xx, and a 200 page that merely mentions the interstitial
     * copy, a blog post about Cloudflare say, must not wake the bypass),
     * then the header (the strongest signal), then the block copy (an
     * unsolvable page must not burn a solve), then the interstitial
     * markers.
     */
    fun isChallenge(
        statusCode: Int,
        headers: Map<String, List<String>>,
        bodyText: String,
    ): Boolean {
        if (statusCode !in CHALLENGE_STATUSES) return false
        if (headers.headerValue(HEADER_CF_MITIGATED)?.equals("challenge", ignoreCase = true) == true) return true
        if (bodyText.isEmpty()) return false
        if (BLOCK_MARKERS.any(bodyText::contains)) return false
        return MARKERS.any(bodyText::contains)
    }

    /** First value of [name], case-insensitively, or null when absent. */
    private fun Map<String, List<String>>.headerValue(name: String): String? =
        entries
            .firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()
}

/**
 * The machinery behind [CloudflareBypasser] — in production, a headless
 * Android WebView that runs the challenge for real. Split out as an
 * interface so the state machine around it (locking, cooldowns, warm
 * starts) is unit-testable on the JVM, where no WebView exists.
 */
interface CloudflareSolver {
    /**
     * Any clearance the system WebView cookie jar still holds for [host].
     * Cookies survive process death, so a restart can resume where the
     * last launch left off instead of eating a fresh challenge — as long
     * as the User-Agent is reconstructed to match, which the
     * implementation guarantees by returning the WebView default.
     */
    suspend fun persistedStateFor(host: String): CloudflareBypass?

    /**
     * Loads [url] in a headless WebView and waits for the challenge to
     * clear, returning the earned state, or null when it did not settle —
     * timeout, hard block, or no usable WebView on the device.
     */
    suspend fun solve(url: String): CloudflareBypass?
}
