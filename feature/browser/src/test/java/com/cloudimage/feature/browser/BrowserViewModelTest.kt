package com.cloudimage.feature.browser

import com.cloudimage.core.network.CloudflareBypass
import com.cloudimage.core.network.CloudflareBypasser
import com.cloudimage.core.network.CloudflareSolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The in-app browser's engine seam (v1.2.19) — the two moments the
 * browser meets the Cloudflare ladder, in the only shapes that matter:
 *
 * - it wears the ladder's OWN User-Agent identity, so a clearance the
 *   user earns by hand binds to exactly the (cookies, agent) pair the
 *   warm start will replay;
 * - leaving the browser forgives the ladder's failure cooldowns AND the
 *   warm-read latch, so the next Retry picks the hand-earned clearance
 *   up on the very first request instead of dying on a cached "no
 *   state" until an app restart.
 */
class BrowserViewModelTest {
    private class RecordingSolver : CloudflareSolver {
        var agent: String? = null
        var failureMemoryResets = 0

        override suspend fun persistedStateFor(host: String): CloudflareBypass? = null

        override suspend fun solve(url: String): CloudflareBypass? = null

        override fun challengeUserAgent(): String? = agent

        override fun resetFailureMemory() {
            failureMemoryResets += 1
        }
    }

    @Test
    fun theBrowserWearsTheLaddersOwnIdentity() {
        val solver =
            RecordingSolver().apply {
                agent = "ChromeCoherent/1 (the device's cleaned default)"
            }
        val viewModel = BrowserViewModel(CloudflareBypasser(solver))

        assertEquals("ChromeCoherent/1 (the device's cleaned default)", viewModel.userAgent())
    }

    @Test
    fun aDeviceWithoutAnIdentityDegradesHonestlyInsteadOfGuessing() {
        val solver = RecordingSolver()
        val viewModel = BrowserViewModel(CloudflareBypasser(solver))

        assertNull(viewModel.userAgent())
    }

    @Test
    fun leavingTheBrowserForgivesTheLaddersFailureState() {
        val solver = RecordingSolver()
        val bypasser = CloudflareBypasser(solver)
        val viewModel = BrowserViewModel(bypasser)

        // The browser session ends — close button or system back, the
        // same teardown path.
        viewModel.onClosed()

        assertEquals(1, solver.failureMemoryResets)
    }

    @Test
    fun browserTeardownMakesTheNextWarmReadSeeTheHandEarnedClearance() =
        kotlinx.coroutines.test.runTest {
            // The full contract in one flow: the zone failed before the
            // browser opened (warm latch set), the user cleared the
            // challenge by hand (the jar now holds a clearance), and the
            // browser's teardown is what makes the next request SEE it.
            val handEarned = CloudflareBypass(cookieHeader = "cf_clearance=hand-earned", userAgent = "ChromeCoherent/1")
            val solver = JarSolver()
            val bypasser = CloudflareBypasser(solver)

            // The zone was warm-checked earlier this launch — nothing in
            // the jar then.
            assertNull(bypasser.bypassStateFor("https://www.wallpaperflare.com/"))

            // The user opens the site in the in-app browser and taps the
            // checkbox: the jar the warm start reads now holds the pass.
            solver.jar = handEarned

            // Still invisible — the one-warm-look-per-launch latch.
            assertNull(bypasser.bypassStateFor("https://www.wallpaperflare.com/"))

            // The browser session ends — close button or system back, the
            // same teardown path.
            val viewModel = BrowserViewModel(bypasser)
            viewModel.onClosed()

            assertEquals(1, solver.resets)
            assertEquals(handEarned, bypasser.bypassStateFor("https://www.wallpaperflare.com/"))
        }

    /** The system cookie jar as the solver sees it, settable mid-test. */
    private class JarSolver : CloudflareSolver {
        var jar: CloudflareBypass? = null
        var resets = 0

        override suspend fun persistedStateFor(host: String): CloudflareBypass? = jar

        override suspend fun solve(url: String): CloudflareBypass? = null

        override fun resetFailureMemory() {
            resets += 1
        }
    }
}
