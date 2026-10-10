package com.cloudimage.core.network

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume

/**
 * The production [CloudflareSolver]: a WebView that loads the challenged
 * URL and lets the challenge's own JavaScript settle it, the way a real
 * browser does — no fingerprint spoofing, no captcha guessing.
 *
 * What makes this work where OkHttp cannot:
 * - the WebView IS a browser engine, so the challenge scripts run for
 *   real against real browser APIs and a real device profile;
 * - the system cookie jar (`CookieManager`) keeps whatever the challenge
 *   issues — `cf_clearance` first of all — and persists it across
 *   launches, which is what [persistedStateFor] reads back;
 * - the clearance is bound to the WebView's own default User-Agent, so
 *   that agent is reported alongside the cookies and replayed verbatim
 *   by [CloudimageHttpClient].
 *
 * v1.0.16: the WebView is ATTACHED to a real window. The v1.0.15 solver
 * created its WebView off-window, and Cloudflare's challenge scripts
 * refuse to settle there — `document.visibilityState` reads "hidden" and
 * the challenge's animation-frame work stalls without a surface — so
 * solves timed out and users saw the raw 403 anyway (the on-device
 * failure Wallpaperflare installs reported). When the app is in the
 * foreground, the solve now runs inside a borderless full-screen dialog
 * over [foregroundActivity]: the page gets a real viewport, a visible
 * document, and — if Cloudflare escalates to an interactive challenge —
 * the user's own finger, which no headless technique can fake. In the
 * background (a Muzei artwork refresh, say) there is no window to attach
 * to, and the detached WebView remains the best effort.
 *
 * v1.2.3: two lessons from WallpaperFlare installs in the wild. First,
 * a suspicion that the default WebView User-Agent's `wv` token read as
 * "embedded browser" to Cloudflare's scoring, so the solver briefly
 * reported a hand-picked clean mobile-Chrome agent (see v1.2.4 — that
 * suspicion was wrong). Second, some zones bind their clearances to more
 * than (IP, User-Agent) — the replay's TLS fingerprint is checked too,
 * and no cookie jar can fix an OkHttp handshake — so [fetch] was added:
 * the WebView loads the page as a browser, settles whatever it meets,
 * and hands back the DOCUMENT itself, the one answer no fingerprint
 * check can tell apart from a user tapping a link.
 *
 * v1.2.4: the v1.2.3 UA override was wrong, and CloudStream — whose
 * CloudflareKiller settles these challenges at scale — says so in its
 * own source: "Don't set user agent, setting user agent will make
 * cloudflare break", "Cloudflare needs default user agent". A fixed
 * Chrome/131 claim on an engine that is really a different Chromium
 * fails Turnstile's UA-versus-engine consistency checks, and a browser
 * nine majors stale scores badly on top of that. The solver therefore
 * wears the device's DEFAULT WebView User-Agent verbatim — read once by
 * [defaultUserAgent] and paired with every clearance, so replays stay
 * bound to the identity that actually earned them. Two more fidelity
 * fixes ride along from the same proven recipe: network images load
 * freely (CloudStream removed its own blocking because suppressing the
 * challenge page's resources broke the captcha orchestration), and the
 * mid-run reload is gone (reloading a challenge mid-settle resets it —
 * fatal when a human is mid-tap on an interactive Turnstile). Deadlines
 * rise to a full minute, CloudStream's window for slow and interactive
 * challenges.
 *
 * v1.2.5: two more lessons from the wild, both CloudStream-faithful.
 * First, the solve bails out EARLY when the WebView settles on real,
 * unchallenged content with no `cf_clearance` to wait for — that is the
 * shape of a zone whose WAF blocks the HTTP client's fingerprint but
 * serves the browser engine plainly, and waiting the full minute for a
 * cookie that will never arrive only delays the caller's next rung (the
 * WebView document fetch, which handles exactly that zone). Second, a
 * solve or fetch that times out WIPES the host's cookies from the system
 * jar — CloudStream's CloudflareKiller clears cookies between sessions
 * for the same reason: a jar poisoned by a failed challenge loop makes
 * the next WebView trip start from the losing state instead of clean.
 *
 * Everything runs on the main dispatcher: WebView demands a Looper thread,
 * and the main thread is the one that always has one. The poll loop below
 * is `delay`-based, so the caller's timeout (see [CloudflareBypasser])
 * cancels it cleanly and the `finally` tears the WebView down.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class WebViewCloudflareSolver(
    private val context: Context,
    private val foregroundActivity: () -> Activity?,
) : CloudflareSolver {
    override suspend fun persistedStateFor(host: String): CloudflareBypass? =
        withContext(Dispatchers.Main) {
            runCatching {
                val cookies = cookieManager.getCookie("https://$host") ?: return@runCatching null
                if (CLEARANCE_COOKIE !in cookies) return@runCatching null
                // Reconstructed under the agent this solver ALWAYS earns
                // with — the device's default WebView UA (v1.2.4), not a
                // hand-picked browser string, so the (cookies, agent) pair
                // matches what a solve or fetch actually produced. Without
                // that agent there is no honest pair to reconstruct.
                val agent = defaultUserAgent() ?: return@runCatching null
                CloudflareBypass(
                    cookieHeader = cookies,
                    userAgent = agent,
                )
            }.getOrNull()
        }

    override suspend fun solve(url: String): CloudflareBypass? =
        withContext(Dispatchers.Main) {
            val host = url.toHttpUrlOrNull()?.host
            if (host == null) {
                null
            } else {
                val activity =
                    foregroundActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
                if (activity == null) {
                    solveDetached(url, host)
                } else {
                    // Showing over an activity that dies mid-handoff throws —
                    // the detached WebView is the fallback, not a null.
                    runCatching { solveAttached(activity, url, host) }.getOrNull()
                        ?: solveDetached(url, host)
                }
            }
        }

    override suspend fun fetch(url: String): WebViewPage? =
        withContext(Dispatchers.Main) {
            val host = url.toHttpUrlOrNull()?.host
            if (host == null) {
                null
            } else {
                // v1.2.6's invisible lane: with a clearance in the jar there
                // is no challenge to settle, so the document loads fine in
                // a DETACHED WebView — no dialog, nothing the user sees. A
                // detached trip that lands on the interstitial instead
                // proves the cookies dead: wipe them (the next solve
                // deserves a clean slate) and escalate to the attached
                // dialog, where the challenge gets the real viewport it
                // needs — the one visible dialog per cookie lifetime.
                if (hasClearanceFor(host)) {
                    fetchDetached(url, host)?.let { return@withContext it }
                    clearCookiesFor(host)
                }
                val activity =
                    foregroundActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
                if (activity == null) {
                    fetchDetached(url, host)
                } else {
                    runCatching { fetchAttached(activity, url, host) }.getOrNull()
                        ?: fetchDetached(url, host)
                }
            }
        }

    /** Whether the system jar still holds a clearance for [host]. */
    private fun hasClearanceFor(host: String): Boolean {
        val cookies = runCatching { cookieManager.getCookie("https://$host") }.getOrNull()
        return cookies != null && CLEARANCE_COOKIE in cookies
    }

    /**
     * The foreground path: the challenge runs in a full-screen borderless
     * dialog over the resumed activity. The user briefly sees Cloudflare's
     * own "Just a moment…" page — the honest UI for what is happening —
     * and can complete an interactive challenge with a tap if one appears.
     * Dismissal in `finally` guarantees the dialog never outlives the
     * solve, whatever its outcome.
     */
    private suspend fun solveAttached(
        activity: Activity,
        url: String,
        host: String,
    ): CloudflareBypass? {
        val client = ChallengeClient()
        val webView = createWebView(client)
        val dialog =
            Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                setContentView(
                    webView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            }
        return try {
            dialog.show()
            webView.loadUrl(url)
            waitForClearance(client, webView, host, url)
        } finally {
            runCatching { dialog.dismiss() }
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    /**
     * The background path — no window to attach to, so the WebView runs
     * detached, exactly as v1.0.15 did for every solve. Best effort: a
     * hidden document is precisely what Cloudflare's scripts score
     * against, and many challenges will simply not settle here.
     */
    private suspend fun solveDetached(
        url: String,
        host: String,
    ): CloudflareBypass? {
        val client = ChallengeClient()
        val webView = createWebView(client)
        return try {
            webView.loadUrl(url)
            waitForClearance(client, webView, host, url)
        } finally {
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    /** The foreground document fetch — the same honest dialog as a solve. */
    private suspend fun fetchAttached(
        activity: Activity,
        url: String,
        host: String,
    ): WebViewPage? {
        val client = ChallengeClient()
        val webView = createWebView(client)
        val dialog =
            Dialog(activity).apply {
                requestWindowFeature(Window.FEATURE_NO_TITLE)
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                setContentView(
                    webView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    setLayout(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            }
        return try {
            dialog.show()
            webView.loadUrl(url)
            awaitDocument(client, webView, host, url, FETCH_DEADLINE_MS, failFastOnInterstitial = false)
        } finally {
            runCatching { dialog.dismiss() }
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    /** The background document fetch — best effort, like the background solve. */
    private suspend fun fetchDetached(
        url: String,
        host: String,
    ): WebViewPage? {
        val client = ChallengeClient()
        val webView = createWebView(client)
        return try {
            webView.loadUrl(url)
            awaitDocument(client, webView, host, url, DETACHED_DEADLINE_MS, failFastOnInterstitial = true)
        } finally {
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    /**
     * A WebView configured to look and behave like the browser the zone
     * wants to see: JavaScript on (the challenge IS JavaScript — no JS,
     * no clearance) and DOM storage on (Turnstile reads it like any real
     * page). Nothing else is touched — v1.2.4: the User-Agent stays the
     * WebView default (CloudStream's CloudflareKiller passes `null` for
     * exactly this reason — "setting user agent will make cloudflare
     * break"), and network images load freely (CloudStream removed its
     * own image blocking because suppressing the challenge page's
     * resources broke the captcha). Every deviation from a plain browser
     * is a signal Turnstile can score; the fewer, the better.
     */
    private fun createWebView(client: ChallengeClient): WebView {
        val cookies = cookieManager
        cookies.setAcceptCookie(true)
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
        cookies.setAcceptThirdPartyCookies(webView, true)
        // The counting client keeps the challenge's redirects inside this
        // view and tells the poll loop when a page finished loading.
        webView.webViewClient = client
        return webView
    }

    /**
     * Waits for the clearance cookie to land. Managed challenges settle
     * between navigations — the script runs, sets `cf_clearance`, and may
     * reload the page — so a short poll over the cookie jar catches every
     * shape of it without parsing page state. There is deliberately NO
     * reload heuristic any more (v1.2.4): CloudStream's solver, which
     * settles these challenges at scale, never reloads, and reloading a
     * challenge mid-settle resets it — fatal when a human is mid-tap on
     * an interactive Turnstile in the visible dialog. A slow orchestration
     * simply gets the full deadline to finish. The hard deadline is a
     * backstop above the engine's own timeout; whichever fires first, the
     * caller's `finally` still tears the WebView down.
     *
     * v1.2.5 adds the early exit and the wipe. A page that finished,
     * settled, and reads as real CONTENT (no Cloudflare furniture, per
     * [CloudflareChallenge.isInterstitialDocument]) means the zone is not
     * challenging the WebView at all — the block that woke the solver
     * bound the HTTP client's fingerprint, not the browser engine — so
     * there is no clearance to wait for and the caller's WebView-fetch
     * rung is the answer, now instead of fifty seconds from now. A
     * deadline that expires with nothing earned wipes the host's cookies:
     * CloudStream clears its jar between solve sessions for exactly this
     * reason, and a jar that still carries a failed trip's state makes
     * the next trip start from the loser's position.
     */
    private suspend fun waitForClearance(
        client: ChallengeClient,
        webView: WebView,
        host: String,
        url: String,
    ): CloudflareBypass? {
        // Read once: the identity this solve binds its cookies to. A device
        // that cannot report a default agent cannot earn an honest pair.
        val agent = defaultUserAgent() ?: return null
        val deadline = System.currentTimeMillis() + SOLVE_DEADLINE_MS
        var contentCheckedForFinish = -1
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val cookies =
                cookieManager.getCookie(url)
                    ?: cookieManager.getCookie("https://$host")
                    ?: continue
            if (CLEARANCE_COOKIE in cookies) {
                // Persist now: a later cold start reads this back as a warm one.
                runCatching { cookieManager.flush() }
                return CloudflareBypass(
                    cookieHeader = cookies,
                    userAgent = agent,
                )
            }
            // The unchallenged-content early exit — see the KDoc above. The
            // clearance check runs first so a settle-and-swap navigation
            // (cookie lands, then content replaces the shell) returns the
            // earned state rather than bailing.
            val finishedAt = client.lastFinishedAt
            val settled = finishedAt > 0 && System.currentTimeMillis() - finishedAt >= SETTLE_AFTER_FINISH_MS
            if (settled && client.finished != contentCheckedForFinish) {
                contentCheckedForFinish = client.finished
                val html = webView.extractHtml() ?: continue
                if (!CloudflareChallenge.isInterstitialDocument(html)) return null
            }
        }
        // Nothing earned in the window — the jar carries this failed trip's
        // cookies, and the next solve deserves a clean slate.
        clearCookiesFor(host)
        return null
    }

    /**
     * Waits for the loaded document to become the site's own content —
     * the fetch path's settle condition. The challenge lifecycle is a
     * chain of navigations: the interstitial finishes, its script runs,
     * the view reloads, and THEN the content page arrives; so the loop
     * re-extracts the document once per finished navigation (after a
     * settle beat for the DOM to complete) and asks
     * [CloudflareChallenge.isInterstitialDocument] whether Cloudflare's
     * furniture is still on screen. The first extraction that reads like
     * site content wins, carrying whatever clearance the trip earned.
     * Like the solve path there is no reload (v1.2.4) — a page that parks
     * simply rides the full deadline. A page that never settles by the
     * deadline is a failure — null, no partial credit.
     *
     * [failFastOnInterstitial] is the detached lane's discipline
     * (v1.2.6): a hidden document cannot settle a challenge — that is
     * the v1.0.16 lesson — so a detached trip that settles on the
     * interstitial returns null IMMEDIATELY instead of parking for a
     * minute, letting the caller escalate to the attached dialog. The
     * attached lane keeps waiting, because there the interstitial is a
     * challenge actively settling in front of the user.
     */
    private suspend fun awaitDocument(
        client: ChallengeClient,
        webView: WebView,
        host: String,
        url: String,
        deadlineMs: Long,
        failFastOnInterstitial: Boolean,
    ): WebViewPage? {
        val deadline = System.currentTimeMillis() + deadlineMs
        var extractedForFinish = -1
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val finishedAt = client.lastFinishedAt
            val settled = finishedAt > 0 && System.currentTimeMillis() - finishedAt >= SETTLE_AFTER_FINISH_MS
            if (settled && client.finished != extractedForFinish) {
                extractedForFinish = client.finished
                val html = webView.extractHtml() ?: continue
                if (!CloudflareChallenge.isInterstitialDocument(html)) {
                    runCatching { cookieManager.flush() }
                    return WebViewPage(html = html, clearance = clearanceFor(host, url))
                }
                if (failFastOnInterstitial) return null
            }
        }
        // No page ever settled — wipe the failed trip's cookies so the next
        // attempt over this host starts clean (v1.2.5).
        clearCookiesFor(host)
        return null
    }

    /** The clearance in the jar right now, paired with the solver's agent. */
    private fun clearanceFor(
        host: String,
        url: String,
    ): CloudflareBypass? {
        val cookies =
            cookieManager.getCookie(url)
                ?: cookieManager.getCookie("https://$host")
                ?: return null
        if (CLEARANCE_COOKIE !in cookies) return null
        val agent = defaultUserAgent() ?: return null
        return CloudflareBypass(cookieHeader = cookies, userAgent = agent)
    }

    /**
     * Drops every cookie the system jar holds for [host] — the v1.2.5
     * fresh-slate discipline for failed trips (CloudStream's
     * CloudflareKiller clears its jar between solve sessions for the same
     * reason). [CookieManager] exposes no per-domain removal, so each
     * cookie is re-set for the host URL with a past `Expires` and
     * `Max-Age=0`, which the jar's RFC 6265 semantics honor as a deletion;
     * only this host's names are touched, other sources' clearances ride
     * on untouched. Best-effort by design — a jar that ignores the expiry
     * only keeps stale cookies, and the ladder re-earns whatever it needs.
     */
    private fun clearCookiesFor(host: String) {
        runCatching {
            val url = "https://$host"
            val cookies = cookieManager.getCookie(url) ?: return
            for (pair in cookies.split(';')) {
                val name = pair.substringBefore('=').trim()
                if (name.isEmpty()) continue
                cookieManager.setCookie(
                    url,
                    "$name=; Path=/; Domain=$host; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0",
                )
            }
            cookieManager.flush()
        }
    }

    /**
     * The rendered document, straight from the engine:
     * `document.documentElement.outerHTML` through `evaluateJavascript`,
     * which answers with a JSON-encoded string (or the literal `null`
     * while a navigation has the document torn down) — decoded, and null
     * on any engine refusal. A WebView mid-navigation answers null; the
     * poll loop simply tries again after the next finish.
     */
    private suspend fun WebView.extractHtml(): String? =
        suspendCancellableCoroutine { continuation ->
            runCatching {
                evaluateJavascript(EXTRACT_HTML_SCRIPT) { value ->
                    val html =
                        value
                            ?.let { runCatching { json.decodeFromString<String?>(it) }.getOrNull() }
                    continuation.resume(html)
                }
            }.onFailure { continuation.resume(null) }
        }

    /**
     * The solver's [WebViewClient]: default navigation behavior (challenge
     * redirects stay in the view) plus a count of finished page loads and
     * when the latest finished, for the reload and settle heuristics.
     * Both the callbacks and the poll loop run on the main thread, so the
     * counters need no synchronization.
     */
    private class ChallengeClient : WebViewClient() {
        var finished: Int = 0
            private set

        var lastFinishedAt: Long = 0
            private set

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) {
            finished += 1
            lastFinishedAt = System.currentTimeMillis()
        }
    }

    private val cookieManager: CookieManager
        get() = CookieManager.getInstance()

    /** JSON decode for `evaluateJavascript` answers — quoted strings or `null`. */
    private val json = Json

    /**
     * The device's true default WebView User-Agent, read once and cached —
     * the identity every solver WebView wears and every clearance is bound
     * to (v1.2.4). `WebSettings.getDefaultUserAgent` needs no WebView
     * instance and is stable for a given device + WebView version, so the
     * cache never goes stale mid-launch. Failures fall back to the UA of a
     * throwaway WebView, and only if even that fails to null — which makes
     * the callers decline rather than guess an agent the cookies were
     * never earned under.
     */
    private fun defaultUserAgent(): String? {
        defaultUserAgentCached?.let { return it }
        val agent =
            runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
                ?: runCatching { WebView(context).settings.userAgentString }.getOrNull()
        defaultUserAgentCached = agent
        return agent
    }

    @Volatile
    private var defaultUserAgentCached: String? = null

    private companion object {
        /** The cookie that proves the challenge was passed. */
        const val CLEARANCE_COOKIE = "cf_clearance"

        /** The document-extraction script `evaluateJavascript` runs. */
        const val EXTRACT_HTML_SCRIPT = "(function(){return document.documentElement.outerHTML})()"

        /** Poll cadence — challenges settle in seconds; sub-second checks are noise. */
        const val POLL_INTERVAL_MS = 400L

        /** Local backstop for a solve, above the engine's SOLVE_TIMEOUT_MS. */
        const val SOLVE_DEADLINE_MS = 65_000L

        /** Local backstop for a document fetch, above FETCH_TIMEOUT_MS. */
        const val FETCH_DEADLINE_MS = 65_000L

        /**
         * The detached lane's shorter window — a cookie-carrying page
         * load settles in seconds; anything still interstitial by this
         * mark is a dead cookie escalating to the attached dialog, not a
         * slow page worth waiting on.
         */
        const val DETACHED_DEADLINE_MS = 15_000L

        /**
         * The settle beat after a page finishes before its document is
         * read — server-rendered HTML completes fast, but the challenge
         * shell swaps for content on its own navigation, and reading
         * mid-swap only ever sees the shell.
         */
        const val SETTLE_AFTER_FINISH_MS = 700L
    }
}
