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
 * the default WebView User-Agent carries the `wv` token — a beacon
 * Cloudflare's scoring reads as "embedded browser", and Turnstile
 * challenges routinely refuse to settle for it — so every WebView this
 * solver creates now reports a clean mobile-Chrome agent
 * ([SOLVER_USER_AGENT]), and persisted clearances are reconstructed
 * under the same constant so the pair stays bound. Second, some zones
 * bind their clearances to more than (IP, User-Agent) — the replay's
 * TLS fingerprint is checked too, and no cookie jar can fix an OkHttp
 * handshake — so [fetch] was added: the WebView loads the page as a
 * browser, settles whatever it meets, and hands back the DOCUMENT
 * itself, the one answer no fingerprint check can tell apart from a
 * user tapping a link.
 *
 * A stalled challenge gets exactly one reload: managed challenges
 * occasionally park on a finished page instead of navigating, and a
 * second load coaxes the orchestration to run again.
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
                // with — v1.2.3's fixed identity, not the device's default,
                // so the (cookies, agent) pair matches what a solve or
                // fetch actually produced.
                CloudflareBypass(
                    cookieHeader = cookies,
                    userAgent = SOLVER_USER_AGENT,
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
            awaitDocument(client, webView, host, url)
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
            awaitDocument(client, webView, host, url)
        } finally {
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    /**
     * A WebView configured to look and behave like the browser the zone
     * wants to see: JavaScript on (the challenge IS JavaScript — no JS,
     * no clearance), DOM storage on (Turnstile reads it like any real
     * page), network images off (a solve never needs thumbnails), and a
     * CLEAN mobile-Chrome User-Agent — v1.2.3: the default WebView agent
     * advertises the `wv` token, which Cloudflare's scoring reads as an
     * embedded browser and Turnstile regularly refuses to settle for.
     * The fixed agent is also what the clearance replay and the warm
     * start reconstruct, so the pair stays bound.
     */
    private fun createWebView(client: ChallengeClient): WebView {
        val cookies = cookieManager
        cookies.setAcceptCookie(true)
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = true
            userAgentString = SOLVER_USER_AGENT
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
     * shape of it without parsing page state. A page that finished yet
     * produced no clearance after [RELOAD_AFTER_MS] is reloaded once: a
     * parked orchestration usually runs properly on the second load. The
     * hard deadline is a backstop above the engine's own timeout;
     * whichever fires first, the caller's `finally` still tears the
     * WebView down.
     */
    private suspend fun waitForClearance(
        client: ChallengeClient,
        webView: WebView,
        host: String,
        url: String,
    ): CloudflareBypass? {
        val deadline = System.currentTimeMillis() + SOLVE_DEADLINE_MS
        var firstFinishAt = 0L
        var reloaded = false
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            // The reload heuristic runs before the cookie lookup on purpose:
            // a parked page can leave the jar empty (a null `getCookie` and
            // the `continue` it triggers), and the reload must still fire.
            if (!reloaded && client.finished > 0) {
                if (firstFinishAt == 0L) {
                    firstFinishAt = System.currentTimeMillis()
                }
                if (System.currentTimeMillis() - firstFinishAt >= RELOAD_AFTER_MS) {
                    reloaded = true
                    runCatching { webView.reload() }
                }
            }
            val cookies =
                cookieManager.getCookie(url)
                    ?: cookieManager.getCookie("https://$host")
                    ?: continue
            if (CLEARANCE_COOKIE in cookies) {
                // Persist now: a later cold start reads this back as a warm one.
                runCatching { cookieManager.flush() }
                return CloudflareBypass(
                    cookieHeader = cookies,
                    userAgent = SOLVER_USER_AGENT,
                )
            }
        }
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
     * site content wins, carrying whatever clearance the trip earned; the
     * one-reload heuristic from [waitForClearance] rides along for pages
     * that park instead of navigating. A page that never settles by the
     * deadline is a failure — null, no partial credit.
     */
    private suspend fun awaitDocument(
        client: ChallengeClient,
        webView: WebView,
        host: String,
        url: String,
    ): WebViewPage? {
        val deadline = System.currentTimeMillis() + FETCH_DEADLINE_MS
        var firstFinishAt = 0L
        var reloaded = false
        var extractedForFinish = -1
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            if (!reloaded && client.finished > 0) {
                if (firstFinishAt == 0L) {
                    firstFinishAt = System.currentTimeMillis()
                }
                if (System.currentTimeMillis() - firstFinishAt >= RELOAD_AFTER_MS) {
                    reloaded = true
                    runCatching { webView.reload() }
                }
            }
            val finishedAt = client.lastFinishedAt
            val settled = finishedAt > 0 && System.currentTimeMillis() - finishedAt >= SETTLE_AFTER_FINISH_MS
            if (settled && client.finished != extractedForFinish) {
                extractedForFinish = client.finished
                val html = webView.extractHtml() ?: continue
                if (!CloudflareChallenge.isInterstitialDocument(html)) {
                    runCatching { cookieManager.flush() }
                    return WebViewPage(html = html, clearance = clearanceFor(host, url))
                }
            }
        }
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
        return CloudflareBypass(cookieHeader = cookies, userAgent = SOLVER_USER_AGENT)
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

    private companion object {
        /** The cookie that proves the challenge was passed. */
        const val CLEARANCE_COOKIE = "cf_clearance"

        /**
         * The identity every solver WebView wears — a plain mobile-Chrome
         * agent with NO `wv` token (v1.2.3), fixed for the app's lifetime
         * so a clearance, its replay, and a later warm start all agree.
         * Must stay coherent with what the site sees from a real browser.
         */
        const val SOLVER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** The document-extraction script `evaluateJavascript` runs. */
        const val EXTRACT_HTML_SCRIPT = "(function(){return document.documentElement.outerHTML})()"

        /** Poll cadence — challenges settle in seconds; sub-second checks are noise. */
        const val POLL_INTERVAL_MS = 400L

        /** Local backstop for a solve, above the engine's SOLVE_TIMEOUT_MS. */
        const val SOLVE_DEADLINE_MS = 25_000L

        /** Local backstop for a document fetch, above FETCH_TIMEOUT_MS. */
        const val FETCH_DEADLINE_MS = 35_000L

        /**
         * How long a finished page may sit without a clearance before the
         * one reload fires — long enough for a healthy challenge's own
         * second navigation, short enough to leave the reload time to work
         * inside the deadline.
         */
        const val RELOAD_AFTER_MS = 6_000L

        /**
         * The settle beat after a page finishes before its document is
         * read — server-rendered HTML completes fast, but the challenge
         * shell swaps for content on its own navigation, and reading
         * mid-swap only ever sees the shell.
         */
        const val SETTLE_AFTER_FINISH_MS = 700L
    }
}
