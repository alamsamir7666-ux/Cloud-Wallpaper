package com.cloudimage.feature.detail

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * The one gesture a drag settles into (v1.0.23). The arbiter locks the
 * dominant drag axis once the finger crosses a small slop and then holds
 * that lock for the rest of the drag: a mostly-horizontal swipe can never
 * turn into a dismissal halfway through, and a mostly-downward swipe can
 * never page the gallery.
 */
internal enum class ViewerGesture {
    NONE,

    /** Dragging down — the dismiss follows at 1:1 while everything fades. */
    DISMISS,

    /** Dragging up — opens the details panel on release. */
    DETAILS,

    /** Dragging sideways — pages to the previous/next wallpaper on release. */
    HORIZONTAL,
}

/** What the viewer should do when the finger lifts. */
internal sealed interface ViewerRelease {
    /** Nothing crossed a threshold — everything springs back home. */
    data object RestorePosition : ViewerRelease

    /** The drag (or its fling) crossed the dismiss threshold — close the viewer. */
    data object DismissViewer : ViewerRelease

    /** The upward drag crossed the details threshold — open the info sheet. */
    data object ShowDetails : ViewerRelease

    /** A sideways swipe committed to paging: +1 is the next image, -1 the previous. */
    data class Navigate(
        val delta: Int,
    ) : ViewerRelease
}

/**
 * Pure gesture disambiguation for the fullscreen viewer (v1.0.23): fed raw
 * pointer positions, it decides which single gesture owns the drag and
 * what its release means. No Compose, no clocks — everything here is plain
 * arithmetic, which is what keeps the diagonal-swipe rules honest and
 * testable.
 *
 * The rules, per the product spec:
 *
 * - the axis that traveled further wins once the slop is behind, and the
 *   lock holds however the finger wanders afterwards;
 * - a vertical lock splits by sign — downward dismisses, upward opens
 *   details;
 * - a horizontal lock keeps both offsets stable: the image follows the
 *   finger sideways and never fades;
 * - releases decide by crossed distance OR fling velocity, and a refusal
 *   at the list's edge (no next/previous to go to) always springs back.
 */
