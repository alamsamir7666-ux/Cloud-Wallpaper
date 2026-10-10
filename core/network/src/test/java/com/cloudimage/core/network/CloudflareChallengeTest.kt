package com.cloudimage.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The challenge detector (v1.0.15) — the gate that decides whether a
 * response wakes the WebView bypass at all.
 *
 * What is pinned here:
 * - every challenge status with the modern `cf-mitigated: challenge`
 *   header is a challenge, header case and value case notwithstanding;
 * - every challenge status SERVED BY A CLOUDFLARE EDGE is a challenge,
 *   whatever the body says (v1.2.5, CloudStream's own trigger — see the
 *   block-page cases below for why that U-turn from v1.0.16 was made);
 * - interstitial bodies — current (challenge-platform scripts) and legacy
 *   (`jschl` forms) — are recognized without the header;
 * - plain API 403s with non-Cloudflare servers stay asleep (bad keys are
 *   not challenges), and 2xx pages that merely mention the interstitial
 *   copy (a blog post about Cloudflare) never wake the bypass.
 *
 * v1.0.16 added the block-page veto ("Attention Required" copy must not
 * burn a solve an IP block can never pass) and v1.2.5 scoped it: the
 * veto now applies only to responses NO Cloudflare edge served. A zone
 * can answer the HTTP client's fingerprint with the block page while
 * serving the very same WebView a challenge it settles — the live
 * WallpaperFlare regression: the block copy vetoed the markers, the
 * ladder never woke, and the user's only signal was the 403 banner with
 * NO challenge dialog at all. CloudStream's CloudflareKiller fires on
 * any cloudflare-served 403/503 with no block exception for exactly this
 * reason; the detector now lets the WebView decide what a block bound.
 */
class CloudflareChallengeTest {
    private val challengeStatuses = listOf(403, 429, 503)

    @Test
    fun mitigatedHeaderMarksEveryChallengeStatus() {
        for (status in challengeStatuses) {
            assertTrue(
                "status $status with cf-mitigated must be a challenge",
                CloudflareChallenge.isChallenge(status, mapOf("cf-mitigated" to listOf("challenge")), ""),
            )
        }
    }

    @Test
    fun mitigatedHeaderIsCaseInsensitive() {
        assertTrue(
            CloudflareChallenge.isChallenge(403, mapOf("CF-Mitigated" to listOf("Challenge")), ""),
        )
    }

    @Test
    fun mitigatedHeaderWithOtherValuesIsNotAChallenge() {
        assertFalse(
            CloudflareChallenge.isChallenge(403, mapOf("cf-mitigated" to listOf("block")), ""),
        )
    }

    @Test
    fun cloudflareEdgeAnswersEveryChallengeStatusAsSolverWorthy() {
        // v1.2.5, CloudStream's rule: `server: cloudflare` + 403/429/503
        // wakes the WebView, no matter the body — the block may bind the
        // HTTP client's fingerprint without binding the browser engine.
        for (status in challengeStatuses) {
            assertTrue(
                "status $status from a Cloudflare edge must wake the solver",
                CloudflareChallenge.isChallenge(status, mapOf("server" to listOf("cloudflare")), ""),
            )
        }
    }

    @Test
    fun cloudflareEdgeServerHeaderIsCaseInsensitive() {
        assertTrue(
            CloudflareChallenge.isChallenge(403, mapOf("Server" to listOf("Cloudflare")), ""),
        )
    }

    @Test
    fun legacyCloudflareNginxServerSpellingAlsoWakesTheSolver() {
        assertTrue(
            CloudflareChallenge.isChallenge(503, mapOf("server" to listOf("cloudflare-nginx")), ""),
        )
    }

    @Test
    fun cloudflareEdgeBlockPageWakesTheSolver() {
        // The v1.2.5 U-turn, pinned: the live WallpaperFlare regression had
        // the zone answering the app's OkHttp requests with this exact body
        // while the WebView sailed through — so block copy on a Cloudflare
        // edge must NOT veto the ladder. The WebView decides what the block
        // binds, not the detector.
        val body =
            """
            <html><head><title>Attention Required! | Cloudflare</title></head>
            <body>Sorry, you have been blocked. You are unable to access
            wallpaperflare.com. Ray ID: 8f2a-example</body></html>
            """.trimIndent()

        assertTrue(
            CloudflareChallenge.isChallenge(403, mapOf("server" to listOf("cloudflare")), body),
        )
    }

