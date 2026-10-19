package com.cloudimage.core.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rate-limit circuit breaker for image hosts (v1.2.28). The wallpapercave
 * CDN answers its rate limit with 415 (a code that also has a genuine
 * family-mismatch meaning), so the trip logic must count bursts, not
 * singletons; 429s are trusted immediately; penalties expire on schedule.
 */
class ImageHostPenaltyBoxTest {
    @After
    fun tearDown() {
        ImageHostPenaltyBox.clear()
    }

    @Test
    fun aSingle415DoesNotTripTheBox() {
        val tripped = ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 415, now = 1_000)

        assertFalse(tripped)
        assertFalse(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = 1_000))
    }

    @Test
    fun three415sInsideTheWindowTripTheBox() {
        val now = 10_000L
        ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 415, now)
        ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 415, now + 1_000)
        val tripped = ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 415, now + 2_000)

        assertTrue(tripped)
        assertTrue(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now + 2_000))
    }

    @Test
    fun stray415sSpreadBeyondTheWindowNeverAccumulate() {
        // One genuine family-mismatch 415 per minute is a stray URL, not a
        // limiter: the 30s window must forget them before the third lands.
        ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 415, now = 0)
        ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 415, now = 61_000)
        val tripped = ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 415, now = 122_000)

        assertFalse(tripped)
        assertFalse(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = 122_000))
    }

    @Test
    fun a429TripsTheBoxImmediately() {
        val tripped = ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 429, now = 5_000)

        assertTrue(tripped)
        assertTrue(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = 5_000))
    }

    @Test
    fun thePenaltyExpiresAfter75Seconds() {
        val trippedAt = 100_000L
        ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 429, now = trippedAt)

        assertTrue(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = trippedAt + 74_999))
        assertFalse(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = trippedAt + 75_000))
        assertEquals(0, ImageHostPenaltyBox.penaltyRemainingMs("wallpapercave.com", now = trippedAt + 75_000))
    }

    @Test
    fun hostsAreIsolatedFromEachOther() {
        ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 429, now = 1_000)

        assertTrue(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = 1_000))
        assertFalse(ImageHostPenaltyBox.isPenalized("wallhere.com", now = 1_000))
        assertFalse(ImageHostPenaltyBox.isPenalized("behance.net", now = 1_000))
    }

    @Test
    fun hostLookupIsCaseInsensitive() {
        ImageHostPenaltyBox.noteLimitResponse("WallpaperCave.com", 429, now = 1_000)

        assertTrue(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = 1_000))
        assertTrue(ImageHostPenaltyBox.isPenalized("WALLPAPERCAVE.COM", now = 1_000))
    }

    @Test
    fun otherCodesAreIgnored() {
        val tripped = ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 404, now = 1_000)

        assertFalse(tripped)
        assertFalse(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = 1_000))
    }

    @Test
    fun afterTheBoxClearsABurstCanTripItAgain() {
        // The limiter's windows repeat; the box must be re-trippable rather
        // than latching closed forever after one penalty.
        ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 429, now = 0)
        assertTrue(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = 1_000))
        assertFalse(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = 80_000))

        val tripped = ImageHostPenaltyBox.noteLimitResponse("wallpapercave.com", 429, now = 90_000)
        assertTrue(tripped)
        assertTrue(ImageHostPenaltyBox.isPenalized("wallpapercave.com", now = 90_001))
    }
}
