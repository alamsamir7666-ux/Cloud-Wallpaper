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
 * One document a WebView fetched past a challenge the HTTP client could
 * not get through: the settled page's HTML, plus any clearance the trip
 * earned along the way.
 *
 * The two fields are independent outcomes of one WebView run — a page can
 * settle on real content and leave a `cf_clearance` in the cookie jar
 * (the common case: the challenge auto-cleared, then the zone served the
 * document), or settle without ever earning a cookie (zones that
 * challenge every navigation), or the run can produce nothing at all.
 * A [WebViewPage] with a null `html` and a non-null `clearance` means
 * "no document, but here is a fresher identity to replay under" — the
 * state another coroutine earned while this caller waited, say.
 */
data class WebViewPage(
    /** The settled document's HTML, or null when no usable page appeared. */
    val html: String?,
    /** A clearance the fetch earned or that superseded it; null when none. */
    val clearance: CloudflareBypass?,
)

/**
 * Recognizes Cloudflare bot-management challenge responses — the "Just a
 * moment…" interstitial a site like wallpaperflare.com answers automated
 * traffic with.
 *
 * A challenge is one of:
 * - a 403/429/503 carrying the modern `cf-mitigated: challenge` response
 *   header (the strongest signal, checked first), or
 * - a 403/429/503 served by a Cloudflare edge (`server: cloudflare`, the
 *   `cloudflare-nginx` legacy spelling included), or
 * - a 403/429/503 whose body carries an interstitial marker (the
 *   `cdn-cgi/challenge-platform` scripts every current page loads, the
 *   interstitial title, or the legacy `jschl` forms).
 *
 * The server rule is CloudStream's, verbatim in spirit — its CloudflareKiller
 * fires on ANY `403/503` whose `Server` header is a Cloudflare spelling, with
 * no exception for block pages — and it exists because of what v1.2.5
 * learned in the wild: a zone's WAF can answer the HTTP client's requests
 * with the "Attention Required" BLOCK page (the v1.2.4 detector correctly
 * recognized the copy and declined to wake the solver) while the very same
 * zone serves the WebView a challenge it settles, or even plain content —
 * the block targeted the client's TLS/UA fingerprint, not the browser
 * engine. Giving up on block copy therefore strands sources the WebView
 * could have saved, and the user's only signal that the ladder is dead is
 * the 403 banner with no challenge dialog at all. The detector now lets
 * the WebView decide: block copy only vetoes markers on responses NOT
 * served by a Cloudflare edge, where no WebView experiment could help.
 *
 * Deliberately NOT recognized: plain API 403s with non-Cloudflare servers —
 * a bad API key is not something a WebView can settle — and 2xx pages that
 * merely mention interstitial copy (a blog post about Cloudflare is not a
 * challenge; the status gate runs before any marker).
 */
object CloudflareChallenge {
    private val CHALLENGE_STATUSES = setOf(403, 429, 503)

    private const val HEADER_CF_MITIGATED = "cf-mitigated"

    /** The edge software header — `server: cloudflare` and its legacy spelling. */
    private const val HEADER_SERVER = "server"

    /**
     * The `Server` values a Cloudflare edge answers with — CloudStream's
     * CLOUDFLARE_SERVERS list. A 403/429/503 from one of these is
     * solver-worthy no matter what its body says.
     */
    private val CLOUDFLARE_SERVERS = setOf("cloudflare", "cloudflare-nginx")

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

    /**
     * Titles Cloudflare's own pages carry and content pages never do —
     * the interstitials, the block pages, and the access-denied variants.
     * Matched inside the extracted `<title>` element, case-insensitively.
     */
    private val TITLE_MARKERS =
        listOf(
            // The managed-challenge interstitial title.
            "Just a moment",
            // The IP-block / WAF-block page title.
            "Attention Required",
            // The access-denied (1020 / country rule) page title.
            "Access denied",
            // Legacy "Checking your browser" interstitial title.
            "Checking your browser",
            // Legacy "Please wait" interstitial title.
            "Please wait",
            // The JS-off challenge title.
            "Enable JavaScript and cookies to continue",
            // Generic Turnstile shell title.
            "Security check",
        )

