package com.cloudimage.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The vertical gesture arbiter's contract, straight from the product spec:
 *
 * - past a small slop, a drag whose vertical travel dominates locks to
 *   dismiss (down) or details (up) by sign and never changes its mind,
 *   however far the finger wanders afterwards;
 * - a drag whose horizontal travel dominates yields the whole gesture —
 *   the pager owns sideways, and nothing here writes another offset;
 * - releases decide by crossed distance OR fling velocity;
 * - the dismiss follow is 1:1 with the finger from the lock point
 *   onward, continuous with a mid-flight baseline, and never negative;
 * - the details follow is half-speed, capped, and never positive;
 * - a resting finger's sub-pixel noise never moves the applied follow.
 */
class ViewerGestureArbiterTest {
    private val arbiter =
        VerticalDragArbiter(
            lockSlopPx = 30f,
            dismissCommitPx = 800f,
            dismissFlingPx = 3000f,
            detailsCommitPx = 180f,
            detailsFlingPx = 1200f,
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
        assertEquals(VerticalGesture.DISMISS, arbiter.gesture)
    }

    @Test
    fun anUpwardDragLocksToDetails() {
        drag(dx = 5f, dy = -60f)
        assertEquals(VerticalGesture.DETAILS, arbiter.gesture)
    }

    @Test
    fun aSidewaysDragYieldsToThePager() {
        drag(dx = 60f, dy = 5f)
        assertEquals(VerticalGesture.YIELDED, arbiter.gesture)
        // A yielded release never acts, however far or fast it ended.
        assertEquals(VerticalRelease.RestorePosition, arbiter.onUp(velocityY = 4000f))
    }

    @Test
    fun noLockHappensBeforeTheSlop() {
        drag(dx = 8f, dy = 12f)
        assertEquals(VerticalGesture.NONE, arbiter.gesture)
        // And a release that early never acts, however fast the fling.
        assertEquals(
            VerticalRelease.RestorePosition,
            arbiter.onUp(velocityY = 4000f),
        )
    }

    @Test
    fun aDismissLockSurvivesTheFingerCurvingSideways() {
        drag(dx = 5f, dy = 60f)
        // The finger now travels far horizontally — the lock holds.
        arbiter.onMove(x = 100f + 5f + 200f, y = 100f + 60f)
        assertEquals(VerticalGesture.DISMISS, arbiter.gesture)
    }

    @Test
    fun aYieldSurvivesTheFingerPlungingDownward() {
        drag(dx = 60f, dy = 5f)
        // The finger now plunges downward — still the pager's gesture.
        arbiter.onMove(x = 100f + 60f, y = 100f + 5f + 400f)
        assertEquals(VerticalGesture.YIELDED, arbiter.gesture)
    }

    @Test
    fun aDismissBelowTheThresholdSpringsBack() {
        drag(dx = 5f, dy = 500f)
        assertEquals(VerticalRelease.RestorePosition, arbiter.onUp(velocityY = 100f))
    }

    @Test
    fun aDismissPastTheThresholdCommits() {
        drag(dx = 5f, dy = 900f)
        assertEquals(VerticalRelease.DismissViewer, arbiter.onUp(velocityY = 0f))
    }

    @Test
    fun aFastDownwardFlingDismissesWithoutTheDistance() {
        drag(dx = 5f, dy = 120f)
        assertEquals(VerticalRelease.DismissViewer, arbiter.onUp(velocityY = 4000f))
    }

    @Test
    fun anUpwardDragPastTheThresholdOpensDetails() {
        drag(dx = 5f, dy = -200f)
        assertEquals(VerticalRelease.ShowDetails, arbiter.onUp(velocityY = 0f))
    }

    @Test
    fun aFastUpwardFlingOpensDetailsWithoutTheDistance() {
        drag(dx = 5f, dy = -40f)
        assertEquals(VerticalRelease.ShowDetails, arbiter.onUp(velocityY = -2000f))
    }

    @Test
    fun aShortUpwardDragSpringsBack() {
        drag(dx = 5f, dy = -100f)
        assertEquals(VerticalRelease.RestorePosition, arbiter.onUp(velocityY = 0f))
    }