    @Test
    fun managedInterstitialBodyIsAChallenge() {
        val body =
            """
            <!DOCTYPE html><html lang="en-US"><head><title>Just a moment...</title>
            <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/challenge_page/v1" defer></script>
            </head><body>Enable JavaScript and cookies to continue</body></html>
            """.trimIndent()

        assertTrue(CloudflareChallenge.isChallenge(403, emptyMap(), body))
    }

    @Test
    fun legacyCheckingYourBrowserPageIsAChallenge() {
        val body =
            """
            <html><head><title>Checking your browser before accessing wallpaperflare.com</title></head>
            <body>Please turn JavaScript on and reload this page.
            <form id="challenge-form" action="/__cf_chl_jschl_tk__=abc">
            <input type="hidden" name="jschl_vc" value="hash"/></form></body></html>
            """.trimIndent()

        assertTrue(CloudflareChallenge.isChallenge(503, emptyMap(), body))
    }

    @Test
    fun plainApiForbiddenIsNotAChallenge() {
        assertFalse(
            CloudflareChallenge.isChallenge(403, emptyMap(), """{"errors":["invalid api key"]}"""),
        )
    }

    @Test
    fun hardBlockPageWithoutACloudflareServerIsNotAChallenge() {
        // "Attention Required" is the IP-block page — a WebView solve cannot
        // lift it, so it must not trigger one. v1.2.5 keeps this veto for
        // responses no Cloudflare edge served (a plain nginx 403 wearing
        // block copy is somebody's error page, not a solvable challenge).
        val body =
            """
            <html><head><title>Attention Required! | Cloudflare</title></head>
            <body>Sorry, you have been blocked. If you believe this is a mistake
            ray ID: 8f2a-example</body></html>
            """.trimIndent()

        assertFalse(CloudflareChallenge.isChallenge(403, emptyMap(), body))
    }

    @Test
    fun liveHardBlockPageWithoutACloudflareServerIsNotAChallenge() {
        // The REAL block page, shape captured from wallpaperflare.com: the
        // ray-ID copy button loads a challenge-platform script, so the
        // v1.0.15 marker-only rule woke a solve on a page no solve can ever
        // pass. Served WITHOUT a Cloudflare server header (the captured
        // exchange carried one, but the veto must stand on its own for
        // proxies that strip it), block copy still vetoes the marker.
        val body =
            """
            <!DOCTYPE html><html lang="en-US"><head>
            <title>Attention Required! | Cloudflare</title>
            <meta name="robots" content="noindex, nofollow" />
            <script type="text/javascript" src="/cdn-cgi/challenge-platform/h/b/orchestrate/jschlm"></script>
            </head><body><div class="cf-error-details">Sorry, you have been blocked</div>
            <div>You are unable to access wallpaperflare.com</div></body></html>
            """.trimIndent()

        assertFalse(CloudflareChallenge.isChallenge(403, emptyMap(), body))
    }

    @Test
    fun wafCustomRuleBlockIsNotAChallenge() {
        // "error code: 1020" is a WAF custom rule deny — same story as the
        // IP block: not solvable by a WebView.
        val body =
            """
            <html><head><title>Access denied | Cloudflare</title></head>
            <body>error code: 1020</body></html>
            """.trimIndent()

        assertFalse(CloudflareChallenge.isChallenge(403, emptyMap(), body))
    }

    @Test
    fun blockCopyVetoesInterstitialMarkers() {
        // A body carrying BOTH block copy and an interstitial marker is a
        // block page — real pages never mix, but the precedence must be
        // pinned so a marker can never smuggle a block past the veto.
        val body = "Attention Required — Just a moment while challenge-platform loads"

        assertFalse(CloudflareChallenge.isChallenge(403, emptyMap(), body))
    }

    @Test
    fun mitigatedHeaderOutranksBlockCopy() {
        // The header is authoritative: when Cloudflare itself says the
        // response is a challenge, block-shaped copy in the body cannot
        // overrule it.
        val body = "Attention Required! Sorry, you have been blocked."

        assertTrue(
            CloudflareChallenge.isChallenge(403, mapOf("cf-mitigated" to listOf("challenge")), body),
        )
    }

