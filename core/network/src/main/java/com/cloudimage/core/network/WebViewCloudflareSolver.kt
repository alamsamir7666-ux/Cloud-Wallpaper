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
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
                // The default agent is what an unset WebView reports, so this
                // reconstructs the pair a previous launch actually earned.
                CloudflareBypass(
                    cookieHeader = cookies,
                    userAgent = WebSettings.getDefaultUserAgent(context),
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

    /**
     * A WebView configured to look and behave like the browser the zone
     * wants to see: JavaScript on (the challenge IS JavaScript — no JS,
     * no clearance), DOM storage on (Turnstile reads it like any real
     * page), network images off (a solve never needs thumbnails), and the
     * User-Agent left at the default it must stay bound to.
     */
    private fun createWebView(client: ChallengeClient): WebView {
        val cookies = cookieManager
        cookies.setAcceptCookie(true)
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = true
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
                    userAgent = webView.settings.userAgentString,
                )
            }
        }
        return null
    }

    /**
     * The solver's [WebViewClient]: default navigation behavior (challenge
     * redirects stay in the view) plus a count of finished page loads for
     * the reload heuristic. Both the callbacks and the poll loop run on
     * the main thread, so the counter needs no synchronization.
     */
    private class ChallengeClient : WebViewClient() {
        var finished: Int = 0
            private set

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) {
            finished += 1
        }
    }

    private val cookieManager: CookieManager
        get() = CookieManager.getInstance()

    private companion object {
        /** The cookie that proves the challenge was passed. */
        const val CLEARANCE_COOKIE = "cf_clearance"

        /** Poll cadence — challenges settle in seconds; sub-second checks are noise. */
        const val POLL_INTERVAL_MS = 400L

        /** Local backstop, above the engine's SOLVE_TIMEOUT_MS. */
        const val SOLVE_DEADLINE_MS = 25_000L

        /**
         * How long a finished page may sit without a clearance before the
         * one reload fires — long enough for a healthy challenge's own
         * second navigation, short enough to leave the reload time to work
         * inside the deadline.
         */
        const val RELOAD_AFTER_MS = 6_000L
    }
}
