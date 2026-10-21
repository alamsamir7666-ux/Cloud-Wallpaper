package com.cloudimage.extensions.core

import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CloudimageProviderHttpClientTest {
    private lateinit var server: MockWebServer
    private lateinit var facade: CloudimageProviderHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client =
            CloudimageHttpClient(
                okHttpClient = OkHttpClient(),
                json = Json { ignoreUnknownKeys = true },
            )
        facade = CloudimageProviderHttpClient(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getReturnsStatusHeadersAndBody() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("provider payload")
                    .addHeader("X-Request-Id", "req-7"),
            )

            val response = facade.get(server.url("/v1").toString(), headers = mapOf("X-Provider-Key" to "key-1"))

            assertTrue(response.isSuccessful)
            assertEquals(200, response.statusCode)
            assertEquals("provider payload", response.bodyText)
            assertEquals("req-7", response.header("X-REQUEST-ID"))
            val recorded = server.takeRequest()
            assertEquals("key-1", recorded.getHeader("X-Provider-Key"))
            assertTrue(recorded.getHeader("User-Agent")!!.startsWith("Cloudimage/"))
        }

    @Test
    fun nonSuccessfulStatusesFlowThroughUntouched() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404).setBody("gone"))

            val response = facade.get(server.url("/missing").toString())

            assertFalse(response.isSuccessful)
            assertEquals(404, response.statusCode)
            assertEquals("gone", response.bodyText)
        }

    @Test
    fun transportFailureThrowsFacadeException() =
        runTest {
            val url = server.url("/down").toString()
            server.shutdown()

            val failure = runCatching { facade.get(url) }.exceptionOrNull()

            assertTrue(failure is ProviderHttpException)
            assertTrue(failure!!.message!!.contains("/down"))
        }

    @Test
    fun cloudflareBlockPageThrowsCloudflareException() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("Server", "cloudflare")
                    .setBody(
                        "<html><head><title>Attention Required! | Cloudflare</title></head>" +
                            "<body><h1>Sorry, you have been blocked</h1>" +
                            "<p>You are unable to access wallpapersden.com</p></body></html>",
                    ),
            )

            val failure = runCatching { facade.get(server.url("/latest/").toString()) }.exceptionOrNull()

            assertTrue(failure is ProviderCloudflareException)
            val cloudflare = failure as ProviderCloudflareException
            assertEquals(403, cloudflare.statusCode)
            assertEquals("localhost", cloudflare.host)
            // The message the user reads must say what happened and what to do.
            assertTrue(cloudflare.message!!.contains("Cloudflare protection refused this connection"))
            assertTrue(cloudflare.message!!.contains("Retry"))
        }

    @Test
    fun cloudflareChallengePageThrowsCloudflareException() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setResponseCode(503)
                    .setHeader("Server", "cloudflare")
                    .setBody(
                        "<html><head><title>Just a moment...</title></head><body>" +
                            "Enable JavaScript and cookies to continue" +
                            "<script src=\"/cdn-cgi/challenge-platform/hbeta/jsd/main.js\"></script>" +
                            "</body></html>",
                    ),
            )

            val failure = runCatching { facade.get(server.url("/").toString()) }.exceptionOrNull()

            assertTrue(failure is ProviderCloudflareException)
            assertEquals(503, (failure as ProviderCloudflareException).statusCode)
        }

    @Test
    fun cloudflareEdgeApiErrorStillFlowsThroughUntouched() =
        runTest {
            // A Cloudflare-fronted API's legitimate 403: the edge's server
            // header wakes the ladder's detector, but the body is the
            // source's own JSON — no Cloudflare furniture — so the plugin
            // must keep its right to phrase the error itself.
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("Server", "cloudflare")
                    .setBody("{\"error\":\"invalid_api_key\"}"),
            )

            val response = facade.get(server.url("/api").toString())

            assertFalse(response.isSuccessful)
            assertEquals(403, response.statusCode)
            assertEquals("{\"error\":\"invalid_api_key\"}", response.bodyText)
        }

    /**
     * v1.3.1 stale-on-error: a GET whose transport failed but whose
     * document the disk cache still holds returns THAT last known good
     * answer instead of throwing — the offline-reader degradation. The
     * cached answer is served even though the network is now dead.
     */
    @Test
    fun transportFailureFallsBackToCachedDocument() =
        runTest {
            val cacheDir =
                java.nio.file.Files
                    .createTempDirectory("facade-cache")
                    .toFile()
            val cachedClient =
                CloudimageHttpClient(
                    okHttpClient =
                        OkHttpClient
                            .Builder()
                            .cache(okhttp3.Cache(cacheDir, 5L * 1024 * 1024))
                            .addNetworkInterceptor(com.cloudimage.core.network.ProviderCachePolicyInterceptor)
                            .build(),
                    json = Json { ignoreUnknownKeys = true },
                )
            val cachedFacade = CloudimageProviderHttpClient(cachedClient)
            val url = server.url("/feed?cursor=2").toString()

            // Prime: the origin answers, silent about freshness — the
            // interceptor grants the document its sixty seconds.
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody("the last good feed")
                    .addHeader("Content-Type", "application/json"),
            )
            assertEquals("the last good feed", cachedFacade.get(url).bodyText)

            // Kill the network. The same GET now fails transport — and is
            // rescued from the cache, body intact, status 200.
            server.shutdown()
            val rescued = cachedFacade.get(url)
            assertTrue(rescued.isSuccessful)
            assertEquals("the last good feed", rescued.bodyText)
            cacheDir.deleteRecursively()
        }

    /** The rescue is honest: a URL never fetched throws the transport truth. */
    @Test
    fun transportFailureWithoutCacheEntryStillThrows() =
        runTest {
            server.shutdown()

            val failure =
                runCatching {
                    facade.get(server.url("/never-fetched").toString())
                }.exceptionOrNull()

            assertTrue(failure is ProviderTransportException)
        }

    /**
     * The rescue only applies to transport failures: a non-2xx ANSWER is
     * the source speaking, and a cached older success must never be
     * smuggled back over the fresh failure.
     */
    @Test
    fun httpAnswerIsNeverReplacedByStaleCache() =
        runTest {
            val url = server.url("/flaky").toString()
            server.enqueue(MockResponse().setResponseCode(200).setBody("cached goodness"))
            assertEquals("cached goodness", facade.get(url).bodyText)

            server.enqueue(MockResponse().setResponseCode(500).setBody("origin bug"))
            val fresh = facade.get(url)

            assertFalse(fresh.isSuccessful)
            assertEquals(500, fresh.statusCode)
            assertEquals("origin bug", fresh.bodyText)
        }
}