    @Test
    fun successPageMentioningTheInterstitialIsNotAChallenge() {
        // A 200 blog post ABOUT the interstitial must not wake the bypass:
        // the status gate runs before any marker.
        val body = "<article>When Cloudflare says \"Just a moment...\" you should wait.</article>"

        assertFalse(CloudflareChallenge.isChallenge(200, emptyMap(), body))
    }

    @Test
    fun emptyBodiesNeverMatchMarkers() {
        for (status in challengeStatuses) {
            assertFalse(CloudflareChallenge.isChallenge(status, emptyMap(), ""))
        }
    }

    @Test
    fun payloadOverloadChecksTheWholeExchange() {
        val payload =
            HttpPayload(
                statusCode = 403,
                headers = mapOf("cf-mitigated" to listOf("challenge")),
                body = ByteArray(0),
            )

        assertTrue(CloudflareChallenge.isChallenge(payload))
    }

    // ---------------------------------------- rendered-document settle checks

    @Test
    fun settledContentPageWithInjectedDetectionsScriptIsContent() {
        // v1.2.4's regression case: bot-managed zones inject their
        // /cdn-cgi/challenge-platform JSD script into NORMAL pages, so the
        // marker-any rule rejected the very content the fetch rung earned
        // and every fetch "timed out" behind a page that had already
        // settled. The site's own title settles it: this is content.
        val html =
            """
            <!DOCTYPE html><html lang="en"><head>
            <title>Wallpaper Flare - HD Wallpapers</title>
            <script src="/cdn-cgi/challenge-platform/h/b/jsd/main.js" defer></script>
            </head><body><ul class="gallery"><li><figure><img src="/wallpaper/a/b/c/slug-preview.jpg"/></figure></li></ul></body></html>
            """.trimIndent()

        assertFalse(CloudflareChallenge.isInterstitialDocument(html))
    }

    @Test
    fun managedInterstitialDocumentIsInterstitial() {
        val html =
            """
            <!DOCTYPE html><html lang="en-US"><head><title>Just a moment...</title>
            <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/challenge_page/v1" defer></script>
            </head><body>Enable JavaScript and cookies to continue</body></html>
            """.trimIndent()

        assertTrue(CloudflareChallenge.isInterstitialDocument(html))
    }

    @Test
    fun blockPageDocumentIsInterstitial() {
        // The rendered fetch path treats block copy as never-settled — a
        // WebView cannot lift an IP block, so the page must not be served
        // as content.
        val html =
            """
            <html><head><title>Attention Required! | Cloudflare</title></head>
            <body>Sorry, you have been blocked. You are unable to access wallpaperflare.com</body></html>
            """.trimIndent()

        assertTrue(CloudflareChallenge.isInterstitialDocument(html))
    }

    @Test
    fun legacyChallengeFormDocumentIsInterstitial() {
        val html =
            """
            <html><head><title>Checking your browser before accessing wallpaperflare.com</title></head>
            <body><form id="challenge-form" action="/__cf_chl_jschl_tk__=abc">
            <input type="hidden" name="jschl_vc" value="hash"/></form></body></html>
            """.trimIndent()

        assertTrue(CloudflareChallenge.isInterstitialDocument(html))
    }

    @Test
    fun interstitialTitlesMatchCaseInsensitively() {
        val html = "<html><head><TITLE>jUST A mOMENT...</TITLE></head><body></body></html>"

        assertTrue(CloudflareChallenge.isInterstitialDocument(html))
    }

    @Test
    fun emptyDocumentIsNeverInterstitial() {
        assertFalse(CloudflareChallenge.isInterstitialDocument(""))
    }

    @Test
    fun contentMentioningCloudflareCopyInBodyIsContent() {
        // A wallpaper whose TITLE is honest but whose description text
        // mentions challenge copy is still content — the title leads.
        val html =
            """
            <html><head><title>Gallery - games wallpaper</title></head>
            <body>Just a moment while the gallery loads — 4K and 5K wallpapers.</body></html>
            """.trimIndent()

        assertFalse(CloudflareChallenge.isInterstitialDocument(html))
    }
}
