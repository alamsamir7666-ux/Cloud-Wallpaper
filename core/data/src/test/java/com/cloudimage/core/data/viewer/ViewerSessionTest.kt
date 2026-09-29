package com.cloudimage.core.data.viewer

import com.cloudimage.core.model.Wallpaper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session hand-off's contract (v1.0.23): a grid parks a list, the
 * detail screen that opens on the wallpaper the list points at takes it,
 * and nobody else does — plus the frame's own sense of its edges.
 */
class ViewerSessionTest {
    private fun wallpaper(id: String) =
        Wallpaper(
            id = id,
            providerId = "wallhaven",
            thumbUrl = "https://w.wallhaven.cc/thumb/$id.jpg",
            fullUrl = "https://w.wallhaven.cc/full/$id.png",
        )

    private val list = listOf(wallpaper("a"), wallpaper("b"), wallpaper("c"))

    @Test
    fun takeHandsTheParkedFrameToTheMatchingWallpaper() {
        val session = ViewerSession()
        session.open(list, index = 1)

        val frame = session.take(wallpaper("b"))

        assertEquals(list, frame?.wallpapers)
        assertEquals(1, frame?.index)
        assertEquals(wallpaper("b"), frame?.current)
        assertTrue(frame?.hasPrevious == true)
        assertTrue(frame?.hasNext == true)
    }

    @Test
    fun takeRefusesAWallpaperTheFrameDoesNotPointAt() {
        val session = ViewerSession()
        session.open(list, index = 1)

        assertNull(session.take(wallpaper("a")))
    }

    @Test
    fun theParkedFrameIsConsumedByTheFirstTake() {
        val session = ViewerSession()
        session.open(list, index = 2)

        assertEquals(wallpaper("c"), session.take(wallpaper("c"))?.current)
        assertNull(session.take(wallpaper("c")))
    }

    @Test
    fun openingWithoutAParkYieldsNothing() {
        assertNull(ViewerSession().take(wallpaper("a")))
    }

    @Test
    fun openIgnoresAnIndexOutsideTheList() {
        val session = ViewerSession()
        session.open(list, index = 3)

        assertNull(session.take(wallpaper("a")))
    }

    @Test
    fun frameEdgesKnowWhereTheListStops() {
        val middle = ViewerSession.Frame(list, index = 1)
        assertTrue(middle.hasPrevious)
        assertTrue(middle.hasNext)

        val first = ViewerSession.Frame(list, index = 0)
        assertFalse(first.hasPrevious)
        assertTrue(first.hasNext)

        val last = ViewerSession.Frame(list, index = 2)
        assertTrue(last.hasPrevious)
        assertFalse(last.hasNext)

        // An empty frame never throws, however it was built.
        val empty = ViewerSession.Frame(emptyList(), index = 0)
        assertFalse(empty.hasPrevious)
        assertFalse(empty.hasNext)
        assertNull(empty.current)
    }
}
