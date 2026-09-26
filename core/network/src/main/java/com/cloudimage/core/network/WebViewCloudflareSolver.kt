package com.cloudimage.core.network

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The production [CloudflareSolver]: a headless WebView that loads the
 * challenged URL and lets the challenge's own JavaScript settle it, the
 * way a real browser does — no fingerprint spoofing, no captcha guessing.
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
 * Everything runs on the main dispatcher: WebView demands a Looper thread,
 * and the main thread is the one that always has one. The poll loop below
 * is `delay`-based, so the caller's timeout (see [CloudflareBypasser])
 * cancels it cleanly and the `finally` tears the WebView down.
 */
@SuppressLint("SetJavaScriptEnabled")
internal class WebViewCloudflareSolver(
    private val context: Context,
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
                val webView = createWebView()
                try {
                    webView.loadUrl(url)
                    waitForClearance(webView, host, url)
                } finally {
                    runCatching { webView.stopLoading() }
                    runCatching { webView.destroy() }
                }
            }
        }

    /**
     * A WebView configured to look and behave like the browser the zone
     * wants to see: JavaScript on (the challenge IS JavaScript — no JS,
     * no clearance), DOM storage on (Turnstile reads it like any real
     * page), network images off (a headless solve never needs thumbnails),
     * and the User-Agent left at the default it must stay bound to.
     */
    private fun createWebView(): WebView {
        val cookies = cookieManager
        cookies.setAcceptCookie(true)
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = true
        }
        cookies.setAcceptThirdPartyCookies(webView, true)
        // A client keeps the challenge's redirects inside this view.
        webView.webViewClient = WebViewClient()
        return webView
    }

    /**
     * Waits for the clearance cookie to land. Managed challenges settle
     * between navigations — the script runs, sets `cf_clearance`, and may
     * reload the page — so a short poll over the cookie jar catches every
     * shape of it without parsing page state. The hard deadline is a
     * backstop above the engine's own timeout; whichever fires first, the
     * `finally` above still tears the WebView down.
     */
    private suspend fun waitForClearance(
        webView: WebView,
        host: String,
        url: String,
    ): CloudflareBypass? {
        val deadline = System.currentTimeMillis() + SOLVE_DEADLINE_MS
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
                    userAgent = webView.settings.userAgentString,
                )
            }
        }
        return null
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
    }
}
