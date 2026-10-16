package com.cloudimage.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bypass state machine (v1.0.15): per-host serialization, caching,
 * cooldowns and warm starts — over a scripted solver, since the real one
 * is a WebView and the JVM has none.
 *
 * What is pinned here:
 * - an earned clearance is cached and served to later requests without
 *   waking the solver again;
 * - concurrent challenges for one host run the solver ONCE — the home
 *   screen fires a dozen rows at the same origin, and a challenge clears
 *   for the whole host;
 * - a failed solve drops the burned state and cools the host down, so a
 *   stubborn zone cannot spawn a WebView per request;
 * - a state that stopped passing (the stale one the challenged request
 *   carried) is re-solved, while a state another coroutine earned
 *   meanwhile is simply ridden;
 * - a persisted clearance (the WebView cookie jar outliving the process)
 *   is read once per host per launch;
 * - nothing about a malformed URL ever reaches the solver;
 * - the document-fetch rung (v1.2.3): a fetched page's clearance is
 *   cached, a fetch failure cools the FETCH lane without closing the
 *   solve lane, and a clearance earned while a caller waited on the
 *   host lock is handed back instead of a page;
 * - the WebView-only mode (v1.2.6): a marked host routes straight to the
 *   document lane, successful fetches refresh the mark, unused marks
 *   lapse, and a failed document fetch cools its own lane;
 * - the zone keying (v1.2.11): clearances, locks, cooldowns and warm
 *   checks are keyed by the request host's registrable domain, so a
 *   site's clearance serves its image CDN — wallpapersden.com's shape —
 *   and the snapshot seam ([CloudflareBypasser.currentBypassFor]) hands
 *   that same identity to the app's image pipeline.
 */
class CloudflareBypasserTest {
    private class ScriptedSolver : CloudflareSolver {
        val solvedUrls = mutableListOf<String>()
        val fetchedUrls = mutableListOf<String>()
        val persistedHosts = mutableListOf<String>()
        var persisted: CloudflareBypass? = null
        var next: CloudflareBypass? = CloudflareBypass(cookieHeader = "cf_clearance=fresh", userAgent = "SolverAgent/1")
        var nextPage: WebViewPage? = null
        var failureMemoryResets = 0
        var agent: String? = null

        override fun challengeUserAgent(): String? = agent

        override suspend fun persistedStateFor(host: String): CloudflareBypass? {
            persistedHosts += host
            return persisted
        }

        override suspend fun solve(url: String): CloudflareBypass? {
            solvedUrls += url
            return next
        }

        override suspend fun fetch(url: String): WebViewPage? {
            fetchedUrls += url
            return nextPage
        }

        override fun resetFailureMemory() {
            failureMemoryResets += 1
        }
    }

    @Test
    fun earnedClearanceIsCachedAndServedWithoutASecondSolve() =
        runTest {
            val solver = ScriptedSolver()
            val bypasser = CloudflareBypasser(solver)

            val earned = bypasser.solve("https://www.wallpaperflare.com/search?wallpaper=nature")
            assertNotNull(earned)
            assertEquals(1, solver.solvedUrls.size)

            val cached = bypasser.bypassStateFor("https://www.wallpaperflare.com/anything/else")
            assertSame(earned, cached)
            assertEquals(1, solver.solvedUrls.size)
        }

    @Test
    fun concurrentChallengesForOneHostRunTheSolverOnce() =
        runTest {
            val solver =
                object : CloudflareSolver {
                    var calls = 0

                    override suspend fun persistedStateFor(host: String): CloudflareBypass? = null

                    override suspend fun solve(url: String): CloudflareBypass? {
                        calls++
                        delay(5_000) // a real WebView takes seconds, not nanoseconds
                        return CloudflareBypass(cookieHeader = "cf_clearance=one", userAgent = "SolverAgent/1")
                    }
                }
            val bypasser = CloudflareBypasser(solver)

            val results = (1..3).map { async { bypasser.solve("https://host.example/grid") } }.awaitAll()

            assertEquals(1, solver.calls)
            assertEquals(1, results.map { it?.cookieHeader }.distinct().size)
        }

