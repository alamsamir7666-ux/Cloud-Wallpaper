package com.cloudimage.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.min

/**
 * [ImageProgressRegistry] byte accounting, exercised against a real
 * OkHttp call over MockWebServer: observed URLs stream progress, unknown
 * lengths degrade to [ImageProgress.UNKNOWN_TOTAL], and unobserved URLs
 * pass through untouched.
 */
class ImageProgressRegistryTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            OkHttpClient
                .Builder()
                .addInterceptor(ImageProgressRegistry.interceptor())
                .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `observed url reports growing progress and completes`() {
        val body = "x".repeat(400_000)
        server.enqueue(MockResponse().setBody(body))
        val url = server.url("/full.jpg").toString()
        val progress = ImageProgressRegistry.observe(url)

        val response = client.newCall(Request.Builder().url(url).build()).execute()
        // Drain in small chunks so several read() passes happen and the
        // conflation window actually sees intermediate values. Requests are
        // sized to the exact remainder: readUtf8(count) throws on a short
        // read, so the final chunk must not overshoot the body.
        val source = response.body!!.source()
        var seen = 0
        var lastBytes = 0L
        while (seen < body.length) {
            val chunk = min(32_768, body.length - seen)
            seen += source.readUtf8(chunk.toLong()).length
            val snapshot = progress.value
            assertNotNull("progress must exist once the body streams", snapshot)
            assertTrue("bytes must never regress", snapshot!!.bytesRead >= lastBytes)
            lastBytes = snapshot.bytesRead
        }
        // -1 read already reported the finish; exhausted() asks the source
        // for one more byte so the EOF actually surfaces.
        assertTrue(source.exhausted())
        assertTrue(progress.value!!.done)
        assertEquals(body.length.toLong(), progress.value!!.totalBytes)
        assertEquals(body.length.toLong(), progress.value!!.bytesRead)
        assertEquals(1f, progress.value!!.fraction)
    }

    @Test
    fun `unknown content length degrades to unknown total`() {
        val body = "y".repeat(10_000)
        server.enqueue(MockResponse().setChunkedBody(body, 512))
        val url = server.url("/chunked.jpg").toString()
        val progress = ImageProgressRegistry.observe(url)

        val response = client.newCall(Request.Builder().url(url).build()).execute()
        assertEquals(body, response.body!!.string())

        val snapshot = progress.value
        assertNotNull(snapshot)
        assertEquals(ImageProgress.UNKNOWN_TOTAL, snapshot!!.totalBytes)
        assertNull("no fraction without a declared total", snapshot.fraction)
        assertEquals(body.length.toLong(), snapshot.bytesRead)
        assertTrue(snapshot.done)
    }

    @Test
    fun `unobserved url passes through untouched`() {
        val body = "z".repeat(1_000)
        server.enqueue(MockResponse().setBody(body))
        val url = server.url("/thumb.jpg").toString()

        val response = client.newCall(Request.Builder().url(url).build()).execute()
        assertEquals(body, response.body!!.string())
        // No observer was registered for this URL; a later observe() is a
        // fresh, null-valued flow.
        assertNull(ImageProgressRegistry.observe(url).value)
    }

    @Test
    fun `reset drops the observer so the next visit starts fresh`() {
        val body = "a".repeat(500)
        server.enqueue(MockResponse().setBody(body))
        val url = server.url("/once.jpg").toString()
        val progress = ImageProgressRegistry.observe(url)

        val response = client.newCall(Request.Builder().url(url).build()).execute()
        assertEquals(body, response.body!!.string())
        assertTrue(progress.value!!.done)

        ImageProgressRegistry.reset(url)
        assertNull(ImageProgressRegistry.observe(url).value)
    }
}
