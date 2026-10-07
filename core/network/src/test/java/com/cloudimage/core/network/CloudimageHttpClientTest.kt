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
    fun downloadWithProgressReportsRunningBytesAndTotal() =
        runTest {
            // A body larger than one 64 KiB progress chunk, with a declared
            // Content-Length so the total is knowable.
            val bytes = ByteArray(3 * CloudimageHttpClient.PROGRESS_CHUNK_BYTES.toInt() + 7) { (it % 251).toByte() }
            server.enqueue(
                MockResponse()
                    .setBody(okio.Buffer().write(bytes))
                    .setHeader("Content-Length", bytes.size.toLong()),
            )
            val progress = mutableListOf<Pair<Long, Long?>>()

            val result =
                client.download(server.url("/full/big.jpg").toString()) { read, total ->
                    progress += read to total
                }

            assertTrue(result is NetworkResult.Success)
            assertTrue((result as NetworkResult.Success).value.contentEquals(bytes))
            // Monotonic byte counts, ending at the declared total.
            assertEquals(bytes.size.toLong(), progress.last().first)
            assertEquals(bytes.size.toLong(), progress.last().second)
            assertTrue(progress.zipWithNext().all { (earlier, later) -> later.first > earlier.first })
            assertTrue(progress.all { it.second == bytes.size.toLong() })
        }

    @Test
    fun downloadWithProgressDegradesToNullTotalWithoutContentLength() =
        runTest {
            // Chunked transfer encoding: no Content-Length on the response.
            val bytes = ByteArray(200) { (it % 97).toByte() }
            server.enqueue(
                MockResponse()
                    .setChunkedBody(okio.Buffer().write(bytes), 64),
            )
            val progress = mutableListOf<Pair<Long, Long?>>()

            val result =
                client.download(server.url("/full/chunked.jpg").toString()) { read, total ->
                    progress += read to total
                }

            assertTrue(result is NetworkResult.Success)
            assertTrue((result as NetworkResult.Success).value.contentEquals(bytes))
            assertEquals(bytes.size.toLong(), progress.last().first)
            assertTrue(progress.all { it.second == null })
        }

    @Test
    fun downloadWithProgressEmitsNothingForErrorBodies() =
        runTest {
            val url = server.url("/full/forbidden.jpg").toString()
            server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
            val progress = mutableListOf<Pair<Long, Long?>>()

            val result =
                client.download(url) { read, total ->
                    progress += read to total
                }

            assertEquals(NetworkResult.Failure(NetworkError.Http(code = 403, url = url)), result)
            assertTrue(progress.isEmpty())
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
        var fetched: WebViewPage? = null
        var solveCalls = 0
        var fetchCalls = 0

        override suspend fun persistedStateFor(host: String): CloudflareBypass? = persisted

        override suspend fun solve(url: String): CloudflareBypass? {
            solveCalls++
            return earned
        }

        override suspend fun fetch(url: String): WebViewPage? {
            fetchCalls++
            return fetched
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

    // ---- WebView document fetch, the ladder's last rung (v1.2.3) ----

    @Test
    fun unsolvableChallengeIsFetchedThroughTheWebView() =
        runTest {
            // The zone challenges, no clearance can be earned (the WebView
            // never settles one), but the page itself loads fine in the
            // browser engine — the fingerprint-strict case WallpaperFlare
            // installs hit in the wild.
            server.enqueue(MockResponse().setResponseCode(403).setBody(challengeBody))
            val solver =
                BypassSolver().apply {
                    earned = null
                    fetched = WebViewPage(html = "<html>the real grid</html>", clearance = null)
                }

            val result = clientWith(solver).getRaw(server.url("/search?wallpaper=nature").toString())

            assertTrue(result is NetworkResult.Success)
            val payload = (result as NetworkResult.Success).value
            assertEquals(200, payload.statusCode)
            assertEquals("<html>the real grid</html>", payload.bodyText)
            assertEquals(1, solver.solveCalls)
            assertEquals(1, solver.fetchCalls)
            assertEquals(1, server.requestCount) // no replay — the document won
        }

    @Test
    fun pageWithOnlyAClearanceGetsOneMoreReplay() =
        runTest {
            // The fetch handed back no document but a fresher clearance
            // (earned while this request waited) — one more replay under it.
            server.enqueue(MockResponse().setResponseCode(403).setBody(challengeBody))
            server.enqueue(MockResponse().setBody("served under the fresh clearance"))
            val fresh = CloudflareBypass(cookieHeader = "cf_clearance=fresher", userAgent = "WebViewAgent/2")
            val solver =
                BypassSolver().apply {
                    earned = null
                    fetched = WebViewPage(html = null, clearance = fresh)
                }

            val result = clientWith(solver).getRaw(server.url("/grid").toString())

            assertTrue(result is NetworkResult.Success)
            assertEquals("served under the fresh clearance", (result as NetworkResult.Success).value.bodyText)
            server.takeRequest() // the challenged first attempt, no cookies
            val replayed = server.takeRequest()
            assertEquals("cf_clearance=fresher", replayed.getHeader("Cookie"))
            assertEquals("WebViewAgent/2", replayed.getHeader("User-Agent"))
            assertEquals(1, solver.fetchCalls)
        }

    @Test
    fun webViewFetchFailureSurfacesTheOriginalChallenge() =
        runTest {
            // Neither a clearance nor a document — the caller sees the honest
            // 403 it always would have.
            server.enqueue(MockResponse().setResponseCode(403).setBody(challengeBody))
            val solver = BypassSolver() // nothing can be earned or fetched

            val result = clientWith(solver).getRaw(server.url("/grid").toString())

            assertTrue(result is NetworkResult.Success)
            assertEquals(403, (result as NetworkResult.Success).value.statusCode)
            assertEquals(challengeBody, (result as NetworkResult.Success).value.bodyText)
            assertEquals(1, solver.solveCalls)
            assertEquals(1, solver.fetchCalls)
        }

    @Test
    fun replayThatStaysChallengedFallsBackToTheWebViewDocument() =
        runTest {
            // A clearance WAS earned, but the replay under it is still
            // challenged — the zone binds its clearances to more than
            // (IP, User-Agent). The document comes from the WebView.
            server.enqueue(MockResponse().setResponseCode(403).setBody(challengeBody))
            server.enqueue(MockResponse().setResponseCode(403).setBody(challengeBody))
            val solver =
                BypassSolver().apply {
                    earned = CloudflareBypass(cookieHeader = "cf_clearance=rejected-anyway", userAgent = "WebViewAgent/1")
                    fetched = WebViewPage(html = "<html>content via webview</html>", clearance = null)
                }

            val result = clientWith(solver).getRaw(server.url("/grid").toString())

            assertTrue(result is NetworkResult.Success)
            val payload = (result as NetworkResult.Success).value
            assertEquals(200, payload.statusCode)
            assertEquals("<html>content via webview</html>", payload.bodyText)
            assertEquals(1, solver.fetchCalls)
            assertEquals(2, server.requestCount) // first attempt + the failed replay
        }
}