    @Test
    fun failedSolveDropsStateAndCoolsTheHostDown() =
        runTest {
            var now = 0L
            val solver =
                ScriptedSolver().apply {
                    next = null // the WebView never settles
                }
            val bypasser = CloudflareBypasser(solver) { now }

            assertNull(bypasser.solve("https://host.example/grid"))
            assertEquals(1, solver.solvedUrls.size)

            // Inside the cooldown the solver is not even asked — half the
            // window, so the case stays meaningful at any cooldown length.
            now = CloudflareBypasser.FAILURE_COOLDOWN_MS / 2
            assertNull(bypasser.solve("https://host.example/grid"))
            assertEquals(1, solver.solvedUrls.size)

            // Past it, a fresh attempt is made and a newly earned state is kept.
            now = CloudflareBypasser.FAILURE_COOLDOWN_MS + 1
            solver.next = CloudflareBypass(cookieHeader = "cf_clearance=second", userAgent = "SolverAgent/1")
            assertNotNull(bypasser.solve("https://host.example/grid"))
            assertEquals(2, solver.solvedUrls.size)
        }

    @Test
    fun staleOnFileStateIsReplaced() =
        runTest {
            val solver = ScriptedSolver()
            val bypasser = CloudflareBypasser(solver)

            val first = bypasser.solve("https://host.example/grid")
            assertNotNull(first)
            // The request that carried `first` got challenged again — the
            // usual half-hour expiry — so the state is stale and re-solved.
            solver.next = CloudflareBypass(cookieHeader = "cf_clearance=second", userAgent = "SolverAgent/1")
            val second = bypasser.solve("https://host.example/grid", staleState = first)

            assertNotNull(second)
            assertEquals(2, solver.solvedUrls.size)
            assertEquals("cf_clearance=second", second?.cookieHeader)
            assertSame(second, bypasser.bypassStateFor("https://host.example/grid"))
        }

    @Test
    fun stateEarnedMeanwhileIsRiddenWithoutReSolving() =
        runTest {
            val solver = ScriptedSolver()
            val bypasser = CloudflareBypasser(solver)

            // One coroutine solved while this request was in flight and
            // challenged with no state of its own — ride the newer state.
            val earned = bypasser.solve("https://host.example/grid")
            assertNotNull(earned)

            val ridden = bypasser.solve("https://host.example/grid", staleState = null)

            assertSame(earned, ridden)
            assertEquals(1, solver.solvedUrls.size)
        }

    @Test
    fun persistedClearanceIsReadOncePerHostPerLaunch() =
        runTest {
            val solver =
                ScriptedSolver().apply {
                    persisted = CloudflareBypass(cookieHeader = "cf_clearance=kept", userAgent = "WebViewDefault/1")
                }
            val bypasser = CloudflareBypasser(solver)

            val first = bypasser.bypassStateFor("https://host.example/grid")
            assertEquals("cf_clearance=kept", first?.cookieHeader)
            bypasser.bypassStateFor("https://host.example/grid")
            bypasser.bypassStateFor("https://host.example/other")

            assertEquals(listOf("host.example"), solver.persistedHosts)
            // A different host gets its own warm look.
            bypasser.bypassStateFor("https://other.example/grid")
            assertEquals(listOf("host.example", "other.example"), solver.persistedHosts)
        }

    @Test
    fun malformedUrlsNeverReachTheSolver() =
        runTest {
            val solver = ScriptedSolver()
            val bypasser = CloudflareBypasser(solver)

            assertNull(bypasser.bypassStateFor("not a url"))
            assertNull(bypasser.solve("not a url"))
            assertNull(bypasser.webViewFetch("not a url"))
            assertNull(bypasser.fetchDocument("not a url"))

            assertEquals(0, solver.solvedUrls.size)
            assertEquals(0, solver.fetchedUrls.size)
            assertEquals(0, solver.persistedHosts.size)
        }

