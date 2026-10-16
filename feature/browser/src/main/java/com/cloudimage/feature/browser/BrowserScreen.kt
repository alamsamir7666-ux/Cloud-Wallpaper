package com.cloudimage.feature.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import kotlinx.coroutines.delay

/** The cookie that proves the verification was passed. */
private const val CLEARANCE_COOKIE = "cf_clearance"

/** How often the status strip re-reads the cookie jar (seconds, not beats). */
private const val CLEARANCE_POLL_MS = 1_000L

/**
 * The in-app browser (v1.2.19) — the escape hatch for a source whose
 * Cloudflare challenge refused every automated rung of the ladder.
 *
 * The recipe is CloudStream's WebviewFragment with this app's identity
 * discipline: a REAL, visible, interactive WebView the user browses the
 * site in, JavaScript and DOM storage on, third-party cookies accepted —
 * and the User-Agent pinned to [BrowserViewModel.userAgent], the same
 * Chrome-coherent identity the challenge dialogs and every clearance
 * replay wear. Whatever the verification issues lands in the system
 * cookie jar the ladder's warm start already reads, so the clearance is
 * remembered across requests, launches and process death. The status
 * strip under the app bar tells the user the one thing that matters —
 * whether the verification has been passed yet — because a checkbox
 * nobody knows to tap is a source that never loads (the v1.2.11
 * lesson).
 *
 * Teardown is the point of the trip: leaving the screen (X, system
 * back past the last history entry, or the activity dying) tears the
 * ViewModel down, and its [BrowserViewModel.onCleared] forgives the
 * ladder's cooldowns and warm-read latch so the next Retry picks the
 * hand-earned clearance up on the very first request. Cookies are
 * flushed on every page finish and again on dispose, so nothing earned
 * is lost to a process death.
 */
@Composable
fun BrowserScreen(
    url: String,
    onClosed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var hasClearance by remember(url) { mutableStateOf(false) }

    // History back inside the page; anything deeper than that closes the
    // browser (and its teardown fires the ladder forgiveness).
    BackHandler(enabled = canGoBack && webView != null) {
        webView?.goBack()
    }

    // The status strip's pulse: challenges settle through navigations and
    // XHRs the page drives itself, so the jar is re-read on a slow beat
    // rather than hooked to any single event.
    LaunchedEffect(url) {
        while (true) {
            val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            hasClearance = cookies != null && CLEARANCE_COOKIE in cookies
            delay(CLEARANCE_POLL_MS)
        }
    }

    // Whatever the session earned must outlive the WebView — flush to disk
    // as the screen leaves composition (the per-page-finish flush already
    // covers the common case; this one covers the last navigation).
    DisposableEffect(Unit) {
        onDispose {
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    Column(modifier = modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        BrowserAppBar(
            title = url,
            canGoBack = canGoBack,
            hasClearance = hasClearance,
            onBack = { if (webView?.canGoBack() == true) webView?.goBack() else onClosed() },
            onReload = { webView?.reload() },
            onClose = onClosed,
        )
        AndroidView(
            factory = { context ->
                WebView(context).also { view ->
                    configureWebView(view, viewModel.userAgent())
                    view.webViewClient =
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean = false

                            override fun onPageFinished(
                                view: WebView,
                                url: String?,
                            ) {
                                // Persist what this navigation earned, and
                                // recompute the history affordance — both
                                // cheap, both only meaningful per page.
                                runCatching { CookieManager.getInstance().flush() }
                                canGoBack = view.canGoBack()
                            }
                        }
                    webView = view
                    view.loadUrl(url)
                }
            },
            onRelease = { view ->
                // Leaving composition (pop, or the activity rotating):
                // the engine instance is done — stop and destroy it so
                // nothing native outlives the screen. The cookie jar is
                // the system's, not the view's; what it earned stays.
                runCatching { view.stopLoading() }
                runCatching { view.destroy() }
            },
            modifier = Modifier.fillMaxSize().testTag("browser:webview"),
        )
    }
}

/**
 * The browser's chrome: one row of affordances (history back, reload,
 * close) over the site's host, plus the verification status strip — the
 * honest "what is happening" bar the challenge dialogs taught this app
 * to carry (v1.2.11).
 */
@Composable
private fun BrowserAppBar(
    title: String,
    canGoBack: Boolean,
    hasClearance: Boolean,
    onBack: () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("browser:back")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription =
                            stringResource(
                                if (canGoBack) R.string.browser_navigate_back else R.string.browser_close,
                            ),
                    )
                }
                Text(
                    text = hostOf(title),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onReload, modifier = Modifier.testTag("browser:reload")) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = stringResource(R.string.browser_reload),
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onClose, modifier = Modifier.testTag("browser:close")) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.browser_close),
                    )
                }
            }
            Text(
                text =
                    stringResource(
                        if (hasClearance) R.string.browser_verified else R.string.browser_verify_hint,
                    ),
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (hasClearance) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("browser:status"),
            )
        }
    }
}

/** The bare host to show as the title — "www.wallpaperflare.com", say. */
private fun hostOf(url: String): String = runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

/**
 * The WebView configuration the challenge dialogs proved (v1.0.16
 * onward): JavaScript ON (the verification IS JavaScript), DOM storage
 * ON (the challenge reads it like any real page), third-party cookies
 * accepted, and the User-Agent the ladder binds its clearances to — so
 * the (cookies, agent) pair this session earns is the pair the ladder
 * replays. A null agent (no WebView to report one) falls back to the
 * engine's own default rather than guessing.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun configureWebView(
    webView: WebView,
    userAgent: String?,
) {
    webView.settings.javaScriptEnabled = true
    webView.settings.domStorageEnabled = true
    userAgent?.let { webView.settings.userAgentString = it }
    val cookies = CookieManager.getInstance()
    cookies.setAcceptCookie(true)
    cookies.setAcceptThirdPartyCookies(webView, true)
}
