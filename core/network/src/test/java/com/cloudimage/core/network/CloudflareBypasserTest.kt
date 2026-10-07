package com.cloudimage.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 *   host lock is handed back instead of a page.
 */
class CloudflareBypasserTest {
    private class ScriptedSolver : CloudflareSolver {
        val solvedUrls = mutableListOf<String>()
        val fetchedUrls = mutableListOf<String>()
        val persistedHosts = mutableListOf<String>()
        var persisted: CloudflareBypass? = null
        var next: CloudflareBypass? = CloudflareBypass(cookieHeader = "cf_clearance=fresh", userAgent = "SolverAgent/1")
        var nextPage: WebViewPage? = null

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

            // Inside the cooldown the solver is not even asked.
            now = 30_000
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
            now = 30_000
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
}
