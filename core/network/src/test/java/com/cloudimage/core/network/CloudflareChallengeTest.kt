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
 * - interstitial bodies — current (challenge-platform scripts) and legacy
 *   (`jschl` forms) — are recognized without the header;
 * - everything that looks like Cloudflare but is NOT solvable by a
 *   WebView stays asleep: plain API 403s (bad keys are not challenges),
 *   the "Attention Required" hard block page (an IP block cannot be
 *   solved), and 2xx pages that merely mention the interstitial copy
 *   (a blog post about Cloudflare is not a challenge).
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
    fun hardBlockPageIsNotAChallenge() {
        // "Attention Required" is the IP-block page — a WebView solve cannot
        // lift it, so it must not trigger one.
        val body =
            """
            <html><head><title>Attention Required! | Cloudflare</title></head>
            <body>Sorry, you have been blocked. If you believe this is a mistake
            ray ID: 8f2a-example</body></html>
            """.trimIndent()

        assertFalse(CloudflareChallenge.isChallenge(403, emptyMap(), body))
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
}
