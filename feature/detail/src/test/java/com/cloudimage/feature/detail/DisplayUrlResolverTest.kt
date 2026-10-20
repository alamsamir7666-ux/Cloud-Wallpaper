package com.cloudimage.feature.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The display-tier rewrite table, pinned file by file. Every rule was
 * verified against the live site (2026-10-19): the families exist, are
 * served as decodable JPEG, and are one to two orders of magnitude
 * smaller than the originals they stand in for.
 */
class DisplayUrlResolverTest {
    @Test
    fun wallpapercaveSiteOriginalsRideThe2xDisplayRender() {
        assertEquals(
            "https://wallpapercave.com/dwp2x/wp14813207.jpg",
            DisplayUrlResolver.displayUrlFor("https://wallpapercave.com/wp/wp14813207.jpg"),
        )
    }

    @Test
    fun wallpapercaveShortIdsAreRewrittenToo() {
        assertEquals(
            "https://wallpapercave.com/dwp2x/KfuayGb.jpg",
            DisplayUrlResolver.displayUrlFor("https://wallpapercave.com/wp/KfuayGb.jpg"),
        )
    }

    @Test
    fun wallpapercaveUserUploadsRideTheFuwpPreview() {
        assertEquals(
            "https://wallpapercave.com/fuwp/uwp5104320.jpeg",
            DisplayUrlResolver.displayUrlFor("https://wallpapercave.com/uwp/uwp5104320.jpeg"),
        )
    }

    @Test
    fun httpAndWwwFormsAreRewritten() {
        assertEquals(
            "https://wallpapercave.com/dwp2x/wp123.webp",
            DisplayUrlResolver.displayUrlFor("http://wallpapercave.com/wp/wp123.webp"),
        )
        assertEquals(
            "https://www.wallpapercave.com/fuwp/uwp1.png",
            DisplayUrlResolver.displayUrlFor("https://www.wallpapercave.com/uwp/uwp1.png"),
        )
    }

    @Test
    fun hostIsMatchedCaseInsensitively() {
        assertEquals(
            "https://wallpapercave.com/dwp2x/wp123.jpg",
            DisplayUrlResolver.displayUrlFor("https://WallPaperCave.com/wp/wp123.jpg"),
        )
    }

    @Test
    fun thumbnailAndPreviewFamiliesPassThroughUntouched() {
        // The rewrite must match whole directory segments — never the
        // letters "wp"/"uwp" inside another family's name.
        listOf(
            "https://wallpapercave.com/dwp1x/wp123.jpg",
            "https://wallpapercave.com/dwp2x/wp123.jpg",
            "https://wallpapercave.com/fuwp/uwp123.jpeg",
            "https://wallpapercave.com/uwpr/uwp123.jpeg",
            "https://wallpapercave.com/mwp/wp123.jpg",
            "https://wallpapercave.com/wpt/wp123.jpg",
            "https://wallpapercave.com/fwp-255/wp123.jpg",
            "https://wallpapercave.com/w/wp123.jpg",
            "https://wallpapercave.com/wp123.jpg",
            "https://wallpapercave.com/w/uwp123",
        ).forEach { url ->
            assertEquals(url, DisplayUrlResolver.displayUrlFor(url))
        }
    }

    @Test
    fun foreignHostsAreNeverRewritten() {
        listOf(
            "https://wallhaven.cc/full/abc.jpg",
            "https://w.wallhaven.cc/full/abc.jpg",
            "https://notwallpapercave.com/wp/wp123.jpg",
            "https://cdn.pixabay.com/photo/uwp1.jpg",
            "https://wallpapercave.com.evil.example/wp/wp123.jpg",
        ).forEach { url ->
            assertEquals(url, DisplayUrlResolver.displayUrlFor(url))
        }
    }

    @Test
    fun nonHttpSchemesAreNeverRewritten() {
        assertEquals(
            "file:///wp/wp123.jpg",
            DisplayUrlResolver.displayUrlFor("file:///wp/wp123.jpg"),
        )
        assertEquals(
            "content://media/wp/wp123.jpg",
            DisplayUrlResolver.displayUrlFor("content://media/wp/wp123.jpg"),
        )
    }

    @Test
    fun malformedAndBlankInputAnswersItself() {
        listOf(
            "",
            "   ",
            "not a url",
            "https://",
            "https://wallpapercave.com",
            "https://wallpapercave.com/",
            "https://wallpapercave.com/wp/",
            "https://wallpapercave.com/wp",
            ":::",
        ).forEach { url ->
            assertEquals(url, DisplayUrlResolver.displayUrlFor(url))
        }
    }

    @Test
    fun aQueryOnTheOriginalIsDroppedFromTheDisplayUrl() {
        // The render families are query-free; the tier requests the bare
        // file. Pinned here so the behavior stays deliberate.
        assertEquals(
            "https://wallpapercave.com/dwp2x/wp123.jpg",
            DisplayUrlResolver.displayUrlFor("https://wallpapercave.com/wp/wp123.jpg?token=1"),
        )
    }
}