    @Test
    fun theDismissFollowTracksTheFingerOneToOneFromTheLock() {
        drag(dx = 0f, dy = 60f)
        // The follow counts from the lock point (slop 30), not the down:
        // the move that crossed the slop is where tracking begins, so the
        // image never jumps by the slop's worth of travel.
        assertEquals(0f, arbiter.followY(currentOffsetY = 0f, detailsNudgeCapPx = 80f))
        arbiter.onMove(x = 100f, y = 100f + 160f)
        // A further 100px of finger travel moves the image exactly 100px.
        assertEquals(100f, arbiter.followY(currentOffsetY = 0f, detailsNudgeCapPx = 80f))
    }

    @Test
    fun theDismissFollowContinuesFromAMidFlightBaselineWithoutJumping() {
        // A drag grabs the image while a settle animation still holds it
        // 80px down: the first locked write latches that as the baseline
        // and the follow continues from it — no snap, no jump.
        drag(dx = 0f, dy = 60f)
        assertEquals(80f, arbiter.followY(currentOffsetY = 80f, detailsNudgeCapPx = 80f))
        arbiter.onMove(x = 100f, y = 100f + 260f)
        // The baseline (80) plus the 200px of travel since the lock.
        assertEquals(280f, arbiter.followY(currentOffsetY = 80f, detailsNudgeCapPx = 80f))
    }

    @Test
    fun theDismissFollowNeverLiftsTheImageAboveItsRestingSpot() {
        drag(dx = 0f, dy = 60f)
        arbiter.onMove(x = 100f, y = 100f - 400f)
        assertEquals(0f, arbiter.followY(currentOffsetY = 0f, detailsNudgeCapPx = 80f))
    }

    @Test
    fun theDetailsFollowIsHalfSpeedAndCapped() {
        drag(dx = 0f, dy = -60f)
        // The move that crossed the slop is where tracking begins.
        assertEquals(0f, arbiter.followY(currentOffsetY = 0f, detailsNudgeCapPx = 80f))
        // A further 940px up at half speed would be -470 — the cap holds.
        arbiter.onMove(x = 100f, y = 100f - 1000f)
        assertEquals(-80f, arbiter.followY(currentOffsetY = 0f, detailsNudgeCapPx = 80f))
        // Drifting back down releases the cap at half speed…
        arbiter.onMove(x = 100f, y = 100f - 160f)
        assertEquals(-50f, arbiter.followY(currentOffsetY = -80f, detailsNudgeCapPx = 80f))
        // …and the follow never lifts the image above its resting spot.
        arbiter.onMove(x = 100f, y = 100f - 20f)
        assertEquals(0f, arbiter.followY(currentOffsetY = -50f, detailsNudgeCapPx = 80f))
    }

    @Test
    fun aYieldedOrUnlockedDragWritesNoFollow() {
        drag(dx = 60f, dy = 5f)
        assertEquals(42f, arbiter.followY(currentOffsetY = 42f, detailsNudgeCapPx = 80f))
        drag(dx = 5f, dy = 8f)
        assertEquals(42f, arbiter.followY(currentOffsetY = 42f, detailsNudgeCapPx = 80f))
    }

    @Test
    fun aRestingFingersNoiseNeverMovesTheAppliedFollow() {
        drag(dx = 0f, dy = 60f)
        val settled = arbiter.followY(currentOffsetY = 0f, detailsNudgeCapPx = 80f)
        // The finger trembles within the 3px stillness band…
        arbiter.onMove(x = 100f, y = 100f + 60.5f)
        arbiter.onMove(x = 100f, y = 100f + 59.5f)
        arbiter.onMove(x = 100f, y = 100f + 61f)
        arbiter.onMove(x = 100f, y = 100f + 60f)
        // …and the applied follow stays exactly where it was.
        assertEquals(settled, arbiter.followY(currentOffsetY = settled, detailsNudgeCapPx = 80f))
        // Genuine travel still crosses the band.
        arbiter.onMove(x = 100f, y = 100f + 90f)
        assertEquals(
            settled + 30f,
            arbiter.followY(currentOffsetY = settled, detailsNudgeCapPx = 80f),
        )
    }

    @Test
    fun aFreshDragResetsTheArbiter() {
        drag(dx = 0f, dy = 900f)
        assertEquals(VerticalRelease.DismissViewer, arbiter.onUp(velocityY = 0f))
        drag(dx = 0f, dy = -200f)
        assertEquals(VerticalGesture.DETAILS, arbiter.gesture)
        assertEquals(VerticalRelease.ShowDetails, arbiter.onUp(velocityY = 0f))
    }
}
