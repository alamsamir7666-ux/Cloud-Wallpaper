package com.cloudimage.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure rules for deriving storage metadata (file name, mime type) from a
 * wallpaper's identity and URL.
 */
class WallpaperFilesTest {
    private val wallpaper =
        Wallpaper(
            id = "e1abc2",
            providerId = "wallhaven",
            thumbUrl = "https://w.wallhaven.cc/full/e1abc2/large.jpg",
            fullUrl = "https://w.wallhaven.cc/full/e1abc2/original.png",
        )

    @Test
    fun fileNameCombinesProviderIdAndUrlExtension() {
        assertEquals("wallhaven-e1abc2.png", wallpaper.savedFileName())
    }

    @Test
    fun extensionFallsBackToJpg() {
        val weird = wallpaper.copy(fullUrl = "https://example.com/full/99/no-extension")
        assertEquals("jpg", weird.savedExtension())
    }

    @Test
    fun extensionIgnoresQueryString() {
        val query = wallpaper.copy(fullUrl = "https://example.com/full/99/pic.jpg?token=a1b2&sig=zz")
        assertEquals("jpg", query.savedExtension())
    }

    @Test
    fun unsafeIdCharactersAreSanitized() {
        val unsafe = wallpaper.copy(id = "we/ird id!")
        assertEquals("wallhaven-we_ird_id_.png", unsafe.savedFileName())
    }

    @Test
    fun mimeTypeMapsJpgToJpeg() {
        val jpeg = wallpaper.copy(fullUrl = "https://example.com/full/99/pic.jpg")
        assertEquals("image/jpeg", jpeg.savedMimeType())
        assertEquals("image/png", wallpaper.savedMimeType())
    }
}