internal class ViewerGestureArbiter(
    private val lockSlopPx: Float,
    private val dismissCommitPx: Float,
    private val dismissFlingPx: Float,
    private val detailsCommitPx: Float,
    private val detailsFlingPx: Float,
    private val navigateCommitPx: Float,
    private val navigateFlingPx: Float,
) {
    var gesture: ViewerGesture = ViewerGesture.NONE
        private set

    /** Total pointer travel since the drag began, per screen axis. */
    var totalX: Float = 0f
        private set

    var totalY: Float = 0f
        private set

    private var lastX = 0f
    private var lastY = 0f

    /** Starts a fresh drag at the given pointer position. */
    fun onDown(
        x: Float,
        y: Float,
    ) {
        gesture = ViewerGesture.NONE
        totalX = 0f
        totalY = 0f
        lastX = x
        lastY = y
    }

    /**
     * Feeds one pointer move and returns the gesture that owns it. The
     * lock is taken the first time either axis clears [lockSlopPx] and is
     * never revised: whatever the finger does afterwards, the drag stays
     * on its axis.
     */
    fun onMove(
        x: Float,
        y: Float,
    ): ViewerGesture {
        totalX += x - lastX
        totalY += y - lastY
        lastX = x
        lastY = y
        if (gesture == ViewerGesture.NONE && max(abs(totalX), abs(totalY)) >= lockSlopPx) {
            gesture =
                if (abs(totalY) > abs(totalX)) {
                    if (totalY > 0f) ViewerGesture.DISMISS else ViewerGesture.DETAILS
                } else {
                    ViewerGesture.HORIZONTAL
                }
        }
        return gesture
    }

    /** What the release of this drag means, given the end-of-drag fling velocity. */
    fun onUp(
        velocityX: Float,
        velocityY: Float,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): ViewerRelease =
        when (gesture) {
            ViewerGesture.DISMISS ->
                if (max(0f, totalY) >= dismissCommitPx || velocityY >= dismissFlingPx) {
                    ViewerRelease.DismissViewer
                } else {
                    ViewerRelease.RestorePosition
                }

            ViewerGesture.DETAILS ->
                if (-totalY >= detailsCommitPx || velocityY <= -detailsFlingPx) {
                    ViewerRelease.ShowDetails
                } else {
                    ViewerRelease.RestorePosition
                }

            ViewerGesture.HORIZONTAL ->
                when {
                    velocityX <= -navigateFlingPx && hasNext -> ViewerRelease.Navigate(+1)

                    velocityX >= navigateFlingPx && hasPrevious -> ViewerRelease.Navigate(-1)

                    totalX <= -navigateCommitPx && hasNext -> ViewerRelease.Navigate(+1)

                    totalX >= navigateCommitPx && hasPrevious -> ViewerRelease.Navigate(-1)

                    else -> ViewerRelease.RestorePosition
                }

            ViewerGesture.NONE -> ViewerRelease.RestorePosition
        }

    /**
     * The image's horizontal follow for the current drag: 1:1 with the
     * finger, rubber-banded to a quarter speed once the swipe points past
     * the list's end so edges push back instead of dragging into nothing.
     */
    fun followX(
        hasNext: Boolean,
        hasPrevious: Boolean,
    ): Float =
        if (gesture != ViewerGesture.HORIZONTAL) {
            0f
        } else {
            when {
                totalX < 0f && !hasNext -> totalX * EDGE_RESISTANCE

                totalX > 0f && !hasPrevious -> totalX * EDGE_RESISTANCE

                else -> totalX
            }
        }

    /**
     * The image's vertical follow. A dismiss drags the image down 1:1 and
     * never up; a details drag nudges the image up at [detailsFollowFactor]
     * of the finger's speed, capped at [detailsNudgeCapPx] — feedback, not
     * a second dismiss.
     */
    fun followY(
        detailsNudgeCapPx: Float,
        detailsFollowFactor: Float = DETAILS_FOLLOW_FACTOR,
    ): Float =
        when (gesture) {
            ViewerGesture.DISMISS -> max(0f, totalY)

            ViewerGesture.DETAILS -> (totalY * detailsFollowFactor).coerceIn(-detailsNudgeCapPx, 0f)

            else -> 0f
        }

    private companion object {
        /** How much of an edge-pointing swipe the image actually follows. */
        const val EDGE_RESISTANCE = 0.25f
    }
}

/**
 * The viewer's live motion (v1.0.23): the drag offsets the image renders
 * with, the fades derived from the dismiss drag, and the settle/commit
 * animations that run on release. The state survives in-place wallpaper
 * swaps — only the zoomable image inside re-keys — so a committed swipe can
 * animate its exit, swap the wallpaper, and slide the next image in
 * without the motion dying mid-flight.
 *
 * Offsets are read during the draw phase (graphicsLayer blocks) so the
 * drag itself never recomposes; the dismiss progress drives the image's
 * alpha, the scrim's fade toward transparent and the chrome fade alike.
 */
