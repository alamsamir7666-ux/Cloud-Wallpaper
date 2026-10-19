package com.cloudimage.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The penalty box interceptor end-to-end (v1.2.28): a burst of 415s from
 * one host trips the box, and while it holds, further requests to that
 * host fail fast with a synthetic 503 without touching the wire — the
 * behavior that stops the app's own retry pressure from keeping a
 * rate-limited image host dead. Requests to other hosts keep flowing.
 */
class ImageHostPenaltyBoxInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            OkHttpClient
                .Builder()
                .addInterceptor(ImageHostPenaltyBox.interceptor())
                .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
        ImageHostPenaltyBox.clear()
    }

    private fun get(path: String): Int {
        val request = Request.Builder().url(server.url(path)).build()
        return client.newCall(request).execute().use { it.code }
    }

    @Test
    fun a415BurstTripsTheBoxAndFastFailsWithoutNetwork() {
        // The limiter's signature: three 415s back to back.
        repeat(3) { server.enqueue(MockResponse().setResponseCode(415)) }

        assertEquals(415, get("/fuwp/one.jpeg"))
        assertEquals(415, get("/fuwp/two.jpeg"))
        assertEquals(415, get("/fuwp/three.jpeg"))

        // The box is tripped now. NO response is enqueued for the next
        // request — if the interceptor let it reach the wire, MockWebServer
        // would time out and fail the test. The synthetic 503 must come
        // from the interceptor alone.
        assertEquals(503, get("/fuwp/four.jpeg"))
        assertEquals(503, get("/dwp1x/five.jpg"))

        // Exactly the three 415s hit the wire.
        assertEquals(3, server.requestCount)
    }

    @Test
    fun oneStray415DoesNotFastFailAnything() {
        server.enqueue(MockResponse().setResponseCode(415))
        server.enqueue(MockResponse().setResponseCode(200).setBody("image-bytes"))

        assertEquals(415, get("/dwp2x/stray.jpeg"))
        assertEquals(200, get("/fuwp/next.jpeg"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun a429FastFailsTheVeryNextRequest() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody("image-bytes"))

        assertEquals(429, get("/fuwp/one.jpeg"))
        // Penalized before the second request: the enqueued 200 stays
        // untouched and the interceptor answers the synthetic 503.
        assertEquals(503, get("/fuwp/two.jpeg"))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun successfulResponsesNeverTripAnything() {
        repeat(5) { server.enqueue(MockResponse().setResponseCode(200).setBody("image-bytes")) }

        repeat(5) { assertEquals(200, get("/fuwp/$it.jpeg")) }
        assertEquals(5, server.requestCount)
        assertTrue(!ImageHostPenaltyBox.isPenalized(server.hostName))
    }
}
