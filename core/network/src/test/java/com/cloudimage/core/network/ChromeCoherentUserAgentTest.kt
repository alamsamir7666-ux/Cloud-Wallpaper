package com.cloudimage.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Chrome-coherent challenge agent (v1.2.17) — the surgical User-Agent
 * transform the solver's WebViews wear and every clearance is bound to.
 *
 * What is pinned here:
 * - the two embedded-only markers of an Android WebView agent — the
 *   parenthetical's `; wv` and the fossil `Version/4.0` WebKit token —
 *   are removed while the TRUE Chrome version, platform and device
 *   profile survive verbatim, leaving exactly what the phone's own
 *   Chrome would send;
 * - an agent with neither token (a desktop Chrome form, an exotic
 *   WebView spelling) returns unchanged — the transform is surgical,
 *   never speculative;
 * - a `wv` outside the parenthetical (a build label) is not the marker
 *   and must survive;
 * - whitespace disturbed by a removal is renormalized, and blank input
 *   stays blank.
 *
 * The wallpapersden.com field report is why this exists: the zone serves
 * a clean browser vantage real content while re-looping its interactive
 * challenge against the app's WebView on two different devices, and the
 * one static fingerprint difference between that WebView and the phones'
 * own Chrome is these two tokens. See [chromeCoherentUserAgent]'s KDoc
 * for why this is not the reverted v1.2.3 experiment (no version is
 * claimed the engine is not).
 */
class ChromeCoherentUserAgentTest {
    /** The canonical modern Android WebView default agent. */
    private val webViewAgent =
        "Mozilla/5.0 (Linux; Android 16; SM-A536B; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/138.0.7204.63 Mobile Safari/537.36"

    /** What the same phone's own Chrome sends — the transform's target. */
    private val chromeAgent =
        "Mozilla/5.0 (Linux; Android 16; SM-A536B) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.63 Mobile Safari/537.36"

    @Test
    fun stripsBothEmbeddedMarkersAndKeepsTheTrueChromeVersion() {
        assertEquals(chromeAgent, chromeCoherentUserAgent(webViewAgent))
    }

    @Test
    fun agentWithoutMarkersReturnsUnchanged() {
        assertEquals(chromeAgent, chromeCoherentUserAgent(chromeAgent))
    }

    @Test
    fun stripsWvAlone() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/97.0.4692.98 Mobile Safari/537.36",
            chromeCoherentUserAgent(
                "Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/97.0.4692.98 Mobile Safari/537.36",
            ),
        )
    }

    @Test
    fun stripsLegacyVersionTokenAlone() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.6478.122 Mobile Safari/537.36",
            chromeCoherentUserAgent(
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/126.0.6478.122 Mobile Safari/537.36",
            ),
        )
    }

    @Test
    fun wvOutsideTheParentheticalIsNotTheMarkerAndSurvives() {
        val agent =
            "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Mobile Safari/537.36 Build/wv-2025.10"
        assertEquals(agent, chromeCoherentUserAgent(agent))
    }

    @Test
    fun whitespaceDisturbedByRemovalIsRenormalized() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.7204.63 Mobile Safari/537.36",
            chromeCoherentUserAgent(
                "Mozilla/5.0 (Linux; Android 16; SM-A536B ;  wv) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko)  Version/4.0  Chrome/138.0.7204.63 Mobile Safari/537.36",
            ),
        )
    }

    @Test
    fun tabletAgentsCleanTheSameWay() {
        assertEquals(
            "Mozilla/5.0 (Linux; Android 13; SM-X710) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.7034.111 Safari/537.36",
            chromeCoherentUserAgent(
                "Mozilla/5.0 (Linux; Android 13; SM-X710; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/132.0.7034.111 Safari/537.36",
            ),
        )
    }

    @Test
    fun blankAgentStaysBlank() {
        assertEquals("", chromeCoherentUserAgent(""))
    }

    @Test
    fun leadingAndTrailingWhitespaceIsTrimmed() {
        assertEquals(
            chromeAgent,
            chromeCoherentUserAgent("  $webViewAgent  "),
        )
    }
}
