package com.cloudimage.feature.detail

import com.cloudimage.core.model.Wallpaper
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The readiness gate's contract (v1.0.24): a verdict for the target being
 * awaited unlocks the page, a verdict for anyone else does not, and a
 * preloader that stays silent only holds the swipe for the timeout. The
 * verdict may arrive before the await even starts — the drag's head start —
 * so the channel remembers the latest one.
 */
class ViewerPreloadGateTest {
    private fun wallpaper(id: String) =
        Wallpaper(
            id = id,
            providerId = "wallhaven",
            thumbUrl = "https://w.wallhaven.cc/thumb/$id.jpg",
            fullUrl = "https://w.wallhaven.cc/full/$id.png",
        )

    @Test
    fun awaitUnlocksWhenTheTargetPaints() =
        runTest {
            val gate = ViewerPreloadGate()
            val target = wallpaper("next")
            val await = async { gate.await(target, timeoutMs = 1_000) }

            gate.report(target, displayed = true)

            assertTrue(await.await())
        }

    @Test
    fun aFailedTargetUnlocksTheGateToo() =
        runTest {
            val gate = ViewerPreloadGate()
            val target = wallpaper("broken")
            val await = async { gate.await(target, timeoutMs = 1_000) }

            gate.report(target, displayed = false)

            assertFalse(await.await())
        }

    @Test
    fun verdictsForOtherTargetsAreSkipped() =
        runTest {
            val gate = ViewerPreloadGate()
            val target = wallpaper("next")
            val await = async { gate.await(target, timeoutMs = 1_000) }

            gate.report(wallpaper("stale"), displayed = true)
            gate.report(wallpaper("other"), displayed = true)
            gate.report(target, displayed = true)

            assertTrue(await.await())
        }

    @Test
    fun aSilentPreloaderOnlyHoldsThePageForTheTimeout() =
        runTest {
            val gate = ViewerPreloadGate()
            val target = wallpaper("slow")

            assertFalse(gate.await(target, timeoutMs = 500))
        }

    @Test
    fun aVerdictThatArrivesEarlyIsStillThereForTheAwait() =
        runTest {
            val gate = ViewerPreloadGate()
            val target = wallpaper("warmed")
            gate.arm(target)
            gate.report(target, displayed = true)

            assertTrue(gate.await(target, timeoutMs = 1_000))
        }

    @Test
    fun clearDisarmsTheWarmerAndForgetsVerdicts() =
        runTest {
            val gate = ViewerPreloadGate()
            val abandoned = wallpaper("abandoned")
            gate.arm(abandoned)
            gate.report(abandoned, displayed = true)

            gate.clear()

            assertNull(gate.armed.value)

            // The verdict delivered before the clear was drained with it:
            // re-arming the same target does not unlock its await — the
            // preloader has to report again.
            gate.arm(abandoned)
            assertFalse(gate.await(abandoned, timeoutMs = 100))
        }
}
