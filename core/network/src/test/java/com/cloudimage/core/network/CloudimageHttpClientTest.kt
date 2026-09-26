package com.cloudimage.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudimageHttpClientTest {
    @Serializable
    private data class Payload(
        val id: Int,
        val name: String,
    )

    private lateinit var server: MockWebServer
    private lateinit var client: CloudimageHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client =
            CloudimageHttpClient(
                okHttpClient = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getReturnsBodyOnSuccess() =
        runTest {
            server.enqueue(MockResponse().setBody("wallpaper-bytes"))

            val result = client.get(server.url("/file").toString())

            assertEquals(NetworkResult.Success("wallpaper-bytes"), result)
        }

    @Test
    fun getJsonDecodesPayloadAndIgnoresUnknownKeys() =
        runTest {
            server.enqueue(MockResponse().setBody("""{"id": 7, "name": "aurora", "future_field": true}"""))

            val result = client.getJson(server.url("/v1/thing").toString(), Payload.serializer())

            assertEquals(NetworkResult.Success(Payload(id = 7, name = "aurora")), result)
        }

    @Test
    fun getSendsCloudimageUserAgent() =
        runTest {
            server.enqueue(MockResponse().setBody("{}"))

            client.get(server.url("/ping").toString())

            val recorded = server.takeRequest()
            assertEquals(CloudimageHttpClient.USER_AGENT, recorded.getHeader("User-Agent"))
            assertEquals("GET", recorded.method)
        }

    @Test
    fun httpErrorMapsToHttpFailure() =
        runTest {
            val url = server.url("/missing").toString()
            server.enqueue(MockResponse().setResponseCode(500))

            val result = client.get(url)

            assertEquals(NetworkResult.Failure(NetworkError.Http(code = 500, url = url)), result)
        }

    @Test
    fun malformedJsonMapsToSerializationFailure() =
        runTest {
            server.enqueue(MockResponse().setBody("<html>not json</html>"))

            val result = client.getJson(server.url("/bad").toString(), Payload.serializer())

            assertTrue(result is NetworkResult.Failure && result.error is NetworkError.Serialization)
        }

    @Test
    fun unreachableServerMapsToIoFailure() =
        runTest {
            val url = server.url("/nowhere").toString()
            server.shutdown()

            val result = client.get(url)

            assertTrue(result is NetworkResult.Failure && result.error is NetworkError.Io)
        }

    @Test
    fun downloadReturnsRawBytesOnSuccess() =
        runTest {
            // Binary body: a mix that would break naive String decoding.
            val bytes =
                byteArrayOf(
                    0x89.toByte(),
                    0x50,
                    0x4E,
                    0x47,
                    0x0D,
                    0x0A,
                    0x1A,
                    0x0A,
                    0x00,
                    0xFF.toByte(),
                )
            server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))

            val result = client.download(server.url("/full/xy.jpg").toString())

            assertTrue(result is NetworkResult.Success)
            assertTrue((result as NetworkResult.Success).value.contentEquals(bytes))
            val recorded = server.takeRequest()
            assertEquals(CloudimageHttpClient.USER_AGENT, recorded.getHeader("User-Agent"))
        }

    @Test
    fun downloadMapsHttpErrorToFailure() =
        runTest {
            val url = server.url("/full/missing.jpg").toString()
            server.enqueue(MockResponse().setResponseCode(404))

            val result = client.download(url)

            assertEquals(NetworkResult.Failure(NetworkError.Http(code = 404, url = url)), result)
        }

    @Test
    fun getRawSendsExtraHeaders() =
        runTest {
            server.enqueue(MockResponse().setBody("{}"))

            client.getRaw(
                server.url("/provider").toString(),
                extraHeaders = mapOf("X-Provider-Key" to "token-42"),
            )

            val recorded = server.takeRequest()
            assertEquals("token-42", recorded.getHeader("X-Provider-Key"))
            assertEquals(CloudimageHttpClient.USER_AGENT, recorded.getHeader("User-Agent"))
        }

    @Test
    fun getRawReturnsStatusHeadersAndBody() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("payload")
                    .addHeader("X-Request-Id", "abc-123"),
            )

            val result = client.getRaw(server.url("/raw").toString())

            assertTrue(result is NetworkResult.Success)
            val payload = (result as NetworkResult.Success).value
            assertEquals(200, payload.statusCode)
            assertEquals("payload", payload.bodyText)
            assertEquals("abc-123", payload.headers["X-Request-Id"]?.single())
        }

    @Test
    fun getRawPassesNonSuccessfulStatusesThrough() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(404)
                    .setBody("not here"),
            )

            val result = client.getRaw(server.url("/gone").toString())

            assertTrue(result is NetworkResult.Success)
            val payload = (result as NetworkResult.Success).value
            assertEquals(404, payload.statusCode)
            assertEquals("not here", payload.bodyText)
        }

    // ---- Cloudflare bypass (v1.0.15) ----

    /** A scripted solver — the real one is a WebView, and the JVM has none. */
    private class BypassSolver : CloudflareSolver {
        var persisted: CloudflareBypass? = null
        var earned: CloudflareBypass? = null
        var solveCalls = 0

        override suspend fun persistedStateFor(host: String): CloudflareBypass? = persisted

        override suspend fun solve(url: String): CloudflareBypass? {
            solveCalls++
            return earned
        }
    }

    private val challengeBody =
        """
        <!DOCTYPE html><html lang="en-US"><head><title>Just a moment...</title>
        <script src="/cdn-cgi/challenge-platform/h/b/orchestrate/challenge_page/v1" defer></script>
        </head><body>Enable JavaScript and cookies to continue</body></html>
        """.trimIndent()

    private fun clientWith(solver: BypassSolver): CloudimageHttpClient =
        CloudimageHttpClient(
            okHttpClient = OkHttpClient(),
            json = Json { ignoreUnknownKeys = true },
            cloudflare = CloudflareBypasser(solver),
        )

    @Test
    fun cloudflareChallengeIsSolvedAndReplayedWithTheEarnedIdentity() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(403).setBody(challengeBody))
            server.enqueue(MockResponse().setBody("the real page"))
            val solver =
                BypassSolver().apply {
                    earned = CloudflareBypass(cookieHeader = "cf_clearance=earned; __cf_bm=bm", userAgent = "WebViewAgent/1")
                }

            val result = clientWith(solver).getRaw(server.url("/search?wallpaper=nature").toString())

            assertTrue(result is NetworkResult.Success)
            val payload = (result as NetworkResult.Success).value
            assertEquals(200, payload.statusCode)
            assertEquals("the real page", payload.bodyText)
            assertEquals(1, solver.solveCalls)
            // The challenged attempt went out with the host agent and no
            // cookies; the replay carried the earned pair, both of it.
            val challenged = server.takeRequest()
            val replayed = server.takeRequest()
            assertEquals(CloudimageHttpClient.USER_AGENT, challenged.getHeader("User-Agent"))
            assertNull(challenged.getHeader("Cookie"))
            assertEquals("WebViewAgent/1", replayed.getHeader("User-Agent"))
            assertEquals("cf_clearance=earned; __cf_bm=bm", replayed.getHeader("Cookie"))
        }

    @Test
    fun challengeFlowsThroughWhenNoClearanceCanBeEarned() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(403).setBody(challengeBody))
            val solver = BypassSolver() // the WebView never settles

            val result = clientWith(solver).getRaw(server.url("/grid").toString())

            // Per the facade contract: a non-2xx is a regular response the
            // caller decides about — an unsolvable challenge is no exception.
            assertTrue(result is NetworkResult.Success)
            assertEquals(403, (result as NetworkResult.Success).value.statusCode)
            assertEquals(1, solver.solveCalls)
        }

    @Test
    fun plainForbiddenNeverWakesTheBypass() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"errors":["invalid api key"]}"""))
            val solver =
                BypassSolver().apply {
                    earned = CloudflareBypass(cookieHeader = "cf_clearance=x", userAgent = "A")
                }

            clientWith(solver).getRaw(server.url("/v1/keyed").toString())

            assertEquals(0, solver.solveCalls)
        }

    @Test
    fun clearanceOnFileRidesTheFirstRequestWithoutASolve() =
        runTest {
            server.enqueue(MockResponse().setBody("warm"))
            val solver =
                BypassSolver().apply {
                    persisted = CloudflareBypass(cookieHeader = "cf_clearance=kept", userAgent = "WebViewAgent/1")
                    earned = CloudflareBypass(cookieHeader = "cf_clearance=never", userAgent = "B")
                }

            val result = clientWith(solver).getRaw(server.url("/warm").toString())

            assertTrue(result is NetworkResult.Success)
            assertEquals(0, solver.solveCalls)
            val recorded = server.takeRequest()
            assertEquals("WebViewAgent/1", recorded.getHeader("User-Agent"))
            assertEquals("cf_clearance=kept", recorded.getHeader("Cookie"))
        }

    @Test
    fun staleClearanceIsResolvedAndReplayed() =
        runTest {
            // First attempt rides the on-file (expired) clearance and is
            // challenged anyway; the replay carries the re-earned one.
            server.enqueue(MockResponse().setResponseCode(403).setBody(challengeBody))
            server.enqueue(MockResponse().setBody("renewed"))
            val solver =
                BypassSolver().apply {
                    persisted = CloudflareBypass(cookieHeader = "cf_clearance=old", userAgent = "WebViewAgent/1")
                    earned = CloudflareBypass(cookieHeader = "cf_clearance=fresh", userAgent = "WebViewAgent/1")
                }

            val result = clientWith(solver).getRaw(server.url("/stale").toString())

            assertTrue(result is NetworkResult.Success)
            assertEquals("renewed", (result as NetworkResult.Success).value.bodyText)
            assertEquals(1, solver.solveCalls)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("cf_clearance=old", first.getHeader("Cookie"))
            assertEquals("cf_clearance=fresh", second.getHeader("Cookie"))
        }
}