    // ---------------------------------------------- WebView document fetch

    @Test
    fun fetchedPageCachesTheClearanceItsTripEarned() =
        runTest {
            val solver =
                ScriptedSolver().apply {
                    nextPage =
                        WebViewPage(
                            html = "<html>the grid</html>",
                            clearance = CloudflareBypass(cookieHeader = "cf_clearance=earned-by-fetch", userAgent = "SolverAgent/1"),
                        )
                }
            val bypasser = CloudflareBypasser(solver)

            val page = bypasser.webViewFetch("https://host.example/grid")

            assertEquals("<html>the grid</html>", page?.html)
            // The clearance the trip earned is cached — later requests ride
            // it without waking the WebView again.
            assertEquals(
                "cf_clearance=earned-by-fetch",
                bypasser.bypassStateFor("https://host.example/other")?.cookieHeader,
            )
            assertEquals(1, solver.fetchedUrls.size)
        }

    @Test
    fun failedFetchCoolsTheFetchLaneButNotTheSolveLane() =
        runTest {
            var now = 0L
            val solver =
                ScriptedSolver().apply {
                    nextPage = null // the page never settles
                }
            val bypasser = CloudflareBypasser(solver) { now }

            assertNull(bypasser.webViewFetch("https://host.example/grid"))
            assertEquals(1, solver.fetchedUrls.size)

            // Inside the fetch cooldown the solver is not even asked again.
            now = CloudflareBypasser.FAILURE_COOLDOWN_MS / 2
            assertNull(bypasser.webViewFetch("https://host.example/grid"))
            assertEquals(1, solver.fetchedUrls.size)

            // ... while the SOLVE lane stays open the whole time — the rungs
            // fail for different reasons, and one must not close the other.
            // The solve also caches its clearance, which the fetch below
            // carries as its (rejected-anyway) stale identity.
            val earned = bypasser.solve("https://host.example/grid")
            assertNotNull(earned)
            assertEquals(1, solver.solvedUrls.size)

            // Past the fetch cooldown, a page is attempted again.
            now = CloudflareBypasser.FAILURE_COOLDOWN_MS + 1
            solver.nextPage = WebViewPage(html = "<html>late</html>", clearance = null)
            assertEquals("<html>late</html>", bypasser.webViewFetch("https://host.example/grid", staleState = earned)?.html)
            assertEquals(2, solver.fetchedUrls.size)
        }

    @Test
    fun aFailedSolveDoesNotCloseTheFetchLane() =
        runTest {
            val solver =
                ScriptedSolver().apply {
                    next = null // no clearance can be earned
                    nextPage = WebViewPage(html = "<html>grid via webview</html>", clearance = null)
                }
            val bypasser = CloudflareBypasser(solver)

            assertNull(bypasser.solve("https://host.example/grid"))

            // The very exchange that could not earn a clearance still gets
            // its document fetched — the whole point of the last rung.
            assertEquals("<html>grid via webview</html>", bypasser.webViewFetch("https://host.example/grid")?.html)
            assertEquals(1, solver.fetchedUrls.size)
        }

    @Test
    fun clearanceEarnedMeanwhileIsHandedBackInsteadOfAPage() =
        runTest {
            val solver = ScriptedSolver()
            val bypasser = CloudflareBypasser(solver)

            // Another coroutine earned this while our caller waited on the
            // host lock — riding it is cheaper than a page load.
            val earned = bypasser.solve("https://host.example/grid")
            assertNotNull(earned)

            val page = bypasser.webViewFetch("https://host.example/grid", staleState = null)

            assertEquals(null, page?.html)
            assertSame(earned, page?.clearance)
            assertEquals(0, solver.fetchedUrls.size)
        }