    /**
     * Challenge FORM structures — present only on real challenge shells,
     * never on the content pages a bot-managed zone injects its
     * JavaScript-Detections script into.
     */
    private val FORM_MARKERS =
        listOf(
            // Challenge form and script tokens (managed + legacy).
            "__cf_chl",
            // Legacy "Checking your browser" interstitial.
            "cf-browser-verification",
            // Legacy challenge answer field.
            "jschl",
        )

    /** The `<title>` element, whatever case the page spells it in. */
    private val TITLE_REGEX = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.IGNORE_CASE)

    /** Whether a completed exchange [payload] is a Cloudflare challenge page. */
    fun isChallenge(payload: HttpPayload): Boolean = isChallenge(payload.statusCode, payload.headers, payload.bodyText)

    /**
     * Whether a WebView-rendered [html] document is still Cloudflare's own
     * interstitial copy — a challenge shell OR a hard-block page — rather
     * than the site's content. Used by the WebView fetch path: a document
     * that still looks like Cloudflare furniture has not settled yet, and
     * one that ends on block copy never will.
     *
     * v1.2.4: the rule is TITLE-led, because the old marker-any rule broke
     * on exactly the pages it existed to accept. Cloudflare zones with bot
     * management inject a `/cdn-cgi/challenge-platform/.../jsd/main.js`
     * (JavaScript Detections) script into their NORMAL pages, so a settled
     * content page carries the "challenge-platform" string too — and the
     * fetch rung kept rejecting the very content it had just earned,
     * timed out, and surfaced the original 403. Cloudflare's own pages
     * announce themselves in their `<title>` — "Just a moment…", "Attention
     * Required!", "Checking your browser", the access-denied variants —
     * titles no real content page shares; the legacy challenge FORM
     * markers (`__cf_chl`, `jschl`, `cf-browser-verification`) exist only
     * on actual challenge shells. A document with neither is content,
     * whatever scripts the zone injected into it.
     */
    fun isInterstitialDocument(html: String): Boolean {
        if (html.isEmpty()) return false
        val title =
            TITLE_REGEX
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        if (TITLE_MARKERS.any { title.contains(it, ignoreCase = true) }) return true
        return FORM_MARKERS.any(html::contains)
    }

    /**
     * The detection rule on raw parts — status gate first (a challenge is
     * never a 2xx, and a 200 page that merely mentions the interstitial
     * copy, a blog post about Cloudflare say, must not wake the bypass),
     * then the header (the strongest signal), then the Cloudflare server
     * rule (v1.2.5: a Cloudflare edge's 403/429/503 wakes the WebView
     * whatever its body says, CloudStream's own trigger — the block may
     * bind the HTTP client's fingerprint without binding the browser
     * engine), then the block copy (which only vetoes markers on responses
     * no Cloudflare edge served), then the interstitial markers.
     */
    fun isChallenge(
        statusCode: Int,
        headers: Map<String, List<String>>,
        bodyText: String,
    ): Boolean {
        if (statusCode !in CHALLENGE_STATUSES) return false
        if (headers.headerValue(HEADER_CF_MITIGATED)?.equals("challenge", ignoreCase = true) == true) return true
        val fromCloudflareEdge = headers.headerValue(HEADER_SERVER)?.lowercase() in CLOUDFLARE_SERVERS
        if (fromCloudflareEdge) return true
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

    /**
     * Loads [url] in a WebView — running whatever challenge it carries for
     * real — and returns the SETTLED DOCUMENT, not just the clearance that
     * got there: the last-resort fetch for zones whose challenges no
     * cookie replay can pass (fingerprint-strict configurations, where
     * the HTTP client's TLS handshake itself is what the zone rejects).
     *
     * Returns null when no usable page appeared — timeout, hard block, no
     * WebView. Default null for JVM test doubles and any solver with no
     * machinery: the caller degrades to surfacing the original response,
     * exactly as before this rung existed. A trip that settles on content
     * usually also leaves a `cf_clearance` in the system cookie jar; it
     * rides along in the page so later requests can try the cheaper
     * replay path first.
     */
    suspend fun fetch(url: String): WebViewPage? = null
}
