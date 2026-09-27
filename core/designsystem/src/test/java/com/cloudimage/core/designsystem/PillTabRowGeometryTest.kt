package com.cloudimage.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The swipe-sync math behind [PillTabRow], exercised as pure functions:
 * the pill interpolates left AND width between slots with the pager
 * fraction, clamps at the ends, and the bar's keep-in-view nudge only
 * fires when the pill actually crowds an edge.
 */
class PillTabRowGeometryTest {
    private val slots =
        listOf(
            TabSlot(left = 16f, width = 60f),
            TabSlot(left = 84f, width = 80f),
            TabSlot(left = 172f, width = 70f),
        )

    @Test
    fun `resting fraction snaps to the exact slot`() {
        slots.forEachIndexed { index, slot ->
            val geometry = pillGeometry(slots, index.toFloat())
            assertEquals(slot.left, geometry.left, 0.001f)
            assertEquals(slot.width, geometry.width, 0.001f)
        }
    }

    @Test
    fun `halfway fraction interpolates left and width`() {
        val geometry = pillGeometry(slots, 0.5f)
        assertEquals(50f, geometry.left, 0.001f)
        assertEquals(70f, geometry.width, 0.001f)
    }

    @Test
    fun `quarter fraction interpolates from the current slot`() {
        val geometry = pillGeometry(slots, 1.25f)
        assertEquals(106f, geometry.left, 0.001f)
        assertEquals(77.5f, geometry.width, 0.001f)
    }

    @Test
    fun `fraction past the last slot clamps to it`() {
        val geometry = pillGeometry(slots, 5f)
        assertEquals(172f, geometry.left, 0.001f)
        assertEquals(70f, geometry.width, 0.001f)
    }

    @Test
    fun `negative fraction clamps to the first slot`() {
        val geometry = pillGeometry(slots, -1f)
        assertEquals(16f, geometry.left, 0.001f)
        assertEquals(60f, geometry.width, 0.001f)
    }

    @Test
    fun `single slot geometry is identity`() {
        val single = listOf(TabSlot(left = 100f, width = 40f))
        val geometry = pillGeometry(single, 0f)
        assertEquals(100f, geometry.left, 0.001f)
        assertEquals(40f, geometry.width, 0.001f)
    }

    @Test
    fun `empty slots collapse to zero`() {
        val geometry = pillGeometry(emptyList(), 0.5f)
        assertEquals(0f, geometry.left, 0.001f)
        assertEquals(0f, geometry.width, 0.001f)
    }

    @Test
    fun `pill inside the viewport keeps the current scroll`() {
        val scroll =
            keepInViewScroll(
                viewport = 360,
                maxScroll = 200,
                current = 80,
                slot = TabSlot(left = 150f, width = 60f),
                margin = 48f,
            )
        assertEquals(80, scroll)
    }

    @Test
    fun `pill crowding the left edge scrolls back to reveal it`() {
        val scroll =
            keepInViewScroll(
                viewport = 360,
                maxScroll = 200,
                current = 80,
                slot = TabSlot(left = 20f, width = 60f),
                margin = 48f,
            )
        assertEquals(0, scroll)
    }

    @Test
    fun `pill crowding the right edge scrolls forward to reveal it`() {
        val scroll =
            keepInViewScroll(
                viewport = 360,
                maxScroll = 200,
                current = 0,
                slot = TabSlot(left = 316f, width = 40f),
                margin = 48f,
            )
        assertEquals(44, scroll)
    }

    @Test
    fun `scroll result never exceeds the scroll range`() {
        val scroll =
            keepInViewScroll(
                viewport = 360,
                maxScroll = 90,
                current = 0,
                slot = TabSlot(left = 900f, width = 40f),
                margin = 48f,
            )
        assertEquals(90, scroll)
    }

    @Test
    fun `zero viewport keeps the current scroll`() {
        val scroll =
            keepInViewScroll(
                viewport = 0,
                maxScroll = 200,
                current = 123,
                slot = TabSlot(left = 500f, width = 60f),
                margin = 48f,
            )
        assertEquals(123, scroll)
    }
}