internal class ViewerMotionState(
    private val scope: CoroutineScope,
) {
    /** Horizontal image offset in px: the sideways page drag. */
    val offsetX = mutableFloatStateOf(0f)

    /** Vertical image offset in px: downward for dismiss, capped-up for details. */
    val offsetY = mutableFloatStateOf(0f)

    /** True while a settle/commit animation owns the offsets — drags stand by. */
    val animating = mutableStateOf(false)

    /** The restore in flight, if any — see [animateRestore]. */
    private var restoreJob: Job? = null

    var viewportWidthPx: Float = 1f
        private set

    var viewportHeightPx: Float = 1f
        private set

    /** How far down the dismiss drag must reach before release commits it. */
    var dismissDistancePx: Float = 1f
        private set

    /** Refreshes the geometry when the viewer lays out or rotates. */
    fun updateViewport(
        widthPx: Float,
        heightPx: Float,
    ) {
        viewportWidthPx = widthPx
        viewportHeightPx = heightPx
        dismissDistancePx = heightPx * DISMISS_COMMIT_FRACTION
    }

    /** 0..1 across the dismiss drag — image, scrim and chrome fade with it. */
    val dismissProgress: Float
        get() = (offsetY.floatValue / dismissDistancePx).coerceIn(0f, 1f)

    /** The image fades out in lockstep with the downward drag. */
    val imageAlpha: Float
        get() = 1f - dismissProgress

    /** Clears stale offsets when a new drag begins from rest. */
    fun snapIfIdle() {
        if (!animating.value) {
            offsetX.floatValue = 0f
            offsetY.floatValue = 0f
        }
    }

    /**
     * Springs both offsets home — the release-below-threshold settle.
     *
     * Single flight (v1.0.25): the stand-downs that call this used to
     * spawn a fresh spring per pointer move whenever the zoomable had
     * claimed a drag, and the interleaved per-frame writes to the same
     * offsets read as the image shivering along its drag axis. One
     * restore owns the offsets at a time; a newer one replaces its
     * predecessor mid-flight, and a superseded restore never clears
     * [animating] on the way out.
     */
    fun animateRestore() {
        restoreJob?.cancel()
        restoreJob =
            scope.launch {
                animating.value = true
                try {
                    coroutineScope {
                        if (offsetX.floatValue != 0f) {
                            launch { animateToZero(offsetX) }
                        }
                        if (offsetY.floatValue != 0f) {
                            launch { animateToZero(offsetY) }
                        }
                    }
                } finally {
                    if (restoreJob == coroutineContext[Job]) {
                        animating.value = false
                    }
                }
            }
    }

    /**
     * Continues the dismiss to a full close: the image travels the rest of
     * the screen's height while the fade finishes; [onDone] fires (and pops
     * the viewer) only after the animation settles.
     */
    fun animateDismiss(onDone: () -> Unit) {
        restoreJob?.cancel()
        restoreJob = null
        scope.launch {
            animating.value = true
            try {
                animate(
                    initialValue = offsetY.floatValue,
                    targetValue = viewportHeightPx,
                    animationSpec = tween(durationMillis = DISMISS_EXIT_MS),
                ) { value, _ -> offsetY.floatValue = value }
                onDone()
            } finally {
                animating.value = false
            }
        }
    }

    /**
     * Pages to the neighbor [delta] points at: the incoming image first
     * gets [awaitReady] to warm up offscreen (v1.0.24) — the release holds
     * the image at its dragged offset until the neighbor can paint, so the
     * swap never flashes a blank — then the current image exits in the
     * swipe's direction, the state swaps the instant it is gone, and the
     * next image slides in from the opposite side.
     */
    fun animateNavigate(
        delta: Int,
        onSwap: () -> Unit,
        awaitReady: (suspend () -> Unit)? = null,
    ) {
        restoreJob?.cancel()
        restoreJob = null
        scope.launch {
            animating.value = true
            try {
                awaitReady?.invoke()
                animate(
                    initialValue = offsetX.floatValue,
                    targetValue = -delta * viewportWidthPx,
                    animationSpec = tween(durationMillis = NAVIGATE_EXIT_MS),
                ) { value, _ -> offsetX.floatValue = value }
                onSwap()
                offsetX.floatValue = delta * viewportWidthPx * NAVIGATE_ENTER_FRACTION
                animate(
                    initialValue = offsetX.floatValue,
                    targetValue = 0f,
                    animationSpec =
                        spring(
                            // No overshoot (v1.0.25): a bouncy landing
                            // read as a horizontal shiver at the tail end
                            // of every committed swipe. The page slides in
                            // briskly and stops where it means to.
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                ) { value, _ -> offsetX.floatValue = value }
            } finally {
                animating.value = false
            }
        }
    }

    private suspend fun animateToZero(offset: MutableFloatState) {
        animate(
            initialValue = offset.floatValue,
            targetValue = 0f,
            animationSpec =
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
        ) { value, _ -> offset.floatValue = value }
    }

    private companion object {
        /** Screen-height fraction past which a released dismiss commits. */
        const val DISMISS_COMMIT_FRACTION = 0.35f

        /** How far the exit half of a committed page swipe travels. */
        const val NAVIGATE_ENTER_FRACTION = 0.30f

        const val DISMISS_EXIT_MS = 220

        const val NAVIGATE_EXIT_MS = 160
    }
}

/** The details nudge follows the finger at half speed, like the pill it mirrors. */
internal const val DETAILS_FOLLOW_FACTOR = 0.5f
