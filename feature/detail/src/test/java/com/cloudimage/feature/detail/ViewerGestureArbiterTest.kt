package com.cloudimage.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gesture arbiter's contract, straight from the product spec (v1.0.23):
 *
 * - one gesture per drag, chosen by the dominant axis once a small slop is
 *   behind and never revised — a mostly-horizontal swipe never dismisses
 *   and a mostly-downward one never pages, however the finger wanders;
 * - releases decide by crossed distance OR fling velocity;
 * - a swipe pointing past the list's edge always springs back.
 */
class ViewerGestureArbiterTest {
    private val arbiter =
        ViewerGestureArbiter(
            lockSlopPx = 30f,
            dismissCommitPx = 800f,
            dismissFlingPx = 3000f,
            detailsCommitPx = 180f,
            detailsFlingPx = 1200f,
            navigateCommitPx = 350f,
            navigateFlingPx = 2000f,
        )

    /** Feeds a drag as absolute positions, the way the pointer layer does. */
    private fun drag(
        dx: Float,
        dy: Float,
    ) {
        arbiter.onDown(x = 100f, y = 100f)
        arbiter.onMove(x = 100f + dx, y = 100f + dy)
    }

    @Test
    fun aDownwardDragLocksToDismiss() {
        drag(dx = 5f, dy = 60f)
        assertEquals(ViewerGesture.DISMISS, arbiter.gesture)
    }

    @Test
    fun anUpwardDragLocksToDetails() {
        drag(dx = 5f, dy = -60f)
        assertEquals(ViewerGesture.DETAILS, arbiter.gesture)
    }

    @Test
    fun aSidewaysDragLocksToHorizontal() {
        drag(dx = 60f, dy = 5f)
        assertEquals(ViewerGesture.HORIZONTAL, arbiter.gesture)
    }

