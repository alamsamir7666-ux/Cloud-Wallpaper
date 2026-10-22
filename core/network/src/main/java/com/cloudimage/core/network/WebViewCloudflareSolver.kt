package com.cloudimage.core.network

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
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
 * v1.2.11: the wallpapersden.com lesson. That zone's bot management
 * challenges even the engine — an INTERACTIVE Turnstile checkbox, not
 * an auto-settling shell — so the user's part in the bargain became the
 * whole game, and the app played it badly on three counts, all fixed
 * here. First, the dialog was unexplained: a full-screen Cloudflare page
 * with a checkbox the user had no idea they should tap, so the challenge
 * just sat there; the visible dialogs now carry a slim header strip that
 * says what is happening and what to do, and the clearance it earns is
 * remembered, so the ask is once per cookie lifetime. Second, the dialog
 * was inescapable: a stuck challenge trapped the user for a full minute
 * with no way out; the visible dialogs are now back-cancelable, and a
 * dismissal fails that rung within one poll beat. Third, the ladder
 * marched the user through the SAME page twice: a solve that never
 * settled (60 s) escalated into a second visible fetch dialog (65 s)
 * showing the very challenge that just refused to settle — over two
 * minutes of Groundhog Day before the honest failure. A zone whose
 * attached attempt just failed now gets one detached best-effort trip
 * for the fetch rung instead, and the error surfaces a minute sooner.
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
                    // the detached WebView is the fallback, not a null. A
                    // failure HERE means the user watched a dialog and it
                    // didn't settle — remembered, so the fetch rung below
                    // does not make them watch the same page twice.
                    //
                    // v1.2.12: an attached trip that RAN and answered null
                    // settled on something a hidden retry cannot clear — the
                    // user backed out, the zone hard-blocked the engine, or
                    // the page was content with no clearance to earn (the
                    // fetch rung's own quiet trip serves that shape). The old
                    // solveDetached fallback here spun a hidden WebView for
                    // the rest of the solve window anyway — up to a minute of
                    // dead air the user could neither see nor win, which is
                    // exactly how the v1.2.11 field report read: back out of
                    // the dialog, the app goes silent, then the 403. Only an
                    // attached trip that died with an EXCEPTION (the activity
                    // finished under the dialog) keeps the detached
                    // best-effort: that was no honest failure, the window
                    // simply vanished mid-settle.
                    val attached = runCatching { solveAttached(activity, url, host) }
                    attached.getOrNull()?.let { return@withContext it }
                    recordAttachedFailure(host)
                    if (attached.exceptionOrNull() != null) solveDetached(url, host) else null
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
                // a hidden WebView — no dialog, nothing the user sees. A
                // hidden trip that lands on the interstitial instead
                // proves the cookies dead: wipe them (the next solve
                // deserves a clean slate) and escalate to the attached
                // dialog, where the challenge gets the real viewport it
                // needs — the one visible dialog per cookie lifetime.
                //
                // v1.2.7 sharpens "hidden": when a foreground window
                // exists the trip runs in an INVISIBLY-ATTACHED WebView —
                // a full-screen dialog whose window alpha is zero. The
                // page still reads as attached and visible (a real
                // viewport, `document.visibilityState === "visible"`),
                // which is exactly what a page's own post-load widgets —
                // WallpaperFlare's Turnstile, in the wild — need to run
                // their checks, while the user still sees nothing at all.
                // Only when no window exists does the trip fall back to
                // the truly detached WebView.
                //
                // v1.2.11: an attached attempt that failed RECENTLY for
                // this zone means the user just watched this challenge
                // not settle — escalating straight back into another
                // visible dialog is the same page for another minute.
                // One quiet detached trip instead, then the honest null.
                if (hasClearanceFor(host)) {
                    fetchQuiet(url, host)?.let { return@withContext it }
                    clearCookiesFor(host)
                }
                val activity =
                    foregroundActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
                return@withContext if (activity == null || recentlyFailedAttached(host)) {
                    fetchDetached(url, host)
                } else {
                    val page = runCatching { fetchAttached(activity, url, host) }.getOrNull()
                    if (page == null) {
                        recordAttachedFailure(host)
                        fetchDetached(url, host)
                    } else {
                        page
                    }
                }
            }
        }

    /**
     * The POST lane's engine rung (v1.3): one script trip through the
     * same lanes and the same discipline as [fetch]. [WebViewPage] is
     * not the shape here — the script's parked answer string IS the
     * exchange — so the lanes return it directly, and the caller
     * degrades to its fallback response on null exactly as the fetch
     * rung's callers do.
     *
     * The page the script runs in must be SAME-ORIGIN with the request
     * the script issues (the caller's contract); loading it runs the
     * full challenge discipline first, so a script never executes in a
     * challenge shell, and the cookie jar a settled page leaves behind
     * is the same one the fetch lanes leave.
     */
    override suspend fun evaluateOnPage(
        url: String,
        js: String,
    ): String? =
        withContext(Dispatchers.Main) {
            val host = url.toHttpUrlOrNull()?.host
            if (host == null) {
                null
            } else {
                // The fetch lanes' exact escalation: a live clearance
                // rides the quiet trip (invisible when a window exists,
                // detached otherwise); a quiet trip that lands on the
                // interstitial proves the cookie dead — wipe it, then
                // escalate to the visible dialog where a challenge gets
                // the real viewport, unless one just failed for this zone
                // (then one detached best-effort, then the honest null).
                if (hasClearanceFor(host)) {
                    evaluateQuiet(url, host, js)?.let { return@withContext it }
                    clearCookiesFor(host)
                }
                val activity =
                    foregroundActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
                return@withContext if (activity == null || recentlyFailedAttached(host)) {
                    evaluateDetached(url, host, js)
                } else {
                    val answer =
                        runCatching { evaluateInDialog(activity, url, host, js, visible = true) }
                            .getOrNull()
                    if (answer == null) {
                        recordAttachedFailure(host)
                        evaluateDetached(url, host, js)
                    } else {
                        answer
                    }
                }
            }
        }

    /**
     * The quiet-only script trip (v1.3.4): [evaluateOnPage]'s sibling for
     * hosts that are NOT Cloudflare zones — no clearance to check, no
     * interstitial escalation, no visible dialog. The page either
     * settles as content and the script answers, or the trip fails and
     * the caller degrades to its typed refusal.
     *
     * The lane is [evaluateQuiet] verbatim: an invisibly-attached dialog
     * when a live window exists (the v1.2.7 lane — a real viewport
     * without real pixels), the detached trip otherwise. A content page
     * has no challenge to settle, so the invisible lane's fail-fast
     * interstitial rule never fires for the hosts this serves; the one
     * interstitial-shaped document such a host can produce — its own
     * block page — lacks every Cloudflare marker the settle heuristics
     * read, settles as the content it is, and hands the script an
     * origin whose fetch then answers the honest refusal status.
     */
    override suspend fun evaluateOnPageQuiet(
        url: String,
        js: String,
    ): String? =
        withContext(Dispatchers.Main) {
            val host = url.toHttpUrlOrNull()?.host
            if (host == null) {
                null
            } else {
                evaluateQuiet(url, host, js)
            }
        }

    /**
     * The script trip's quiet lane: invisibly attached when a live window
     * exists, detached otherwise — [fetchQuiet]'s exact shape, fail-fast
     * on the interstitial so a dead cookie escalates to the visible
     * dialog instead of scripting against Cloudflare's shell.
     */
    private suspend fun evaluateQuiet(
        url: String,
        host: String,
        js: String,
    ): String? {
        val activity = foregroundActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
        return if (activity == null) {
            evaluateDetached(url, host, js)
        } else {
            runCatching { evaluateInDialog(activity, url, host, js, visible = false) }.getOrNull()
                ?: evaluateDetached(url, host, js)
        }
    }

    /**
     * The quiet document fetch (v1.2.6's invisible lane): an invisibly-
     * attached WebView when a live window exists, else a detached one.
     * Both run the fetch discipline — [DEADLINE][fetchDetached], fail-fast
     * on the interstitial — so a dead cookie escalates to the visible
     * dialog exactly as v1.2.6's detached trip did.
     */
    private suspend fun fetchQuiet(
        url: String,
        host: String,
    ): WebViewPage? {
        val activity = foregroundActivity()?.takeUnless { it.isFinishing || it.isDestroyed }
        return if (activity == null) {
            fetchDetached(url, host)
        } else {
            runCatching { fetchInvisible(activity, url, host) }.getOrNull()
                ?: fetchDetached(url, host)
        }
    }

    /** Whether the system jar still holds a clearance for [host]. */
    private fun hasClearanceFor(host: String): Boolean {
        val cookies = runCatching { cookieManager.getCookie("https://$host") }.getOrNull()
        return cookies != null && CLEARANCE_COOKIE in cookies
    }

    /**
     * Marks that a VISIBLE challenge trip for [host]'s zone just failed —
     * the user watched the dialog and the challenge did not settle
     * (timeout, rejection, or their own back-cancel). Keyed by zone, like
     * every Cloudflare state since v1.2.11: the site and its image CDN
     * share one zone, and one watched failure is one too many for both.
     */
    private fun recordAttachedFailure(host: String) {
        attachedFailureAt[cloudflareZoneOf(host)] = System.currentTimeMillis()
    }

    /**
     * Whether a visible trip failed recently for [host]'s zone — the
     * signal that suppresses the fetch rung's second dialog. The window
     * outlasts one ladder walk (solve fail → fetch attempt) comfortably
     * while leaving a manual Retry a few seconds later its fresh, honest
     * walk — the user who wants to try again gets the dialog again.
     */
    private fun recentlyFailedAttached(host: String): Boolean {
        val failedAt = attachedFailureAt[cloudflareZoneOf(host)] ?: return false
        return System.currentTimeMillis() - failedAt < ATTACHED_FAILURE_COOLDOWN_MS
    }

    /**
     * The foreground path: the challenge runs in a full-screen borderless
     * dialog over the resumed activity. The user briefly sees Cloudflare's
     * own "Just a moment…" page — the honest UI for what is happening —
     * and can complete an interactive challenge with a tap if one appears.
     * Dismissal in `finally` guarantees the dialog never outlives the
     * solve, whatever its outcome.
     *
     * v1.2.11: the dialog is no longer a riddle. A slim header strip
     * names the host and tells the user to tap the checkbox when one
     * appears — wallpapersden.com's zone challenges the ENGINE with an
     * interactive Turnstile, and a checkbox nobody knows to tap is a
     * source that never loads. The dialog is also back-cancelable now:
     * a stuck challenge is an exit the user deserves, not a minute-long
     * trap, and a dismissal fails the rung within one poll beat.
     */
    private suspend fun solveAttached(
        activity: Activity,
        url: String,
        host: String,
    ): CloudflareBypass? {
        val client = ChallengeClient()
        val webView = createWebView(client)
        val dialog = challengeDialog(activity, webView, host, visible = true)
        return try {
            dialog.show()
            webView.loadUrl(url)
            waitForClearance(
                client,
                webView,
                host,
                url,
                dismissed = { dialog.isShowing },
                allowChallengeRefresh = true,
            )
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
    ): WebViewPage? = fetchInDialog(activity, url, host, visible = true)

    /**
     * The invisible document fetch (v1.2.7): the same full-screen dialog,
     * but with the window's alpha at zero — attached to a real window and
     * laid out at real viewport size (so the page's own post-load widgets,
     * WallpaperFlare's Turnstile among them, read a genuinely visible
     * document), while compositing nothing the user can see. A trip here
     * still fail-fasts on the interstitial like the detached lane: a
     * challenge the user cannot see is a challenge the user cannot solve,
     * so it escalates to the visible dialog instead of parking in the
     * dark for a minute.
     */
    private suspend fun fetchInvisible(
        activity: Activity,
        url: String,
        host: String,
    ): WebViewPage? = fetchInDialog(activity, url, host, visible = false)

    /**
     * One document-fetch trip in a dialog window over [activity]: visible
     * when [visible], fully transparent otherwise. The dialog discipline —
     * dismissed in `finally`, WebView torn down with it — is identical for
     * both; only the window's compositing (and, with it, the interstitial
     * policy) differs: a VISIBLE dialog is where a challenge settles in
     * front of the user (fail-fast off — the page that arrives after the
     * settle is the fetch's answer, and v1.2.11 gives it the same explainer
     * strip and back-cancel as the solve dialog), while an INVISIBLE one
     * the user cannot interact with treats the interstitial as a dead end
     * and escalates (fail-fast on).
     */
    private suspend fun fetchInDialog(
        activity: Activity,
        url: String,
        host: String,
        visible: Boolean,
    ): WebViewPage? {
        val client = ChallengeClient()
        val webView = createWebView(client)
        val dialog = challengeDialog(activity, webView, host, visible)
        return try {
            dialog.show()
            webView.loadUrl(url)
            awaitDocument(
                client,
                webView,
                host,
                url,
                FETCH_DEADLINE_MS,
                failFastOnInterstitial = !visible,
                dismissed = { dialog.isShowing },
            )
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
     * One script trip in a dialog window over [activity] — the evaluate
     * sibling of [fetchInDialog], with the identical dialog discipline
     * (visible = the challenge the user can settle; invisible = the
     * zero-alpha window a script quietly rides) and the identical
     * teardown. The document settles FIRST (a script never runs in a
     * challenge shell), then [runPageScript] kicks the caller's script
     * and polls for the answer it parks on the page.
     */
    private suspend fun evaluateInDialog(
        activity: Activity,
        url: String,
        host: String,
        js: String,
        visible: Boolean,
    ): String? {
        val client = ChallengeClient()
        val webView = createWebView(client)
        val dialog = challengeDialog(activity, webView, host, visible)
        return try {
            dialog.show()
            webView.loadUrl(url)
            val settled =
                awaitDocument(
                    client,
                    webView,
                    host,
                    url,
                    FETCH_DEADLINE_MS,
                    failFastOnInterstitial = !visible,
                    dismissed = { dialog.isShowing },
                )
            if (settled == null) null else runPageScript(webView, js)
        } finally {
            runCatching { dialog.dismiss() }
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    /** The background script trip — best effort, like the background fetch. */
    private suspend fun evaluateDetached(
        url: String,
        host: String,
        js: String,
    ): String? {
        val client = ChallengeClient()
        val webView = createWebView(client)
        return try {
            webView.loadUrl(url)
            val settled =
                awaitDocument(
                    client,
                    webView,
                    host,
                    url,
                    DETACHED_DEADLINE_MS,
                    failFastOnInterstitial = true,
                )
            if (settled == null) null else runPageScript(webView, js)
        } finally {
            runCatching { webView.stopLoading() }
            runCatching { webView.destroy() }
        }
    }

    /**
     * The dialog a challenge runs in (v1.2.11): full-screen and borderless
     * as always, but VISIBLE trips now stack a slim explainer strip above
     * the WebView — the host under check and the one instruction that
     * matters (tap the checkbox; the app remembers the pass) — and are
     * cancelable with the back key, so a challenge that will not settle
     * is a dead end the user can walk out of instead of a trap. Invisible
     * trips keep the plain zero-alpha window and stay non-cancelable:
     * nothing is on screen, so no back-press is ever aimed at them.
     */
    private fun challengeDialog(
        activity: Activity,
        webView: WebView,
        host: String,
        visible: Boolean,
    ): Dialog =
        Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCancelable(visible)
            setCanceledOnTouchOutside(false)
            setContentView(
                if (visible) challengeContent(activity, webView, host) else webView,
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
                if (!visible) {
                    // Zero alpha, zero dim: the window attaches and lays
                    // out — the page runs as a real, visible document —
                    // while the compositor draws nothing over the app.
                    attributes =
                        attributes.apply {
                            alpha = 0f
                            dimAmount = 0f
                        }
                }
            }
        }

    /**
     * The visible trip's content: the explainer strip over the challenge
     * page itself. Densities are honored (px paddings scaled by the
     * display density), the strip's dark surface reads as app chrome
     * above whatever Cloudflare's page looks like, and the WebView takes
     * every remaining pixel so the challenge loses nothing it had.
     */
    private fun challengeContent(
        activity: Context,
        webView: WebView,
        host: String,
    ): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(HINT_BACKGROUND)
            val density = resources.displayMetrics.density

            fun dp(value: Int): Int = (value * density).toInt()
            addView(
                TextView(activity).apply {
                    text = HINT_FORMAT.format(host)
                    textSize = HINT_TEXT_SIZE_SP
                    setTextColor(HINT_TEXT_COLOR)
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setPadding(dp(20), dp(14), dp(20), dp(14))
                },
            )
            addView(
                webView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
        }

    /**
     * A WebView configured to look and behave like the browser the zone
     * wants to see: JavaScript on (the challenge IS JavaScript — no JS,
     * no clearance) and DOM storage on (Turnstile reads it like any real
     * page). Nothing else is touched but one surgical exception (v1.2.17):
     * the User-Agent is the device default with its two embedded-only
     * markers — the parenthetical's `; wv` and the fossil `Version/4.0`
     * WebKit token — removed via [chromeCoherentUserAgent], leaving the
     * string exactly what the phone's own Chrome sends: same engine,
     * same true Chrome version, same device profile.
     *
     * Why this is not the reverted v1.2.3 experiment: that one CLAIMED a
     * Chrome version the engine was not, and CloudStream's warning —
     * "setting user agent will make cloudflare break" — is about exactly
     * that UA-versus-engine lie. Here every number stays the engine's own
     * truth; only the two tokens that mark the browser as embedded go,
     * because an aggressive zone (wallpapersden.com, in the wild, on two
     * different devices) can key its WAF rules and Browser Integrity
     * checks on them while serving the identical engine presenting as
     * Chrome — verified through a clean-browser vantage that gets the
     * real content. Network images still load freely (CloudStream removed
     * its own image blocking because suppressing the challenge page's
     * resources broke the captcha).
     */
    private fun createWebView(client: ChallengeClient): WebView {
        val cookies = cookieManager
        cookies.setAcceptCookie(true)
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // The identity every clearance this trip earns is bound to —
            // the Chrome-coherent form of the device's default agent.
            userAgentString = defaultUserAgent()
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
     *
     * v1.2.13 adds the one late refresh (attached trips only —
     * [allowChallengeRefresh]): a challenge still interstitial half a
     * minute after its page loaded is either untapped or re-looping, and
     * a re-looping Turnstile instance refuses every tap forever while a
     * FRESH one often settles on the next tap, so the trip reloads the
     * page once for a new instance instead of staring at the same
     * checkbox until the deadline. See [CHALLENGE_REFRESH_AFTER_MS] for
     * the timing's safety argument against v1.2.4's never-reload rule.
     */
    private suspend fun waitForClearance(
        client: ChallengeClient,
        webView: WebView,
        host: String,
        url: String,
        dismissed: () -> Boolean = { false },
        allowChallengeRefresh: Boolean = false,
    ): CloudflareBypass? {
        // Read once: the identity this solve binds its cookies to. A device
        // that cannot report a default agent cannot earn an honest pair.
        val agent = defaultUserAgent() ?: return null
        val deadline = System.currentTimeMillis() + SOLVE_DEADLINE_MS
        var contentCheckedForFinish = -1

        // 0 while no refresh has fired — one per trip, never reset.
        var refreshedAt = 0L
        while (System.currentTimeMillis() < deadline) {
            if (dismissed()) {
                // v1.2.11: the user backed out of the challenge — this trip
                // earned nothing, and the jar keeps the same clean-slate
                // discipline a timed-out trip gets.
                clearCookiesFor(host)
                return null
            }
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
            //
            // v1.2.12 adds the hard-block exit above it: a page that settled
            // on "Attention Required!" is the zone refusing the ENGINE —
            // no cookie is coming and no tap exists to give. Without this
            // the dialog parked on the block page for the full window with
            // a hint strip promising a checkbox that never appears, and
            // the detached solve spun the same dead page out of sight.
            val finishedAt = client.lastFinishedAt
            val settled = finishedAt > 0 && System.currentTimeMillis() - finishedAt >= SETTLE_AFTER_FINISH_MS
            if (settled && client.finished != contentCheckedForFinish) {
                contentCheckedForFinish = client.finished
                val html = webView.extractHtml() ?: continue
                if (CloudflareChallenge.isHardBlockDocument(html)) {
                    clearCookiesFor(host)
                    return null
                }
                if (!CloudflareChallenge.isInterstitialDocument(html)) return null
            }
            // The one late refresh — v1.2.13, attached trips only. The
            // document is re-read right before the reload fires: a page
            // that quietly became CONTENT in place (a settle whose cookie
            // is still propagating, say) exits through the rung's own
            // early-return instead of being reset, so the refresh can
            // only ever hit a page that still reads as Cloudflare's shell.
            if (allowChallengeRefresh &&
                refreshedAt == 0L &&
                finishedAt > 0 &&
                System.currentTimeMillis() - finishedAt >= CHALLENGE_REFRESH_AFTER_MS
            ) {
                val current = webView.extractHtml() ?: continue
                if (!CloudflareChallenge.isInterstitialDocument(current)) return null
                refreshedAt = System.currentTimeMillis()
                runCatching { webView.reload() }
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
     * site content then holds the page open for its POST-LOAD JAVASCRIPT
     * (v1.2.7): pages increasingly assemble their payload after
     * `onPageFinished` — WallpaperFlare's download page runs a Turnstile
     * widget, POSTs its token, and only then writes the original image's
     * URL into the DOM — so the document is re-read the moment the page
     * goes QUIET (no subresource or interception activity for
     * [JS_QUIET_MS]), or at the [POST_LOAD_GRACE_MS] cap for pages whose
     * analytics never stop. Like the solve path there is no reload
     * (v1.2.4) — a page that parks simply rides the full deadline. A page
     * that never settles by the deadline is a failure — null, no partial
     * credit.
     *
     * [failFastOnInterstitial] is the detached/invisible lane's discipline
     * (v1.2.6): a hidden document cannot settle a challenge — that is
     * the v1.0.16 lesson — so a hidden trip that settles on the
     * interstitial returns null IMMEDIATELY instead of parking for a
     * minute, letting the caller escalate to the attached dialog. The
     * visible lane keeps waiting, because there the interstitial is a
     * challenge actively settling in front of the user.
     */
    private suspend fun awaitDocument(
        client: ChallengeClient,
        webView: WebView,
        host: String,
        url: String,
        deadlineMs: Long,
        failFastOnInterstitial: Boolean,
        dismissed: () -> Boolean = { false },
    ): WebViewPage? {
        val deadline = System.currentTimeMillis() + deadlineMs
        var extractedForFinish = -1
        while (System.currentTimeMillis() < deadline) {
            if (dismissed()) {
                // v1.2.11: the user backed out of the visible fetch dialog —
                // no document is coming from a window they closed.
                clearCookiesFor(host)
                return null
            }
            delay(POLL_INTERVAL_MS)
            val finishedAt = client.lastFinishedAt
            val settled = finishedAt > 0 && System.currentTimeMillis() - finishedAt >= SETTLE_AFTER_FINISH_MS
            if (settled && client.finished != extractedForFinish) {
                extractedForFinish = client.finished
                val html = webView.extractHtml() ?: continue
                if (CloudflareChallenge.isInterstitialDocument(html)) {
                    // v1.2.12: a settled HARD BLOCK is a dead end in every
                    // lane — the fail-fast discipline the hidden trips
                    // already had, extended to the visible one, so a dialog
                    // parked on "Attention Required!" exits immediately
                    // instead of outlasting its welcome with a hint that
                    // promises a checkbox the page will never show.
                    if (failFastOnInterstitial || CloudflareChallenge.isHardBlockDocument(html)) return null
                    continue
                }
                // Site content — but its JavaScript may still be writing the
                // parts this fetch exists to read. Hold the page open until
                // it goes quiet (or the grace cap, or a new navigation takes
                // over), then read the FINAL document.
                //
                // A page that embeds Cloudflare Turnstile (the wallpaperflare
                // download page, in the wild) gets the patient regime: its
                // token → POST → DOM-write chain runs 1–5 s after load, and
                // the POST itself may never touch the resource clock (it is
                // an XHR, and not every WebView version routes those through
                // the interception hook) — so a minimum hold rides along
                // that no quiet signal can cut short. Every other page —
                // the site's server-rendered listings, which carry no
                // widget at all — returns on the plain quiet window.
                val turnstilePage = TURNSTILE_MARKER.containsMatchIn(html)
                val graceCap = if (turnstilePage) TURNSTILE_GRACE_MS else POST_LOAD_GRACE_MS
                val quietWindow = if (turnstilePage) TURNSTILE_QUIET_MS else JS_QUIET_MS
                val graceDeadline =
                    minOf(System.currentTimeMillis() + graceCap, deadline)
                val minHoldUntil =
                    if (turnstilePage) {
                        minOf(System.currentTimeMillis() + TURNSTILE_MIN_HOLD_MS, graceDeadline)
                    } else {
                        0L
                    }
                var navigatedAgain = false
                while (System.currentTimeMillis() < graceDeadline) {
                    delay(POLL_INTERVAL_MS)
                    if (client.finished != extractedForFinish) {
                        navigatedAgain = true
                        break
                    }
                    if (System.currentTimeMillis() < minHoldUntil) continue
                    val quietFor =
                        if (client.lastResourceAt == 0L) {
                            Long.MAX_VALUE
                        } else {
                            System.currentTimeMillis() - client.lastResourceAt
                        }
                    if (quietFor >= quietWindow) break
                }
                if (navigatedAgain) continue
                val finalHtml = webView.extractHtml() ?: html
                runCatching { cookieManager.flush() }
                return WebViewPage(html = finalHtml, clearance = clearanceFor(host, url))
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
     * Runs the caller's [js] in the SETTLED page and waits for the answer
     * it parks on the page — the script contract [evaluateOnPage] offers:
     * the script starts an async operation (a fetch, typically), sets
     * `__ciScriptDone` the moment it has an answer and
     * `__ciScriptResult` to the answer string, and returns without
     * blocking (an `evaluateJavascript` call cannot await a Promise — its
     * callback would only ever see `[object Promise]`). This method
     * supplies the polling: the kick-off, then one cheap expression per
     * poll beat until the result lands or [SCRIPT_DEADLINE_MS] lapses.
     *
     * A script whose fetch outlives the deadline answers null — the
     * honest failure the caller degrades on, never a synthesized body.
     */
    private suspend fun runPageScript(
        webView: WebView,
        js: String,
    ): String? {
        runCatching { webView.evaluateJavascript(js, null) }.onFailure { return null }
        val deadline = System.currentTimeMillis() + SCRIPT_DEADLINE_MS
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val result = evaluateString(webView, SCRIPT_POLL_EXPRESSION)
            if (result != null) return result
        }
        return null
    }

    /**
     * One `evaluateJavascript` expression's answer, JSON-decoded — null
     * for the engine's literal `null` (the poll's "not yet"), null on
     * any engine refusal. [WebView.extractHtml]'s decode discipline,
     * factored out for the script lanes to share.
     */
    private suspend fun evaluateString(
        webView: WebView,
        expression: String,
    ): String? =
        suspendCancellableCoroutine { continuation ->
            runCatching {
                webView.evaluateJavascript(expression) { value ->
                    val answer =
                        value
                            ?.takeUnless { it == "null" }
                            ?.let { runCatching { json.decodeFromString<String?>(it) }.getOrNull() }
                    continuation.resume(answer)
                }
            }.onFailure { continuation.resume(null) }
        }

    /**
     * The solver's [WebViewClient]: default navigation behavior (challenge
     * redirects stay in the view) plus the page-load and resource-activity
     * clocks the settle heuristics read — when the latest page finished
     * and when any subresource last moved, the signal the post-load
     * JavaScript quiet window keys on. The callbacks run on the main
     * thread while [shouldInterceptRequest] runs on a background one, so
     * the timestamp writes are `@Volatile`.
     *
     * v1.2.7 adds one narrow interception: requests for
     * `r{N}.wallpaperflare.com` — the original-image host the site's own
     * download flow assigns into the DOM after its Turnstile settles —
     * answer with a stub instead of the multi-megabyte original. The
     * fetch lane only needs the URL, not the bytes (the app re-downloads
     * the file through its HTTP client right after), and letting a
     * hidden WebView pull full-resolution wallpapers on every tap would
     * double the user's bandwidth for nothing. Everything else passes
     * through untouched — `null` IS the default handling, the exact path
     * every non-intercepted request takes, so the challenge orchestration
     * (its scripts, iframes and XHRs, none of which live on that host)
     * behaves precisely as before.
     */
    private class ChallengeClient : WebViewClient() {
        var finished: Int = 0
            private set

        var lastFinishedAt: Long = 0
            private set

        /** When any subresource was last requested — 0 until one is. */
        @Volatile
        var lastResourceAt: Long = 0
            private set

        override fun onPageFinished(
            view: WebView,
            url: String,
        ) {
            finished += 1
            lastFinishedAt = System.currentTimeMillis()
        }

        override fun onLoadResource(
            view: WebView,
            url: String,
        ) {
            lastResourceAt = System.currentTimeMillis()
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            lastResourceAt = System.currentTimeMillis()
            val host = request.url.host ?: return null
            return if (ORIGINAL_IMAGE_HOST.containsMatchIn(host)) {
                WebResourceResponse("image/jpeg", null, ByteArrayInputStream(ByteArray(0)))
            } else {
                null
            }
        }
    }

    private val cookieManager: CookieManager
        get() = CookieManager.getInstance()

    /** JSON decode for `evaluateJavascript` answers — quoted strings or `null`. */
    private val json = Json

    /**
     * The Chrome-coherent form of the device's default WebView User-Agent,
     * read once and cached — the identity every solver WebView wears and
     * every clearance is bound to (v1.2.4 introduced the default-agent
     * binding; v1.2.17 cleans it via [chromeCoherentUserAgent], so the
     * (cookies, agent) pair always matches what a trip actually wore —
     * the WebView is configured with this same string in
     * [createWebView], the replay and the persisted warm start read it
     * back here). `WebSettings.getDefaultUserAgent` needs no WebView
     * instance and is stable for a given device + WebView version, so the
     * cache never goes stale mid-launch. Failures fall back to the UA of a
     * throwaway WebView, and only if even that fails to null — which makes
     * the callers decline rather than guess an agent the cookies were
     * never earned under.
     *
     * Warm-start note for upgrades: a clearance persisted by an older
     * version of the app was earned under the RAW default agent, so its
     * first replay under the cleaned form fails the (IP, User-Agent) bind
     * — the zone answers a challenge, the ladder re-solves under the
     * cleaned identity, and the state heals on its own. Only zones that
     * HAD a working clearance pay one extra solve, once.
     */
    private fun defaultUserAgent(): String? {
        defaultUserAgentCached?.let { return it }
        val agent =
            runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
                ?: runCatching { WebView(context).settings.userAgentString }.getOrNull()
        val challengeAgent = agent?.let(::chromeCoherentUserAgent)
        defaultUserAgentCached = challengeAgent
        return challengeAgent
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
         * Local backstop for a script's own execution inside a settled
         * page — the fetch of a same-origin request plus its body read
         * lands in a couple of beats on a warm engine; thirty covers the
         * slow tail without ever outliving the caller's outer window.
         */
        const val SCRIPT_DEADLINE_MS = 30_000L

        /**
         * The script-answer poll — null until the script parks its
         * answer (the contract [runPageScript] and its scripts share):
         * done-but-no-string reads as null too, so a misbehaving script
         * rides to the deadline and then the honest failure, never a
         * partial answer.
         */
        const val SCRIPT_POLL_EXPRESSION =
            "window.__ciScriptDone === true ? (typeof window.__ciScriptResult === 'string' ? window.__ciScriptResult : null) : null"

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

        /**
         * How long a challenge page may sit interstitial before the one
         * refresh fires (v1.2.13). The v1.2.4 rule — never reload, a
         * reload mid-settle resets the challenge, fatal mid-tap — stays
         * sacred for everything INSIDE this window, and the window is
         * sized so that window's cases are done: a managed challenge
         * settles in 3–8 s, a human reads the hint strip and taps within
         * a few seconds more, and anything still interstitial at thirty
         * is an interactive challenge nobody is tapping or a re-looping
         * widget whose instance is poisoned — a FRESH instance often
         * settles on the very next tap (CloudStream's solver reloads for
         * exactly this reason), which one bounded reload buys. The rare
         * tap that lands inside the last second before the refresh pays
         * one reset, and the hint strip now says to tap again.
         */
        const val CHALLENGE_REFRESH_AFTER_MS = 30_000L

        /**
         * How still a page must be before its post-load JavaScript is
         * judged finished — no subresource or interception activity for
         * this long means the DOM the fetch reads is the settled one
         * (v1.2.7). Server-rendered listings settle here within a beat of
         * their own load.
         */
        const val JS_QUIET_MS = 1_200L

        /**
         * The cap on the quiet wait — pages whose analytics keep the
         * resource clock warm forever still return at this mark, with
         * whatever DOM they have (v1.2.7). Long enough for slow post-load
         * scripts, short enough that an ordinary fetch through the engine
         * never feels it.
         */
        const val POST_LOAD_GRACE_MS = 6_000L

        /**
         * The widget marker that switches a fetch into the patient regime —
         * the Turnstile API script or the `cf-turnstile` widget element.
         * WallpaperFlare's download page embeds one to gate its original-
         * image URLs behind a token exchange; its listings carry no widget
         * at all, so the patient regime never taxes the feeds.
         */
        val TURNSTILE_MARKER =
            Regex("""challenges\.cloudflare\.com/turnstile|class=["'][^"']*cf-turnstile""")

        /**
         * Quiet window under the patient regime — wider, because the
         * widget's token POST is an XHR the resource clock may never see
         * and the DOM write that matters follows it (v1.2.7).
         */
        const val TURNSTILE_QUIET_MS = 2_000L

        /**
         * How long a widget-bearing page is held open unconditionally —
         * the token flow typically completes within 1–3 s of the page
         * finishing, and this floor covers the invisible-XHR gap no quiet
         * signal can (v1.2.7).
         */
        const val TURNSTILE_MIN_HOLD_MS = 3_000L

        /** The patient regime's cap — a slow Turnstile still returns by here. */
        const val TURNSTILE_GRACE_MS = 8_000L

        /**
         * The original-image CDN of the one site whose download flow
         * assigns full-resolution files into the DOM after page load —
         * intercepted (stubbed) so a hidden fetch never pays for bytes
         * the HTTP client re-downloads seconds later (v1.2.7).
         */
        val ORIGINAL_IMAGE_HOST = Regex("""^r\d+\.wallpaperflare\.com$""")

        /**
         * How long a failed VISIBLE trip suppresses the next one — long
         * enough to cover the ladder walk that watched the failure (the
         * fetch rung immediately after the solve), short enough that a
         * deliberate Retry a little later earns its fresh dialog (v1.2.11).
         */
        const val ATTACHED_FAILURE_COOLDOWN_MS = 30_000L

        /** The explainer strip's surface — dark app chrome over the page. */
        const val HINT_BACKGROUND = 0xFF101418.toInt()

        /** The strip's copy color — quiet white, not headline white. */
        const val HINT_TEXT_COLOR = 0xFFE6EAF0.toInt()

        /** The strip's text size — small print for a one-line instruction. */
        const val HINT_TEXT_SIZE_SP = 13f

        /**
         * The strip's copy: what is happening, the ONE thing to do, the
         * one re-loop instruction (v1.2.13 — a check that restarts is a
         * poisoned instance the trip is about to refresh, not a lost
         * cause), and the promise that it is not asked often — the four
         * facts a full-screen Cloudflare page never tells the user itself.
         */
        const val HINT_FORMAT =
            "%s is checking your browser. Tap the checkbox if the page shows one — " +
                "if the check restarts, tap it again. Done once, the app remembers it."
    }

    /** When a visible challenge trip last failed, per zone — see [recentlyFailedAttached]. */
    private val attachedFailureAt = ConcurrentHashMap<String, Long>()

    /**
     * The user asked to try again — the Retry button's signal that the
     * next attempt is human-paced, not another row of the stampede the
     * cooldown exists to absorb. The record of visible failures is what
     * suppresses the fetch rung's dialog; forgetting it hands the next
     * walk its fresh, honest visible attempt (v1.2.12).
     */
    override fun resetFailureMemory() {
        attachedFailureAt.clear()
    }

    /**
     * The identity [createWebView] pins and [persistedStateFor]
     * reconstructs — exposed for the in-app browser (v1.2.19) so a
     * hand-earned clearance binds to the same (cookies, agent) pair every
     * automated rung replays under. See the interface KDoc.
     */
    override fun challengeUserAgent(): String? = defaultUserAgent()
}
