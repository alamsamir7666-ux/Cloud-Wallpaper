package com.cloudimage.feature.detail

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * The one vertical gesture a drag settles into. Sideways is deliberately
 * absent: the pager owns horizontal movement natively, so this arbiter
 * only ever arbitrates the vertical axis and [YIELDS] the whole gesture
 * to the pager the moment a drag leans sideways past the slop.
 */
internal enum class VerticalGesture {
    NONE,

    /** Dragging down — the dismiss follows at 1:1 while everything fades. */
    DISMISS,

    /** Dragging up — opens the details panel on release. */
    DETAILS,

    /** The drag leaned sideways — the pager owns it for the rest of the gesture. */
    YIELDED,
}

/** What the viewer should do when the finger lifts. */
internal sealed interface VerticalRelease {
    /** Nothing crossed a threshold — everything springs back home. */
    data object RestorePosition : VerticalRelease

    /** The drag (or its fling) crossed the dismiss threshold — close the viewer. */
    data object DismissViewer : VerticalRelease

    /** The upward drag crossed the details threshold — open the info sheet. */
    data object ShowDetails : VerticalRelease
}

/**
 * Pure vertical-gesture disambiguation for the fullscreen viewer: fed raw
 * pointer positions, it decides whether a drag is a dismiss, a details
 * swipe, or somebody else's problem. No Compose, no clocks — plain
 * arithmetic, which is what keeps the rules honest and testable.
 *
 * The rules, per the product spec:
 *
 * - past a small slop, a drag whose vertical travel dominates locks to
 *   [DISMISS] or [DETAILS] by sign and never changes its mind; a drag
 *   whose horizontal travel dominates [YIELDS] permanently — the pager
 *   takes over and nothing here writes another offset;
 * - releases decide by crossed distance OR fling velocity;
 * - the dismiss follow is 1:1 with the finger from the lock point
 *   onward, never negative (the image never rises above its resting
 *   spot inside a downward gesture) and never scaled, skewed or resized;
 * - the details follow nudges the image up at half the finger's speed,
 *   capped — feedback, not a second dismiss;
 * - follows are written through a stillness band: a resting finger's
 *   digitizer keeps reporting sub-pixel and single-pixel noise for as
 *   long as it rests, and a follow fed straight through would translate
 *   that noise into a whole-pixel shiver along the drag axis. The band
 *   is far below anything a deliberate drag can feel, so only stillness
 *   is filtered out, never travel.
 */
