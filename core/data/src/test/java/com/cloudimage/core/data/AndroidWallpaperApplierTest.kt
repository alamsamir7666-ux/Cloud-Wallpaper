package com.cloudimage.core.data

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cloudimage.core.data.platform.AndroidWallpaperApplier
import com.cloudimage.core.data.platform.BitmapDecoder
import com.cloudimage.core.data.platform.SystemWallpaperSetter
import com.cloudimage.core.data.platform.galleryContentValues
import com.cloudimage.core.data.platform.toManagerFlags
import com.cloudimage.core.data.repository.ApplyError
import com.cloudimage.core.data.repository.ApplyResult
import com.cloudimage.core.data.repository.ApplyTarget
import com.cloudimage.core.model.Wallpaper
import com.cloudimage.core.network.CloudimageHttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * The apply pipeline is tested against a real MockWebServer with scripted
 * [BitmapDecoder] and [SystemWallpaperSetter] seams — every failure mode
 * (HTTP, undecodable bytes, an unsupported device, a system IO error) is
 * injected through the seams, so the assertions cover the code as it runs in
 * production.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AndroidWallpaperApplierTest {
    private lateinit var server: MockWebServer
    private lateinit var setter: RecordingSetter
    private lateinit var decoder: FakeDecoder
    private lateinit var applier: AndroidWallpaperApplier

    private val wallpaper =
        Wallpaper(
            id = "e1abc2",
            providerId = "wallhaven",
            thumbUrl = "https://w.wallhaven.cc/full/e1abc2/large.jpg",
            fullUrl = "https://w.wallhaven.cc/full/e1abc2/original.png",
        )

    /** The wallpaper under test, with its full URL pointed at the mock server. */
    private fun servedWallpaper(): Wallpaper = wallpaper.copy(fullUrl = server.url("/full/xy.png").toString())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        setter = RecordingSetter()
        decoder = FakeDecoder()
        applier =
            AndroidWallpaperApplier(
                httpClient =
                    CloudimageHttpClient(
                        okHttpClient = OkHttpClient(),
                        json = Json { ignoreUnknownKeys = true },
                    ),
                decoder = decoder,
                setter = setter,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun applyDecodesAndSetsTheRequestedTarget() =
        runTest {
            server.enqueue(MockResponse().setBody("image-bytes"))

            val result = applier.apply(servedWallpaper(), ApplyTarget.LOCK)

            assertEquals(ApplyResult.Success, result)
            assertEquals(1, setter.calls.size)
            assertEquals(ApplyTarget.LOCK, setter.calls.single().second)
            assertNotNull(setter.calls.single().first)
        }

    @Test
    fun applyMapsHttpFailure() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val result = applier.apply(servedWallpaper(), ApplyTarget.HOME)

            assertEquals(ApplyResult.Failure(ApplyError.HTTP), result)
            assertTrue(setter.calls.isEmpty())
        }

    @Test
    fun applyMapsUndecodableBytesToDecodeFailure() =
        runTest {
            server.enqueue(MockResponse().setBody("corrupt-bytes"))
            decoder.result = null

            val result = applier.apply(servedWallpaper(), ApplyTarget.HOME)

            assertEquals(ApplyResult.Failure(ApplyError.DECODE), result)
            assertTrue(setter.calls.isEmpty())
        }

    @Test
    fun applyMapsUnsupportedTarget() =
        runTest {
            server.enqueue(MockResponse().setBody("image-bytes"))
            setter.failure = IllegalArgumentException("FLAG_LOCK unsupported")

            val result = applier.apply(servedWallpaper(), ApplyTarget.LOCK)

            assertEquals(ApplyResult.Failure(ApplyError.UNSUPPORTED), result)
        }

    @Test
    fun applyMapsSecurityRejection() =
        runTest {
            server.enqueue(MockResponse().setBody("image-bytes"))
            setter.failure = SecurityException("not the default home app")

            val result = applier.apply(servedWallpaper(), ApplyTarget.LOCK)

            assertEquals(ApplyResult.Failure(ApplyError.UNSUPPORTED), result)
        }

    @Test
    fun applyMapsSystemIoError() =
        runTest {
            server.enqueue(MockResponse().setBody("image-bytes"))
            setter.failure = IOException("disk full")

            val result = applier.apply(servedWallpaper(), ApplyTarget.BOTH)

            assertEquals(ApplyResult.Failure(ApplyError.IO), result)
        }

    @Test
    fun targetFlagsMapOntoWallpaperManagerBits() {
        assertEquals(1, ApplyTarget.HOME.toManagerFlags())
        assertEquals(2, ApplyTarget.LOCK.toManagerFlags())
        assertEquals(3, ApplyTarget.BOTH.toManagerFlags())
    }

    @Test
    fun galleryValuesCarryRelativePathAndPendingFlag() {
        val values = galleryContentValues(fileName = "wallhaven-e1abc2.png", mimeType = "image/png")
        assertEquals("wallhaven-e1abc2.png", values.getAsString("_display_name"))
        assertEquals("image/png", values.getAsString("mime_type"))
        assertEquals("Pictures/Cloudimage", values.getAsString("relative_path"))
        assertEquals(1, values.getAsInteger("is_pending"))
    }

    private class FakeDecoder : BitmapDecoder {
        var result: Bitmap? = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

        override fun decode(bytes: ByteArray): Bitmap? = result
    }

    private class RecordingSetter : SystemWallpaperSetter {
        val calls = mutableListOf<Pair<Bitmap, ApplyTarget>>()
        var failure: Exception? = null

        override fun set(
            bitmap: Bitmap,
            target: ApplyTarget,
        ) {
            failure?.let { throw it }
            calls += bitmap to target
        }
    }
}
