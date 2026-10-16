package com.cloudimage.feature.browser

import androidx.lifecycle.ViewModel
import com.cloudimage.core.network.CloudflareBypasser
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * The in-app browser's engine seam (v1.2.19) — deliberately tiny, and
 * deliberately free of every Android WebView class so it stays
 * unit-testable on the JVM. The screen owns the WebView; this view model
 * owns the two moments where the browser meets the Cloudflare ladder:
 *
 * - the User-Agent the browser wears — [CloudflareBypasser.challengeUserAgent],
 *   the Chrome-coherent identity every clearance is bound to. Wearing it
 *   here is what makes a hand-earned clearance replayable: the ladder's
 *   warm start reconstructs the (cookies, agent) pair under the SAME
 *   string, so the pair the user earned in the browser is exactly the
 *   pair the next request replays under;
 * - the teardown — [onCleared] fires on EVERY exit path (the X button
 *   pops the destination, the system back pops it, the activity dies
 *   under it), and it forgives the ladder's failure cooldowns AND the
 *   warm-read latch, so the next Retry warm-reads the jar the user just
 *   wrote their clearance into instead of dying on a cached "no state".
 *
 * The inspiration is CloudStream's WebviewFragment: their Cloudflare
 * story ends with the user solving the challenge once in a real browser
 * session whose cookies land in the shared system jar — the same jar
 * their CloudflareKiller harvests before ever spinning a solver. The
 * difference here is the identity discipline: their fragment browses
 * under a desktop Chrome agent while the killer replays under the
 * WebView default — a mismatch this view model closes by asking the
 * ladder itself which agent to wear.
 */
@HiltViewModel
class BrowserViewModel
    @Inject
    constructor(
        private val cloudflare: CloudflareBypasser,
    ) : ViewModel() {
        /**
         * The agent the WebView should pin, or null to keep the engine's
         * own default — the degraded-but-honest fallback for devices
         * that cannot report an agent. Must be read on the main thread
         * (WebView machinery); the screen does, at factory time.
         */
        fun userAgent(): String? = cloudflare.challengeUserAgent()

        /**
         * The browser session's goodbye (v1.2.19): the ladder's cooldowns
         * and warm-read latch are forgiven, so the next Retry warm-reads
         * the jar the user may have just written a clearance into. Called
         * by the screen's close affordances AND by [onCleared] — the
         * system-back exit goes through ViewModel teardown alone, and
         * double-calling is harmless (clearing cleared maps).
         */
        fun onClosed() {
            cloudflare.forgiveFailures()
        }

        override fun onCleared() = onClosed()
    }
