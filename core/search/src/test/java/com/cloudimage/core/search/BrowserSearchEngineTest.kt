package com.cloudimage.core.search

import com.cloudimage.core.search.ImageSearchResult.Failure
import com.cloudimage.core.search.ImageSearchResult.Success
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Browser engine's contract, exercised against a scripted MockWebServer
 * standing in for the deployed Browser repo system: request shape, the
 * result mapping, exact client-side tier verification, within-page URL
 * dedup, and the failure taxonomy.
 */
class BrowserSearchEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: BrowserSearchEngine
    private val json = Json { ignoreUnknownKeys = true }

    private val onePageOfResults =
        """
        {
          "success": true,
          "query": "mountain",
          "count": 3,
          "page": 1,
          "hasMore": true,
          "results": [
            {
              "id": "img-p1-0-1",
              "original_url": "https://z-cdn.example.com/mountain.jpg",
              "caption": "",
              "source": "Unsplash",
              "original_width": 3000,
              "original_height": 2003
            },
            {
              "id": "img-p1-1-1",
              "original_url": "https://z-cdn.example.com/stringy.jpg",
              "caption": "",
              "source": "Matador Network",
              "original_width": "2560px",
              "original_height": "1440px"
            },
            {
              "id": "img-p1-2-1",
              "original_url": "https://z-cdn.example.com/unknown.jpg",
              "source": "KTLA"
            }
          ]
        }
        """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // The override is exactly what production DI never sets: a test-only
        // base URL. The config below is never consulted while it is present.
        val untouchedConfig =
            SearchBackendConfig(
                baseClient = OkHttpClient(),
                json = json,
                remoteUrl = "http://127.0.0.1:1/config.json",
                defaultBaseUrl = "http://127.0.0.1:1",
            )
        engine =
            BrowserSearchEngine(
                config = untouchedConfig,
                baseClient = OkHttpClient(),
                json = json,
                overrideBaseUrl = server.url("/").toString(),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun searchPostsTheQueryAndMapsResults() =
        runTest {
            server.enqueue(MockResponse().setBody(onePageOfResults).setHeader("Content-Type", "application/json"))

            val outcome = engine.search("mountain", page = 1, filters = GlobalSearchFilters())

            val recorded = server.takeRequest()
            assertEquals("/api/search", recorded.path)
            assertEquals("POST", recorded.method)
            // OkHttp appends the charset to the media type it is handed.
            assertEquals("application/json; charset=utf-8", recorded.getHeader("Content-Type"))
            val sentBody = recorded.body.readUtf8()
            assertTrue(sentBody.contains("\"query\":\"mountain\""))
            assertTrue(sentBody.contains("\"page\":1"))

            val page = (outcome as Success).page
            assertEquals(3, page.results.size)
            assertEquals(true, page.hasMore)
            assertEquals(1, page.page)
            val first = page.results[0]
            val expectedBase = server.url("/").toString().trimEnd('/')
            assertEquals("gs:https://z-cdn.example.com/mountain.jpg", first.id)
            assertEquals(GLOBAL_SEARCH_PROVIDER_ID, first.providerId)
            // v1.2.1: thumbnails ride the backend's image proxy at 640px.
            assertTrue(
                "thumbUrl should ride the proxy at 640px, was: ${first.thumbUrl}",
                first.thumbUrl.startsWith("$expectedBase/api/proxy-image?"),
            )
            assertTrue(first.thumbUrl.contains("url="))
            assertTrue(first.thumbUrl.contains("w=640"))
            // The full URL stays as the original — quality is the whole
            // point of the detail screen, downloads and share.
            assertEquals("https://z-cdn.example.com/mountain.jpg", first.fullUrl)
            assertEquals("Unsplash", first.title)
            assertEquals(3000, first.width)
            assertEquals(2003, first.height)
        }

    @Test
    fun stringTypedDimensionsParseAndZerosMeanUnknown() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """
                        {
                          "success": true, "page": 1, "hasMore": false,
                          "results": [
                            {"original_url": "https://x.example.com/px.jpg",
                             "source": "A", "original_width": "1400px", "original_height": "934px"},
                            {"original_url": "https://x.example.com/zero.jpg",
                             "source": "B", "original_width": 0, "original_height": 0},
                            {"original_url": "https://x.example.com/garbage.jpg",
                             "source": "C", "original_width": "n/a", "original_height": "n/a"}
                          ]
                        }
                        """.trimIndent(),
                    ).setHeader("Content-Type", "application/json"),
            )

            val page = (engine.search("q", 1, GlobalSearchFilters()) as Success).page

            assertEquals(1400 to 934, page.results[0].width to page.results[0].height)
            assertEquals(null to null, page.results[1].width to page.results[1].height)
            assertEquals(null to null, page.results[2].width to page.results[2].height)
        }

    @Test
    fun withinPageDuplicatesCollapseByUrl() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """
                        {
                          "success": true, "page": 2, "hasMore": true,
                          "results": [
                            {"original_url": "https://x.example.com/same.jpg", "source": "Unsplash", "original_width": 1000, "original_height": 800},
                            {"original_url": "https://x.example.com/same.jpg", "source": "Unsplash", "original_width": 1000, "original_height": 800},
                            {"original_url": "https://x.example.com/other.jpg", "source": "Pinterest", "original_width": 800, "original_height": 1000}
                          ]
                        }
                        """.trimIndent(),
                    ).setHeader("Content-Type", "application/json"),
            )

            val page = (engine.search("q", 2, GlobalSearchFilters()) as Success).page

            assertEquals(
                listOf("gs:https://x.example.com/same.jpg", "gs:https://x.example.com/other.jpg"),
                page.results.map { it.id },
            )
            // Blank URLs are dropped, not mapped into broken wallpapers.
            assertTrue(page.results.all { it.thumbUrl.isNotBlank() })
        }

    @Test
    fun tierVerifiesExactlyClientSide() =
        runTest {
            server.enqueue(MockResponse().setBody(onePageOfResults).setHeader("Content-Type", "application/json"))

            // FHD asks 1920x1080 in either orientation: the 3000x2003 shot
            // and the string-typed 2560x1440 pass; unknown dimensions never
            // pass a real tier — a wallpaper app that guesses lies.
            val page =
                (
                    engine.search(
                        "mountain",
                        1,
                        GlobalSearchFilters(sizeTier = SearchSizeTier.FHD),
                    ) as Success
                ).page

            assertEquals(
                listOf("gs:https://z-cdn.example.com/mountain.jpg", "gs:https://z-cdn.example.com/stringy.jpg"),
                page.results.map { it.id },
            )
        }

    @Test
    fun aTierBiasesTheQueryTowardItsOwnModifiers() =
        runTest {
            val biasByTier =
                mapOf(
                    SearchSizeTier.HD to "mountain hd",
                    SearchSizeTier.FHD to "mountain high resolution",
                    SearchSizeTier.QHD to "mountain 4k",
                    SearchSizeTier.UHD to "mountain 4k",
                )
            biasByTier.forEach { (tier, biasedQuery) ->
                server.enqueue(MockResponse().setBody(onePageOfResults).setHeader("Content-Type", "application/json"))
                engine.search("mountain", 1, GlobalSearchFilters(sizeTier = tier))

                val sent = server.takeRequest().body.readUtf8()
                assertEquals(
                    biasedQuery,
                    Json
                        .parseToJsonElement(sent)
                        .jsonObject["query"]!!
                        .jsonPrimitive.content,
                )
            }
        }

    @Test
    fun theAnyTierSendsTheQueryVerbatim() =
        runTest {
            server.enqueue(MockResponse().setBody(onePageOfResults).setHeader("Content-Type", "application/json"))

            engine.search("mountain mist", 3, GlobalSearchFilters(sizeTier = SearchSizeTier.ANY))

            val sent = server.takeRequest().body.readUtf8()
            assertEquals(
                "mountain mist",
                Json
                    .parseToJsonElement(sent)
                    .jsonObject["query"]!!
                    .jsonPrimitive.content,
            )
            // The page number rides alongside, untouched by the biasing.
            assertEquals(
                3,
                Json
                    .parseToJsonElement(sent)
                    .jsonObject["page"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun blankQueryFailsWithoutTouchingTheNetwork() =
        runTest {
            val outcome = engine.search("   ", 1, GlobalSearchFilters())

            assertEquals(ImageSearchError.BAD_RESPONSE, (outcome as Failure).error)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun throttlingIsRateLimited() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))

            val outcome = engine.search("q", 1, GlobalSearchFilters())

            assertEquals(ImageSearchError.RATE_LIMITED, (outcome as Failure).error)
        }

    @Test
    fun httpErrorsAreServer() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

            val outcome = engine.search("q", 1, GlobalSearchFilters())

            assertEquals(ImageSearchError.SERVER, (outcome as Failure).error)
            assertEquals("HTTP 500", outcome.detail)
        }

    @Test
    fun upstreamFailureIsServerWithItsReason() =
        runTest {
            server.enqueue(
                MockResponse()
                    .setBody(
                        """
                        {"success": false, "error": "Image search returned no parseable response.", "results": []}
                        """.trimIndent(),
                    ).setHeader("Content-Type", "application/json"),
            )

            val outcome = engine.search("q", 1, GlobalSearchFilters())

            assertEquals(ImageSearchError.SERVER, (outcome as Failure).error)
            assertEquals("Image search returned no parseable response.", outcome.detail)
        }

    @Test
    fun garbageBodiesAreBadResponse() =
        runTest {
            server.enqueue(MockResponse().setBody("<html>gateway says hi</html>"))

            val outcome = engine.search("q", 1, GlobalSearchFilters())

            assertEquals(ImageSearchError.BAD_RESPONSE, (outcome as Failure).error)
        }

    @Test
    fun connectionRefusalIsNetwork() =
        runTest {
            val url = server.url("/")
            server.shutdown()
            val deadEngine =
                BrowserSearchEngine(
                    config =
                        SearchBackendConfig(
                            baseClient = OkHttpClient(),
                            json = json,
                            remoteUrl = "http://127.0.0.1:1/config.json",
                            defaultBaseUrl = "http://127.0.0.1:1",
                        ),
                    baseClient = OkHttpClient(),
                    json = json,
                    overrideBaseUrl = url.toString(),
                )

            val outcome = deadEngine.search("q", 1, GlobalSearchFilters())

            assertEquals(ImageSearchError.NETWORK, (outcome as Failure).error)
        }

    @Test
    fun networkFailureInvalidatesTheConfigSoTheNextSearchReFetches() =
        runTest {
            // Stand up a mock config server that records each fetch.
            val deadSearchServer = MockWebServer().apply { start() }
            val deadUrl = deadSearchServer.url("/").toString().trimEnd('/')
            deadSearchServer.shutdown() // now deadUrl is unreachable

            val configServer =
                MockWebServer().apply {
                    // Two enqueued responses: one per expected fetch.
                    enqueue(MockResponse().setBody("""{"baseUrl": "$deadUrl"}"""))
                    enqueue(MockResponse().setBody("""{"baseUrl": "$deadUrl"}"""))
                    start()
                }

            val config =
                SearchBackendConfig(
                    baseClient = OkHttpClient(),
                    json = json,
                    remoteUrl = configServer.url("/config.json").toString(),
                    defaultBaseUrl = "http://127.0.0.1:1",
                )
            val engine =
                BrowserSearchEngine(
                    config = config,
                    baseClient = OkHttpClient(),
                    json = json,
                    // overrideBaseUrl stays null — we want config to drive the URL.
                )

            // First search: fetches config (1), gets deadUrl, search fails
            // with NETWORK, config is invalidated.
            val first = engine.search("q", 1, GlobalSearchFilters())
            assertEquals(ImageSearchError.NETWORK, (first as Failure).error)
            assertEquals(1, configServer.requestCount)

            // Second search: config is stale (invalidate() reset the TTL),
            // so baseUrl() re-fetches (2).
            val second = engine.search("q", 1, GlobalSearchFilters())
            assertEquals(ImageSearchError.NETWORK, (second as Failure).error)
            assertEquals(2, configServer.requestCount)

            configServer.shutdown()
        }
}
