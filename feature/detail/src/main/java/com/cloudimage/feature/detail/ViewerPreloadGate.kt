package com.cloudimage.feature.detail

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The readiness gate for in-place paging (v1.0.24).
 *
 * The page transition of v1.0.23 swapped the wallpaper the moment its
 * exit animation finished — but the incoming image still had to load, so
 * the swap traded the old image for a blank beat: a black flash, then the
 * new one popping in mid-animation. The gate closes that hole. While a
 * sideways drag gathers, the neighbor it points at warms up in an
 * offscreen copy of the viewer's own image stack; the exit->swap->enter
 * animation only starts once that copy paints (or gives up).
 *
 * Verdicts are one-per-target and stale-proof: a report for a target
 * nobody is waiting on simply sits in the channel, and the next [await]
 * skips it. [arm] is idempotent for the same wallpaper, so re-arming the
 * neighbor a drag keeps pointing at composes nothing twice.
 */
internal class ViewerPreloadGate {
    private val mutableArmed = mutableStateOf<Wallpaper?>(null)

    /** The wallpaper warming up offscreen; null when the gate is idle. */
    val armed: State<Wallpaper?> get() = mutableArmed

    private val verdicts = Channel<Verdict>(capacity = Channel.CONFLATED)

    /** Points the offscreen preloader at [target]; a no-op for the same one. */
    fun arm(target: Wallpaper) {
        mutableArmed.value = target
    }

    /** Reports what became of the preloader for [target]. */
    fun report(
        target: Wallpaper,
        displayed: Boolean,
    ) {
        verdicts.trySend(Verdict(target, displayed))
    }

    /** Disarms the preloader and forgets any verdict already delivered. */
    fun clear() {
        mutableArmed.value = null
        while (verdicts.tryReceive().isSuccess) { /* drain stale verdicts */ }
    }

    /**
     * Suspends until the preloader settles for [target]: true when it
     * painted, false when it failed or [timeoutMs] ran out — either way
     * the caller proceeds, only without the guarantee. Verdicts for other
     * wallpapers are skipped, so a late-arriving load never unlocks the
     * wrong page.
     */
    suspend fun await(
        target: Wallpaper,
        timeoutMs: Long,
    ): Boolean =
        withTimeoutOrNull(timeoutMs) {
            var displayed = false
            while (true) {
                val verdict = verdicts.receive()
                if (verdict.target == target) {
                    displayed = verdict.displayed
                    break
                }
            }
            displayed
        } ?: false

    private data class Verdict(
        val target: Wallpaper,
        val displayed: Boolean,
    )
}
