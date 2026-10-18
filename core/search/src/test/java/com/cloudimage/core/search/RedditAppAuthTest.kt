package com.cloudimage.core.search

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

/**
 * The installed-app OAuth client's contract, exercised against a scripted
 * MockWebServer standing in for reddit.com's token endpoint: the request
 * shape (Basic auth with an EMPTY secret, the installed-client grant, the
 * per-installation device id), the token cache (one fetch, many searches),
 * the early-expiry refresh, and the failure taxonomy (every failure is a
 * null token, never a throw — the search lane falls back to anonymous).
 */
class RedditAppAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var auth: RedditAppAuth
    private val json = Json { ignoreUnknownKeys = true }
    private val deviceId = "fixedDeviceIdForTests0000"
    private var now: Long = 1_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        auth =
            RedditAppAuth(
                baseClient = OkHttpClient(),
                json = json,
                deviceIdStore = { deviceId },
                overrideTokenBaseUrl = server.url("/").toString().trimEnd('/'),
                clock = { now },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun tokenBody(
        token: String = "tok-1",
        expiresIn: Long = 3600,
    ): String = """{"access_token": "$token", "token_type": "bearer", "expires_in": $expiresIn, "scope": "*"}"""

    @Test
    fun `token request carries basic auth empty secret grant and device id`() =
        runTest {
            server.enqueue(MockResponse().setBody(tokenBody("tok-1")))

            assertEquals("tok-1", auth.bearerToken("my-client-id"))

            val recorded = server.takeRequest()
            assertEquals("/api/v1/access_token", recorded.path)
            assertEquals("POST", recorded.method)
            // Basic base64("my-client-id:") — the installed-app shape (no secret).
            val expectedBasic = "Basic " + Base64.getEncoder().encodeToString("my-client-id:".toByteArray())
            assertEquals(expectedBasic, recorded.getHeader("Authorization"))
            val body = recorded.body.readUtf8()
            assertTrue(body.contains("grant_type=https%3A%2F%2Foauth.reddit.com%2Fgrants%2Finstalled_client"))
            assertTrue(body.contains("device_id=$deviceId"))
            // A descriptive User-Agent — Reddit throttles generic agents.
            assertNotNull(recorded.getHeader("User-Agent"))
        }

    @Test
    fun `token is cached across calls within its lifetime`() =
        runTest {
            server.enqueue(MockResponse().setBody(tokenBody("tok-1")))
            auth.bearerToken("client")
            server.enqueue(MockResponse().setBody(tokenBody("tok-2")))
            auth.bearerToken("client")

            // One hour minus the 5-minute margin is far more than these two
            // clock ticks — the second call must reuse tok-1 without asking.
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `expired token is refreshed`() =
        runTest {
            server.enqueue(MockResponse().setBody(tokenBody("tok-1", expiresIn = 3600)))
            assertEquals("tok-1", auth.bearerToken("client"))

            // March past the margin-adjusted expiry.
            now += 3600L * 1000L

            server.enqueue(MockResponse().setBody(tokenBody("tok-2", expiresIn = 3600)))
            assertEquals("tok-2", auth.bearerToken("client"))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `short-lived tokens never trust a sub-margin ttl`() =
        runTest {
            // expires_in shorter than the refresh margin must still yield a
            // usable (briefly) cached token — coerced to the minimum TTL.
            server.enqueue(MockResponse().setBody(tokenBody("tok-short", expiresIn = 30)))
            assertEquals("tok-short", auth.bearerToken("client"))
            // Inside the coerced 60s TTL: no re-fetch.
            now += 10_000L
            assertEquals("tok-short", auth.bearerToken("client"))
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `invalidate forces the next call to re-mint`() =
        runTest {
            server.enqueue(MockResponse().setBody(tokenBody("tok-1")))
            assertEquals("tok-1", auth.bearerToken("client"))

            auth.invalidate()

            server.enqueue(MockResponse().setBody(tokenBody("tok-2")))
            assertEquals("tok-2", auth.bearerToken("client"))
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `non-200 answers are null never thrown`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message": "Unauthorized"}"""))
            assertNull(auth.bearerToken("bad-client"))
        }

    @Test
    fun `garbage bodies are null never thrown`() =
        runTest {
            server.enqueue(MockResponse().setBody("not json at all"))
            assertNull(auth.bearerToken("client"))
        }

    @Test
    fun `blank access tokens are null`() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"access_token": "", "expires_in": 3600}"""))
            assertNull(auth.bearerToken("client"))
        }

    @Test
    fun `network failures are null never thrown`() =
        runTest {
            val dead = MockWebServer()
            dead.start()
            val deadAuth =
                RedditAppAuth(
                    baseClient = OkHttpClient(),
                    json = json,
                    deviceIdStore = { deviceId },
                    overrideTokenBaseUrl = dead.url("/").toString().trimEnd('/'),
                    clock = { now },
                )
            dead.shutdown()

            assertNull(deadAuth.bearerToken("client"))
        }
}