    @Test
    fun staleStateIsStillFetchedThrough() =
        runTest {
            val solver =
                ScriptedSolver().apply {
                    nextPage = WebViewPage(html = "<html>content</html>", clearance = null)
                }
            val bypasser = CloudflareBypasser(solver)

            // The clearance this request's replay carried (and that the zone
            // rejected anyway) is the STALE one — the fetch must not mistake
            // it for a newer state and skip the page.
            val stale = bypasser.solve("https://host.example/grid")
            assertNotNull(stale)

            assertEquals("<html>content</html>", bypasser.webViewFetch("https://host.example/grid", staleState = stale)?.html)
            assertEquals(1, solver.fetchedUrls.size)
        }

    // ------------------------------------------- WebView-only mode (v1.2.6)

    @Test
    fun webViewOnlyHostsRouteStraightToTheDocumentLane() =
        runTest {
            val solver = ScriptedSolver()
            val bypasser = CloudflareBypasser(solver)

            assertFalse(bypasser.isWebViewOnly("https://host.example/grid"))
            bypasser.markWebViewOnly("https://host.example/grid")
            assertTrue(bypasser.isWebViewOnly("https://host.example/grid"))

            // The lane asks the engine for the document — never a solve, never
            // a persisted-state look.
            solver.nextPage = WebViewPage(html = "<html>engine content</html>", clearance = null)
            val page = bypasser.fetchDocument("https://host.example/grid")

            assertEquals("<html>engine content</html>", page?.html)
            assertEquals(listOf("https://host.example/grid"), solver.fetchedUrls)
            assertEquals(0, solver.solvedUrls.size)
            assertEquals(0, solver.persistedHosts.size)

            // Other hosts are untouched — the mark is per host.
            assertFalse(bypasser.isWebViewOnly("https://other.example/grid"))
        }

    @Test
    fun successfulDocumentFetchesRefreshTheMark() =
        runTest {
            var now = 0L
            val solver =
                ScriptedSolver().apply {
                    nextPage = WebViewPage(html = "<html>content</html>", clearance = null)
                }
            val bypasser = CloudflareBypasser(solver) { now }

            bypasser.markWebViewOnly("https://host.example/grid")
            // Twenty-nine minutes in, an actively browsed host fetches again —
            // the mark slides forward instead of lapsing.
            now = CloudflareBypasser.WEB_VIEW_ONLY_TTL_MS - 60_000
            assertNotNull(bypasser.fetchDocument("https://host.example/grid"))
            now = CloudflareBypasser.WEB_VIEW_ONLY_TTL_MS + 60_000
            assertTrue(bypasser.isWebViewOnly("https://host.example/grid"))
        }

    @Test
    fun unusedWebViewOnlyMarksLapse() =
        runTest {
            var now = 0L
            val solver = ScriptedSolver()
            val bypasser = CloudflareBypasser(solver) { now }

            bypasser.markWebViewOnly("https://host.example/grid")
            assertTrue(bypasser.isWebViewOnly("https://host.example/grid"))

            now = CloudflareBypasser.WEB_VIEW_ONLY_TTL_MS + 1
            assertFalse(bypasser.isWebViewOnly("https://host.example/grid"))
        }

    @Test
    fun failedDocumentFetchesCoolTheirOwnLane() =
        runTest {
            var now = 0L
            val solver =
                ScriptedSolver().apply {
                    nextPage = null // the engine trip fails
                }
            val bypasser = CloudflareBypasser(solver) { now }
            bypasser.markWebViewOnly("https://host.example/grid")

            assertNull(bypasser.fetchDocument("https://host.example/grid"))
            assertEquals(1, solver.fetchedUrls.size)

            // Inside the cooldown the solver is not even asked — and a failed
            // document does NOT mark the host, so a request between cooldowns
            // runs the honest ladder again instead of looping a dead lane.
            now = CloudflareBypasser.FAILURE_COOLDOWN_MS / 2
            assertNull(bypasser.fetchDocument("https://host.example/grid"))
            assertEquals(1, solver.fetchedUrls.size)
        }

    // ------------------------------------------- zone keying (v1.2.11)