internal class VerticalDragArbiter(
    private val lockSlopPx: Float,
    private val dismissCommitPx: Float,
    private val dismissFlingPx: Float,
    private val detailsCommitPx: Float,
    private val detailsFlingPx: Float,
    /** Wander below this leaves the applied follow where it was. */
    private val stillnessPx: Float = STILLNESS_PX,
) {
    var gesture: VerticalGesture = VerticalGesture.NONE
        private set

    /** Total pointer travel since the drag began, per screen axis. */
    var totalX: Float = 0f
        private set

    var totalY: Float = 0f
        private set

    private var lastX = 0f
    private var lastY = 0f

    /** Travel at the moment the lock was taken — the follow's zero point. */
    private var totalYAtLock = 0f

    /**
     * The offset the image rendered with when the lock began. The first
     * [followY] after a lock latches the caller's current offset as the
     * baseline, so a drag that grabs the image while a settle animation
     * is still in flight continues from exactly where the image is — no
     * snap, no jump, one writer.
     */
    private var baselineY: Float? = null

    /** The follow the image last rendered with — the stillness anchor. */
    private var appliedFollowY = 0f

    /** Starts a fresh drag at the given pointer position. */
    fun onDown(
        x: Float,
        y: Float,
    ) {
        gesture = VerticalGesture.NONE
        totalX = 0f
        totalY = 0f
        lastX = x
        lastY = y
        totalYAtLock = 0f
        baselineY = null
        appliedFollowY = 0f
    }

    /**
     * Feeds one pointer move and returns the gesture that owns it. The
     * lock is taken the first time either axis clears [lockSlopPx] and
     * is never revised: a mostly-downward swipe stays a dismiss however
     * far the finger wanders sideways afterwards, and a mostly-sideways
     * one stays yielded however far it later plunges.
     */
    fun onMove(
        x: Float,
        y: Float,
    ): VerticalGesture {
        totalX += x - lastX
        totalY += y - lastY
        lastX = x
        lastY = y
        if (gesture == VerticalGesture.NONE && max(abs(totalX), abs(totalY)) >= lockSlopPx) {
            totalYAtLock = totalY
            gesture =
                when {
                    abs(totalY) > abs(totalX) -> if (totalY > 0f) VerticalGesture.DISMISS else VerticalGesture.DETAILS
                    else -> VerticalGesture.YIELDED
                }
        }
        return gesture
    }

    /** What the release of this drag means, given the end-of-drag fling velocity. */
    fun onUp(velocityY: Float): VerticalRelease =
        when (gesture) {
            VerticalGesture.DISMISS ->
                if (max(0f, totalY) >= dismissCommitPx || velocityY >= dismissFlingPx) {
                    VerticalRelease.DismissViewer
                } else {
                    VerticalRelease.RestorePosition
                }

            VerticalGesture.DETAILS ->
                if (-totalY >= detailsCommitPx || velocityY <= -detailsFlingPx) {
                    VerticalRelease.ShowDetails
                } else {
                    VerticalRelease.RestorePosition
                }

            VerticalGesture.YIELDED, VerticalGesture.NONE -> VerticalRelease.RestorePosition
        }

    /**
     * The image's vertical follow for the current drag, continuous with
     * [currentOffsetY] at the lock instant: a dismiss drags the image
     * down 1:1 with the finger from the lock point and never above its
     * resting spot; a details drag nudges it up at
     * [DETAILS_FOLLOW_FACTOR] of the finger's speed, capped at
     * [detailsNudgeCapPx]. Both are written through the stillness band
     * so a resting finger's noise never moves the image.
     */
    fun followY(
        currentOffsetY: Float,
        detailsNudgeCapPx: Float,
    ): Float {
        val base = baselineY ?: currentOffsetY.also { baselineY = it }
        val raw =
            when (gesture) {
                VerticalGesture.DISMISS -> (base + totalY - totalYAtLock).coerceAtLeast(0f)

                VerticalGesture.DETAILS ->
                    (base + (totalY - totalYAtLock) * DETAILS_FOLLOW_FACTOR)
                        .coerceIn(-detailsNudgeCapPx, 0f)

                else -> return currentOffsetY
            }
        return raw.settledInto(appliedFollowY).also { appliedFollowY = it }
    }

    /**
     * The stillness band: raw wander that stayed within [stillnessPx] of
     * the applied value leaves the applied value alone. Only genuine
     * travel — a drag, not a resting finger's noise — crosses it.
     */
    private fun Float.settledInto(applied: Float): Float = if (abs(this - applied) < stillnessPx) applied else this

    private companion object {
        /** Digitizer noise ceiling while a finger rests, in pixels. */
        const val STILLNESS_PX = 3f
    }
}

/**
 * The viewer's live vertical motion: the drag offset the image renders
 * with, the fades derived from the dismiss drag, and the settle/commit
 * animations that run on release. One axis, one writer at a time — the
 * drag writes while the finger is down, an animation writes after the
 * release, and a drag that begins mid-settle latches onto the animated
 * value instead of fighting it (see [VerticalDragArbiter.followY]).
 *
 * Offsets are read outside composition — the layout-phase offset block
 * and the draw-phase fades alike — so the drag itself never recomposes.
 * The drag moves the image through the LAYOUT on whole pixels (flick's
 * own discipline): a sub-pixel graphicsLayer translation would re-sample
 * telephoto's sub-sampled tiles at a fresh fractional position every
 * frame, which reads as a faint shimmer along the drag axis. The fade
 * stays a draw-phase concern. Nothing here ever scales, skews or resizes
 * the image — translation and alpha are the only two effects.
 */
