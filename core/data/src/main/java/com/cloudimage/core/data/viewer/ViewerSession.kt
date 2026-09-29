package com.cloudimage.core.data.viewer

import com.cloudimage.core.model.Wallpaper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The hand-off point for swipe-through navigation (v1.0.23).
 *
 * Every grid that opens the fullscreen viewer knows the list its tap came
 * from — the browse feed, a library tab, the "More like this" row. The
 * route argument can only carry one [Wallpaper] (the whole list would blow
 * the transaction limits), so the grid instead parks its list and the
 * tapped index here, right before navigating; the detail screen's ViewModel
 * then takes the frame and owns it for its lifetime.
 *
 * The split is deliberate: the session is a one-shot mailbox, not shared
 * state. Each detail screen snapshots what it was opened with, so pushing
 * a lookalike and coming back leaves the lower screen browsing its own
 * list — no cross-talk between stacked viewers — and a process death
 * simply means no swipe navigation until the next tap.
 */
@Singleton
class ViewerSession
    @Inject
    constructor() {
        /**
         * One list plus the position the user entered it at. The accessors
         * never throw on an empty list, so navigation can lean on them
         * without re-checking.
         */
        data class Frame(
            val wallpapers: List<Wallpaper>,
            val index: Int,
        ) {
            val current: Wallpaper? get() = wallpapers.getOrNull(index)

            val hasPrevious: Boolean get() = index > 0

            val hasNext: Boolean get() = index < wallpapers.lastIndex
        }

        private var pending: Frame? = null

        /** Parks the list a wallpaper is about to be opened from. */
        fun open(
            wallpapers: List<Wallpaper>,
            index: Int,
        ) {
            if (index in wallpapers.indices) {
                pending = Frame(wallpapers, index)
            }
        }

        /**
         * Hands the parked frame to the detail screen opening [wallpaper].
         * The frame must point at exactly that wallpaper — anything else
         * (a stale park, a deep link) yields null and the screen degrades
         * to the "More like this" fallback list.
         */
        fun take(wallpaper: Wallpaper): Frame? {
            val frame = pending
            pending = null
            return frame?.takeIf { it.current == wallpaper }
        }
    }
