package com.cloudimage.core.search

import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.search.ImageSearchResult.Failure
import com.cloudimage.core.search.ImageSearchResult.Success
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

/**
 * The DuckDuckGo engine's contract, exercised against a scripted
 * MockWebServer: the two-step browser flow, pagination cursors, header
 * mimicry, tier filtering, dedup and the failure taxonomy.
 */
class DuckDuckGoImageSearchEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: DuckDuckGoImageSearchEngine

    // The token's real shape inside DDG's scripts: an assignment, not JSON.
    private val htmlWithToken =
        """
        <html><head><script>window.DDG = { ready: true }; DDG.vqd="4-1234567890";</script></head></html>
        """.trimIndent()

    private val onePageOfResults =
        """
        {
          "results": [
            {
              "title": "Mountain at dawn",
              "image": "https://img.example.com/mountain-full.jpg",
              "thumbnail": "https://external-content.duckduckgo.com/iu/?u=mountain-thumb",
              "url": "https://www.example.com/mountain",
              "width": 3840,
              "height": 2160
            },
            {
              "title": "A small preview",
              "image": "https://img.example.com/small-full.jpg",
              "thumbnail": "https://external-content.duckduckgo.com/iu/?u=small-thumb",
              "url": "https://www.example.com/small",
              "width": 640,
              "height": 480
            },
            {
              "title": "Dimensions as strings",
              "image": "https://img.example.com/stringy-full.jpg",
              "thumbnail": "https://external-content.duckduckgo.com/iu/?u=stringy-thumb",
              "url": "https://www.example.com/stringy",
              "width": "2560",
              "height": "1440"
            },
            {
              "title": "Unknown dimensions",
              "image": "https://img.example.com/unknown-full.jpg",
              "thumbnail": "https://external-content.duckduckgo.com/iu/?u=unknown-thumb",
              "url": "https://www.example.com/unknown"
            }
          ],
          "next": "100",
          "vqd": "4-9999999999",
          "query_expansions": [
            {"displayText": "mountain 4k"},
            {"displayText": ""},
            {"displayText": "mountain wallpaper"}
          ]
        }
        """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine =
            DuckDuckGoImageSearchEngine(
                client = CloudimageHttpClient(OkHttpClient(), Json { ignoreUnknownKeys = true }),
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun searchRunsTheTwoStepBrowserFlowAndMapsResults() =
        runTest {
            server.enqueueHtml(htmlWithToken)
            server.enqueueJson(onePageOfResults)

            val outcome = engine.search("mountain", page = 1, filters = GlobalSearchFilters())

            val page = (outcome as Success).page
            // ANY passes everything: big, small, string-typed and unknown dimensions alike.
            assertEquals(4, page.results.size)
            val first = page.results.first()
            assertEquals("ddg:https://img.example.com/mountain-full.jpg", first.id)
            assertEquals(GLOBAL_SEARCH_PROVIDER_ID, first.providerId)
            assertEquals("https://external-content.duckduckgo.com/iu/?u=mountain-thumb", first.thumbUrl)
            assertEquals("https://img.example.com/mountain-full.jpg", first.fullUrl)
            assertEquals("Mountain at dawn", first.title)
            assertEquals(3840, first.width)
            assertEquals(2160, first.height)
            assertEquals("https://www.example.com/mountain", first.sourceUrl)
            assertTrue(page.hasMore)
            assertEquals(listOf("mountain 4k", "mountain wallpaper"), page.relatedSearches)

            // The flow's shape: HTML page first, i.js JSON second.
            val htmlRequest = server.takeRequest()
            val jsonRequest = server.takeRequest()
            assertEquals("/", htmlRequest.path?.substringBefore('?'))
            assertEquals("/i.js", jsonRequest.path?.substringBefore('?'))
            assertEquals("mountain", htmlRequest.requestUrl?.queryParameter("q"))
            assertEquals("images", htmlRequest.requestUrl?.queryParameter("iax"))
            assertEquals("4-1234567890", jsonRequest.requestUrl?.queryParameter("vqd"))
            assertEquals("1", jsonRequest.requestUrl?.queryParameter("p"))

            // Browser mimicry: a Chrome UA on both legs, XHR headers on the JSON leg.
            assertTrue(htmlRequest.getHeader("User-Agent")!!.contains("Chrome/131"))
            assertTrue(jsonRequest.getHeader("User-Agent")!!.contains("Chrome/131"))
            assertEquals("https://duckduckgo.com/", jsonRequest.getHeader("Referer"))
            assertEquals("XMLHttpRequest", jsonRequest.getHeader("X-Requested-With"))
        }

    @Test
    fun stringTypedDimensionsSurviveTheDrift() =
        runTest {
            server.enqueueHtml(htmlWithToken)
            server.enqueueJson(onePageOfResults)

            val outcome = engine.search("mountain", page = 1, filters = GlobalSearchFilters())

            val stringy = (outcome as Success).page.results.first { it.fullUrl.contains("stringy") }
            assertEquals(2560, stringy.width)
            assertEquals(1440, stringy.height)
        }

    @Test
    fun paginationSendsTheOffsetCursor() =
        runTest {
            server.enqueueHtml(htmlWithToken)
            server.enqueueJson(onePageOfResults)

            engine.search("mountain", page = 2, filters = GlobalSearchFilters())

            server.takeRequest() // HTML
            val jsonRequest = server.takeRequest()
            assertEquals("100", jsonRequest.requestUrl?.queryParameter("s"))
        }

    @Test
    fun safeSearchOffAsksForEverything() =
        runTest {
            server.enqueueHtml(htmlWithToken)
            server.enqueueJson(onePageOfResults)

            engine.search("mountain", page = 1, filters = GlobalSearchFilters(safeSearch = false))

            server.takeRequest()
            assertEquals("-1", server.takeRequest().requestUrl?.queryParameter("p"))
        }

    @Test
    fun sizeTierBiasesTheEngineAndVerifiesExactly() =
        runTest {
            server.enqueueHtml(htmlWithToken)
            server.enqueueJson(onePageOfResults)

            val outcome =
                engine.search("mountain", page = 1, filters = GlobalSearchFilters(sizeTier = SearchSizeTier.UHD))

            // The 640x480 image fails the exact 4K check; unknown dimensions
            // cannot be verified and never pass a real tier.
            val results = (outcome as Success).page.results
            assertEquals(listOf("https://img.example.com/mountain-full.jpg"), results.map { it.fullUrl })
            // hasMore still rides the RAW batch — filtering must not end the scroll.
            assertTrue(outcome.page.hasMore)

            server.takeRequest()
            val bias = server.takeRequest().requestUrl?.queryParameter("f")
            assertEquals(",,,size:Wallpaper", bias)
        }

    @Test
    fun withinPageDuplicateUrlsCollapse() =
        runTest {
            val duplicated =
                """
                {
                  "results": [
                    {"title": "First", "image": "https://img.example.com/same.jpg", "thumbnail": "https://t1", "url": "https://www.example.com/a", "width": 1920, "height": 1080},
                    {"title": "Second", "image": "https://img.example.com/same.jpg", "thumbnail": "https://t2", "url": "https://www.example.com/b", "width": 1920, "height": 1080}
                  ],
                  "next": "100"
                }
                """.trimIndent()
            server.enqueueHtml(htmlWithToken)
            server.enqueueJson(duplicated)

            val outcome = engine.search("dupes", page = 1, filters = GlobalSearchFilters())

            assertEquals(1, (outcome as Success).page.results.size)
        }

    @Test
    fun staleTokenIsRefreshedMidSession() =
        runTest {
            // First i.js answer comes back as HTML — the token went stale.
            server.enqueueHtml(htmlWithToken)
            server.enqueueHtml("<html>anomaly</html>")
            // The retry mints a fresh token and succeeds.
            server.enqueueHtml(htmlWithToken)
            server.enqueueJson(onePageOfResults)

            val outcome = engine.search("mountain", page = 1, filters = GlobalSearchFilters())

            assertTrue(outcome is Success)
            assertEquals(4, server.requestCount)
            server.takeRequest()
            assertEquals("4-1234567890", server.takeRequest().requestUrl?.queryParameter("vqd"))
            server.takeRequest()
            assertEquals("4-1234567890", server.takeRequest().requestUrl?.queryParameter("vqd"))
        }

    @Test
    fun anomalyPageWithoutTokenSurfacesRateLimited() =
        runTest {
            repeat(DuckDuckGoImageSearchEngineTest.RETRY_ROUNDS) {
                server.enqueueHtml("<html>no token here</html>")
            }

            val outcome = engine.search("mountain", page = 1, filters = GlobalSearchFilters())

            val failure = outcome as Failure
            assertEquals(ImageSearchError.RATE_LIMITED, failure.error)
            assertEquals(RETRY_ROUNDS, server.requestCount)
        }

    @Test
    fun http403OnTheTokenPageReadsAsThrottling() =
        runTest {
            repeat(DuckDuckGoImageSearchEngineTest.RETRY_ROUNDS) {
                server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
            }

            val outcome = engine.search("mountain", page = 1, filters = GlobalSearchFilters())

            assertEquals(ImageSearchError.RATE_LIMITED, (outcome as Failure).error)
        }

    @Test
    fun emptyQueryShortCircuitsToAnEmptyPage() =
        runTest {
            val outcome = engine.search("   ", page = 1, filters = GlobalSearchFilters())

            val page = (outcome as Success).page
            assertTrue(page.results.isEmpty())
            assertFalse(page.hasMore)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun jsonWithoutResultsArrayIsABadResponse() =
        runTest {
            server.enqueueHtml(htmlWithToken)
            server.enqueueJson("""{"unexpected": true}""")

            repeat(DuckDuckGoImageSearchEngineTest.RETRY_ROUNDS) {
                server.enqueueHtml(htmlWithToken)
                server.enqueueJson("""{"unexpected": true}""")
            }

            val outcome = engine.search("mountain", page = 1, filters = GlobalSearchFilters())

            assertEquals(ImageSearchError.BAD_RESPONSE, (outcome as Failure).error)
        }

    @Test
    fun parseResultsMapsEveryEntryIntoGlobalSearchWallpapers() {
        val outcome =
            engine.parseResults(
                cacheKey = "shape",
                body = onePageOfResults,
                page = 1,
                filters = GlobalSearchFilters(),
            )
        val results = (outcome as Success).page.results
        assertEquals(4, results.size)
        assertTrue(results.all { it.providerId == GLOBAL_SEARCH_PROVIDER_ID })
        assertTrue(results.all { it.id.startsWith("ddg:") })
    }

    private companion object {
        const val RETRY_ROUNDS = 3
    }
}

private fun MockWebServer.enqueueHtml(body: String) {
    enqueue(MockResponse().setResponseCode(200).setBody(body).setHeader("Content-Type", "text/html"))
}

private fun MockWebServer.enqueueJson(body: String) {
    enqueue(MockResponse().setResponseCode(200).setBody(body).setHeader("Content-Type", "application/json"))
}