    @Test
    fun noLockHappensBeforeTheSlop() {
        drag(dx = 8f, dy = 12f)
        assertEquals(ViewerGesture.NONE, arbiter.gesture)
        // And a release that early never acts, however fast the fling.
        assertEquals(
            ViewerRelease.RestorePosition,
            arbiter.onUp(velocityX = 4000f, velocityY = 4000f, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun aDismissLockSurvivesTheFingerCurvingSideways() {
        drag(dx = 5f, dy = 60f)
        // The finger now travels far horizontally — the lock holds.
        arbiter.onMove(x = 100f + 5f + 200f, y = 100f + 60f)
        assertEquals(ViewerGesture.DISMISS, arbiter.gesture)
    }

    @Test
    fun aHorizontalLockSurvivesTheFingerCurvingDownward() {
        drag(dx = 60f, dy = 5f)
        // The finger now plunges downward — still a page swipe, not a dismiss.
        arbiter.onMove(x = 100f + 60f, y = 100f + 5f + 400f)
        assertEquals(ViewerGesture.HORIZONTAL, arbiter.gesture)
        // The follow keeps the vertical channel quiet, too.
        assertEquals(0f, arbiter.followY(detailsNudgeCapPx = 80f))
    }

    @Test
    fun dismissFollowsTheFingerOneToOneAndNeverUpward() {
        drag(dx = 0f, dy = 300f)
        assertEquals(300f, arbiter.followY(detailsNudgeCapPx = 80f))
        // Dragging back up past the start clamps at rest, not above it.
        arbiter.onMove(x = 100f, y = 100f - 100f)
        assertEquals(0f, arbiter.followY(detailsNudgeCapPx = 80f))
    }

    @Test
    fun dismissCommitsPastTheDistanceThreshold() {
        drag(dx = 0f, dy = 900f)
        assertEquals(
            ViewerRelease.DismissViewer,
            arbiter.onUp(velocityX = 0f, velocityY = 0f, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun dismissCommitsOnAFastDownwardFling() {
        drag(dx = 0f, dy = 100f)
        assertEquals(
            ViewerRelease.DismissViewer,
            arbiter.onUp(velocityX = 0f, velocityY = 3500f, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun dismissSpringsBackBelowBothThresholds() {
        drag(dx = 0f, dy = 100f)
        assertEquals(
            ViewerRelease.RestorePosition,
            arbiter.onUp(velocityX = 0f, velocityY = 500f, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun detailsOpenPastTheDistanceThreshold() {
        drag(dx = 0f, dy = -200f)
        assertEquals(
            ViewerRelease.ShowDetails,
            arbiter.onUp(velocityX = 0f, velocityY = 0f, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun detailsOpenOnAFastUpwardFling() {
        drag(dx = 0f, dy = -50f)
        assertEquals(
            ViewerRelease.ShowDetails,
            arbiter.onUp(velocityX = 0f, velocityY = -1300f, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun detailsNudgeFollowsAtHalfSpeedAndIsCapped() {
        drag(dx = 0f, dy = -40f)
        assertEquals(-20f, arbiter.followY(detailsNudgeCapPx = 80f))
        // Far beyond the cap: the image waits, the sheet does not.
        drag(dx = 0f, dy = -500f)
        assertEquals(-80f, arbiter.followY(detailsNudgeCapPx = 80f))
    }

    @Test
    fun aLeftSwipePastTheDistancePagesForward() {
        drag(dx = -400f, dy = 10f)
        assertEquals(
            ViewerRelease.Navigate(+1),
            arbiter.onUp(velocityX = 0f, velocityY = 0f, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun aRightSwipePastTheDistancePagesBack() {
        drag(dx = 400f, dy = 10f)
        assertEquals(
            ViewerRelease.Navigate(-1),
            arbiter.onUp(velocityX = 0f, velocityY = 0f, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun theFlingDirectionOutranksTheTravel() {
        drag(dx = -50f, dy = 0f)
        assertEquals(
            ViewerRelease.Navigate(-1),
            arbiter.onUp(velocityX = 2500f, velocityY = 0f, hasPrevious = true, hasNext = true),
        )
        assertEquals(
            ViewerRelease.Navigate(+1),
            arbiter.onUp(velocityX = -2500f, velocityY = 0f, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun aSwipePointingPastTheListEdgeSpringsBack() {
        // No next image: a committed left swipe refuses to page.
        drag(dx = -400f, dy = 10f)
        assertEquals(
            ViewerRelease.RestorePosition,
            arbiter.onUp(velocityX = 0f, velocityY = 0f, hasPrevious = true, hasNext = false),
        )
        // No previous image: a committed right swipe likewise.
        drag(dx = 400f, dy = 10f)
        assertEquals(
            ViewerRelease.RestorePosition,
            arbiter.onUp(velocityX = 0f, velocityY = 0f, hasPrevious = false, hasNext = true),
        )
        // And the edge-bound fling refuses as well.
        drag(dx = -50f, dy = 0f)
        assertEquals(
            ViewerRelease.RestorePosition,
            arbiter.onUp(velocityX = -2500f, velocityY = 0f, hasPrevious = true, hasNext = false),
        )
    }

    @Test
    fun aShortSlowSidewaysDragSpringsBack() {
        drag(dx = -100f, dy = 10f)
        assertEquals(
            ViewerRelease.RestorePosition,
            arbiter.onUp(velocityX = 0f, velocityY = 0f, hasPrevious = true, hasNext = true),
        )
    }

    @Test
    fun theSidewaysFollowIsOneToOneWithANeighbor() {
        drag(dx = -200f, dy = 0f)
        assertEquals(-200f, arbiter.followX(hasNext = true, hasPrevious = true))
    }

    @Test
    fun theSidewaysFollowRubberBandsPastTheListEdge() {
        drag(dx = -200f, dy = 0f)
        assertEquals(-50f, arbiter.followX(hasNext = false, hasPrevious = true))
        drag(dx = 200f, dy = 0f)
        assertEquals(50f, arbiter.followX(hasNext = true, hasPrevious = false))
    }

    @Test
    fun aVerticalLockKeepsTheHorizontalChannelQuiet() {
        drag(dx = 0f, dy = 300f)
        assertEquals(0f, arbiter.followX(hasNext = true, hasPrevious = true))
    }
}