    @Test
    fun zoneApproximationHoldsTheLine() {
        // The plain cases: a registrable domain is its own zone, deeper
        // hosts collapse onto it.
        assertEquals("wallpapersden.com", cloudflareZoneOf("wallpapersden.com"))
        assertEquals("wallpapersden.com", cloudflareZoneOf("images.wallpapersden.com"))
        assertEquals("wallpaperflare.com", cloudflareZoneOf("www.wallpaperflare.com"))
        assertEquals("wallpaperflare.com", cloudflareZoneOf("c4.wallpaperflare.com"))
        // Guarded suffixes step one label deeper, so sibling hosts under a
        // country-code second level still share ONE zone — not the suffix.
        assertEquals("site.co.uk", cloudflareZoneOf("www.site.co.uk"))
        assertEquals("site.co.uk", cloudflareZoneOf("cdn.site.co.uk"))
        // Short and degenerate inputs return as-is; they never earn state.
        assertEquals("localhost", cloudflareZoneOf("localhost"))
        assertEquals("host.example", cloudflareZoneOf("host.example"))
    }

    @Test
    fun aClearanceEarnedForTheSiteServesTheImageCdn() =
        runTest {
            val solver = ScriptedSolver()
            val bypasser = CloudflareBypasser(solver)

            // The document host's challenge solved once...
            val earned = bypasser.solve("https://wallpapersden.com/popular-wallpapers/")
            assertNotNull(earned)
            assertEquals(1, solver.solvedUrls.size)

            // ...and the image CDN of the SAME zone rides it — no second
            // solve, no second dialog, the one-identity contract that
            // wallpapersden.com's shared zone demanded.
            assertSame(earned, bypasser.bypassStateFor("https://images.wallpapersden.com/image/ws-x_1.jpg"))
            assertEquals(1, solver.solvedUrls.size)

            // The snapshot seam tells the app's image pipeline the same
            // story — by host, not URL.
            assertSame(earned, bypasser.currentBypassFor("images.wallpapersden.com"))
            assertNull(bypasser.currentBypassFor("c4.wallpaperflare.com"))
        }

    @Test
    fun oneWarmProbePerZoneServesEverySibling() =
        runTest {
            val solver = ScriptedSolver()
            val bypasser = CloudflareBypasser(solver)

            // Whichever sibling asks first reads the jar; the second
            // sibling's lookups ride the cached answer — one probe per
            // ZONE per launch, not per hostname.
            solver.persisted = CloudflareBypass(cookieHeader = "cf_clearance=kept", userAgent = "WebViewDefault/1")
            val first = bypasser.bypassStateFor("https://wallpapersden.com/popular-wallpapers/")
            val second = bypasser.bypassStateFor("https://images.wallpapersden.com/image/ws-x_1.jpg")

            assertEquals("cf_clearance=kept", first?.cookieHeader)
            assertSame(first, second)
            assertEquals(listOf("wallpapersden.com"), solver.persistedHosts)
        }

    @Test
    fun aFailedSolveCoolsTheWholeZone() =
        runTest {
            var now = 0L
            val solver =
                ScriptedSolver().apply {
                    next = null // the challenge never settles
                }
            val bypasser = CloudflareBypasser(solver) { now }

            assertNull(bypasser.solve("https://wallpapersden.com/popular-wallpapers/"))
            assertEquals(1, solver.solvedUrls.size)

            // The CDN's solve lane is the SITE's lane — the same zone just
            // failed, and no sibling spawns its own dialog inside the window.
            now = CloudflareBypasser.FAILURE_COOLDOWN_MS / 2
            assertNull(bypasser.solve("https://images.wallpapersden.com/image/ws-x_1.jpg"))
            assertEquals(1, solver.solvedUrls.size)
        }