internal class ViewerMotionState(
    private val scope: CoroutineScope,
) {
    /** Vertical image offset in px: downward for dismiss, capped-up for details. */
    val offsetY = mutableFloatStateOf(0f)

    /** True while the dismiss exit owns the screen — new drags stand down. */
    val dismissing = mutableStateOf(false)

    /** The restore in flight, if any. */
    private var restoreJob: Job? = null

    var viewportHeightPx: Float = 1f
        private set

    /** How far down the dismiss drag must reach before release commits it. */
    var dismissDistancePx: Float = 1f
        private set

    /** Where the fade bottoms out — past the commit point, not at it. */
    private var fadeDistancePx = 1f

    /** Refreshes the geometry when the viewer lays out or rotates. */
    fun updateViewport(
        widthPx: Float,
        heightPx: Float,
    ) {
        viewportHeightPx = heightPx
        dismissDistancePx = heightPx * DISMISS_COMMIT_FRACTION
        fadeDistancePx = dismissDistancePx * FADE_SPAN_FACTOR
    }

    /** 0..1 across the dismiss drag up to the commit point — the chrome's fade. */
    val dismissProgress: Float
        get() = (offsetY.floatValue / dismissDistancePx).coerceIn(0f, 1f)

    /**
     * The image, scrim and chrome fade in lockstep with the downward
     * drag, bottoming out past the commit point instead of at it: at the
     * threshold the image is still half-visible, and the exit animation
     * finishes the fade as it carries the image off screen.
     */
    val contentAlpha: Float
        get() = (1f - offsetY.floatValue / fadeDistancePx).coerceIn(0f, 1f)

    /** Stops the settle animation so a fresh drag can take the offset over. */
    fun cancelAnimations() {
        restoreJob?.cancel()
        restoreJob = null
    }

    /**
     * Springs the offset home — the release-below-threshold settle.
     * Single flight: a newer restore replaces its predecessor mid-flight
     * and the superseded one never writes again, so the offset always
     * has exactly one writer.
     */
    fun animateRestore() {
        restoreJob?.cancel()
        restoreJob =
            scope.launch {
                animate(
                    initialValue = offsetY.floatValue,
                    targetValue = 0f,
                    animationSpec =
                        spring(
                            // Critically damped: a bouncy landing would read
                            // as a vertical shiver at the tail of every
                            // abandoned drag.
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMedium,
                        ),
                ) { value, _ -> offsetY.floatValue = value }
            }
    }

    /**
     * Continues the dismiss to a full close: the image travels the rest
     * of the screen's height while the fade finishes; [onDone] fires
     * (and pops the viewer) only after the animation settles.
     */
    fun animateDismiss(onDone: () -> Unit) {
        restoreJob?.cancel()
        restoreJob = null
        dismissing.value = true
        scope.launch {
            try {
                animate(
                    initialValue = offsetY.floatValue,
                    targetValue = viewportHeightPx,
                    animationSpec =
                        tween(
                            durationMillis = DISMISS_EXIT_MS,
                            delayMillis = 0,
                        ),
                ) { value, _ -> offsetY.floatValue = value }
                onDone()
            } finally {
                dismissing.value = false
            }
        }
    }

    private companion object {
        /** Screen-height fraction past which a released dismiss commits. */
        const val DISMISS_COMMIT_FRACTION = 0.35f

        /** The fade spans this multiple of the commit distance. */
        const val FADE_SPAN_FACTOR = 2f

        const val DISMISS_EXIT_MS = 220
    }
}

/** The details nudge follows the finger at half speed, like the pill it mirrors. */
internal const val DETAILS_FOLLOW_FACTOR = 0.5f