    @Test
    fun forgivenFailuresLetTheSolverRunAgainImmediately() =
        runTest {
            // v1.2.12: the Retry button's contract. A zone that just failed
            // swallows the next automatic request (the stampede the cooldown
            // exists for), but forgiveFailures — the user's explicit ask —
            // hands the very next solve its fresh, honest attempt, solver
            // failure memory included.
            val solver =
                ScriptedSolver().apply {
                    next = null // the zone refuses
                }
            val bypasser = CloudflareBypasser(solver)

            assertNull(bypasser.solve("https://wallpapersden.com/popular-wallpapers/"))
            assertNull(bypasser.solve("https://wallpapersden.com/latest-wallpapers/"))
            assertEquals(1, solver.solvedUrls.size)
            assertEquals(0, solver.failureMemoryResets)

            bypasser.forgiveFailures()

            assertNull(bypasser.solve("https://wallpapersden.com/latest-wallpapers/"))
            assertEquals(2, solver.solvedUrls.size)
            assertEquals(1, solver.failureMemoryResets)
        }

    @Test
    fun forgivenessNeverCostsAnEarnedClearance() =
        runTest {
            // Forgetting failure state must not forget an identity the
            // user's tap already earned — a cleared zone stays cleared, and
            // no later request wakes the solver for it again.
            val solver = ScriptedSolver()
            val bypasser = CloudflareBypasser(solver)

            assertNotNull(bypasser.solve("https://wallpapersden.com/popular-wallpapers/"))

            bypasser.forgiveFailures()

            val state = bypasser.bypassStateFor("https://images.wallpapersden.com/image/ws-x_1.jpg")
            assertEquals("cf_clearance=fresh", state?.cookieHeader)
            assertEquals(1, solver.solvedUrls.size)
        }

    @Test
    fun forgivenessReReadsThePersistedJarAfterAHandEarnedClearance() =
        runTest {
            // v1.2.19: the in-app browser's contract. The zone already
            // failed this launch (warm latch set, cooldown set), and then
            // the user cleared the challenge BY HAND — the jar now holds
            // a clearance. Without forgiveness the one-warm-look-per-launch
            // latch would keep that clearance invisible until an app
            // restart; the browser's teardown forgiveness must re-open the
            // warm read so the very next request replays under it.
            val solver =
                ScriptedSolver().apply {
                    next = null // the automated solve fails
                    persisted = null // and the jar starts empty
                }
            val bypasser = CloudflareBypasser(solver)

            assertNull(bypasser.bypassStateFor("https://www.wallpaperflare.com/search?wallpaper=nature"))
            assertNull(bypasser.solve("https://www.wallpaperflare.com/search?wallpaper=nature"))
            assertEquals(1, solver.solvedUrls.size)

            // The user opens the site in the in-app browser and taps the
            // checkbox: the system jar — the one persistedStateFor reads —
            // now holds a hand-earned clearance.
            solver.persisted = CloudflareBypass(cookieHeader = "cf_clearance=hand-earned", userAgent = "SolverAgent/1")

            // Still invisible: the zone was already warm-checked and its
            // solve lane is cooling down.
            assertNull(bypasser.bypassStateFor("https://www.wallpaperflare.com/search?wallpaper=nature"))

            // The browser closes — teardown forgiveness.
            bypasser.forgiveFailures()

            val state = bypasser.bypassStateFor("https://www.wallpaperflare.com/search?wallpaper=nature")
            assertNotNull(state)
            assertEquals("cf_clearance=hand-earned", state?.cookieHeader)
            // The clearance was READ, not re-solved — the hand-earned pass
            // is the one that stuck.
            assertEquals(1, solver.solvedUrls.size)
        }

    @Test
    fun theChallengeUserAgentIsTheSolversIdentity() =
        runTest {
            // v1.2.19: the in-app browser wears whatever the ladder binds
            // its clearances to — one identity everywhere, so a hand-earned
            // (cookies, agent) pair is exactly what the replay expects.
            val solver =
                ScriptedSolver().apply {
                    agent = "ChromeCoherent/1 (the solver's pinned identity)"
                }
            val bypasser = CloudflareBypasser(solver)

            assertEquals("ChromeCoherent/1 (the solver's pinned identity)", bypasser.challengeUserAgent())
        }
}
