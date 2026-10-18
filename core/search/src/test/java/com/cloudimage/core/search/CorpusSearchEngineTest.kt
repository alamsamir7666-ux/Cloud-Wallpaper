package com.cloudimage.core.search

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The corpus engine's contract, exercised against a scripted MockWebServer
 * standing in for the Supabase PostgREST endpoint: the exact-match
 * normalized query, the anonymous auth headers, the row-to-wallpaper
 * mapping, raw-batch hasMore, the bounded page depth, the corpus-less
 * build, and the failure taxonomy.
 */
class CorpusSearchEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: CorpusSearchEngine
    private val json = Json { ignoreUnknownKeys = true }

    private val twoRows =
        """
        [
          {
            "image_url": "https://z-cdn.example.com/lake.jpg",
            "thumb_url": null,
            "page_url": null,
            "title": "A crystal-clear alpine lake surrounded by autumn-colored mountains",
            "source_host": "unsplash.com",
            "width": 3000,
            "height": 2000
          },
          {
            "image_url": "https://z-cdn.example.com/forest.jpg",
            "thumb_url": "https://z-cdn.example.com/t/forest.jpg",
            "page_url": "https://example.com/forest",
            "title": "Misty forest at dawn",
            "source_host": "alphacoders.com",
            "width": 1920,
            "height": 1080
          },
          {
            "image_url": null,
            "page_url": "https://example.com/web-only-row",
            "title": "a web-only row has no grid tile",
            "source_host": "example.com"
          }
        ]
        """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        engine = newEngine(defaultCorpus = null)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** The overrides are exactly what production DI never sets: test-only endpoint + key. */
    private fun newEngine(defaultCorpus: SearchBackendConfig.CorpusEndpoint?): CorpusSearchEngine {
        val untouchedConfig =
            SearchBackendConfig(
                baseClient = OkHttpClient(),
                json = json,
                remoteUrl = "http://127.0.0.1:1/config.json",
                defaultBaseUrl = "http://127.0.0.1:1",
                defaultCorpus = defaultCorpus,
            )
        return CorpusSearchEngine(
            config = untouchedConfig,
            baseClient = OkHttpClient(),
            json = json,
            overrideCorpusUrl = server.url("/").toString(),
            overrideAnonKey = "test-anon-key",
        )
    }

    @Test
    fun searchQueriesTheCorpusWithANormalizedExactMatchFilter() =
        runTest {
            server.enqueue(MockResponse().setBody(twoRows).setHeader("Content-Type", "application/json"))

            val outcome = engine.search("  Mountain   Wallpaper 4K ", page = 1, filters = GlobalSearchFilters())

            val recorded = server.takeRequest()
            assertEquals("GET", recorded.method)
            val path = recorded.path ?: ""
            assertTrue(path.startsWith("/rest/v1/search_corpus?"))
            // Spaces percent-encode to %20; the bare eq. form is the one
            // the deployed PostgREST actually matches (the double-quoted
            // form silently returns nothing — verified live).
            assertTrue(path.contains("query_norm=eq.mountain%20wallpaper%204k"))
            assertTrue(path.contains("select=image_url%2Cthumb_url%2Cpage_url%2Ctitle%2Csource_host%2Cwidth%2Cheight"))
            assertTrue(path.contains("order=scraped_at.desc%2Cid.desc"))
            assertTrue(path.contains("limit=20"))
            assertFalse("page 1 must ride the default offset", path.contains("offset="))
            assertEquals("test-anon-key", recorded.getHeader("apikey"))
            assertEquals("Bearer test-anon-key", recorded.getHeader("Authorization"))
        }

    @Test
    fun pageTwoRidesItsOffsetWindow() =
        runTest {
            server.enqueue(MockResponse().setBody("[]").setHeader("Content-Type", "application/json"))

            engine.search("mountain wallpaper", page = 3, filters = GlobalSearchFilters())

            val path = server.takeRequest().path ?: ""
            assertTrue(path.contains("offset=40"))
            assertTrue(path.contains("limit=20"))
        }

    @Test
    fun rowsMapOntoWallpapersAndWebOnlyRowsAreSkipped() =
        runTest {
            server.enqueue(MockResponse().setBody(twoRows).setHeader("Content-Type", "application/json"))

            val outcome = engine.search("mountain wallpaper", page = 1, filters = GlobalSearchFilters())

            val page = (outcome as Success).page
            assertEquals(2, page.results.size)
            val lake = page.results[0]
            assertEquals("gs:https://z-cdn.example.com/lake.jpg", lake.id)
            assertEquals(GLOBAL_SEARCH_PROVIDER_ID, lake.providerId)
            // No thumb URL on the row: the image itself is the card (the
            // bridge proxy would add a hop without resizing anything).
            assertEquals("https://z-cdn.example.com/lake.jpg", lake.thumbUrl)
            assertEquals("https://z-cdn.example.com/lake.jpg", lake.fullUrl)
            // source_host is the provenance title, exactly like `source`
            // for bridge results; the long caption is only the fallback.
            assertEquals("unsplash.com", lake.title)
            assertEquals(3000, lake.width)
            assertEquals(2000, lake.height)
            assertNull(lake.sourceUrl)

            val forest = page.results[1]
            assertEquals("https://z-cdn.example.com/t/forest.jpg", forest.thumbUrl)
            assertEquals("https://example.com/forest", forest.sourceUrl)
            assertEquals("alphacoders.com", forest.title)
        }

    @Test
    fun tierVerificationIsExact() =
        runTest {
            val rows =
                """
                [
                  {"image_url": "https://z-cdn.example.com/huge.jpg", "width": 3840, "height": 2160},
                  {"image_url": "https://z-cdn.example.com/small.jpg", "width": 800, "height": 600},
                  {"image_url": "https://z-cdn.example.com/unknown.jpg"}
                ]
                """.trimIndent()
            server.enqueue(MockResponse().setBody(rows).setHeader("Content-Type", "application/json"))

            val outcome =
                engine.search(
                    "mountain wallpaper",
                    page = 1,
                    filters = GlobalSearchFilters(sizeTier = SearchSizeTier.FHD),
                )

            val page = (outcome as Success).page
            // 800x600 fails FHD; unknown dims fail ANY real tier — a
            // wallpaper app that guesses lies to its users.
            assertEquals(listOf("gs:https://z-cdn.example.com/huge.jpg"), page.results.map { it.id })
        }

    @Test
    fun hasMoreRidesTheRawBatchNotTheFilteredOne() =
        runTest {
            // 20 raw rows, most of them sub-FHD — the raw batch must keep
            // infinite scroll alive even when the tier filters almost all.
            val rows =
                (1..20).joinToString(",", "[", "]") { i ->
                    """{"image_url": "https://z-cdn.example.com/r$i.jpg", "width": ${400 + i}, "height": 300}"""
                }
            server.enqueue(MockResponse().setBody(rows).setHeader("Content-Type", "application/json"))

            val outcome =
                engine.search(
                    "mountain wallpaper",
                    page = 1,
                    filters = GlobalSearchFilters(sizeTier = SearchSizeTier.FHD),
                )

            val page = (outcome as Success).page
            assertTrue(page.results.isEmpty())
            assertTrue(page.hasMore)
        }

    @Test
    fun pagesPastTheCorpusDepthAnswerEmptyWithoutARequest() =
        runTest {
            val outcome = engine.search("mountain wallpaper", page = CorpusSearchEngine.MAX_PAGES + 1, filters = GlobalSearchFilters())

            val page = (outcome as Success).page
            assertTrue(page.results.isEmpty())
            assertFalse(page.hasMore)
            assertEquals(CorpusSearchEngine.MAX_PAGES + 1, page.page)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun aCorpusLessBuildFailsTypedWithoutARequest() =
        runTest {
            // No overrides AND no baked-in default: the lane is absent.
            val config =
                SearchBackendConfig(
                    baseClient = OkHttpClient(),
                    json = json,
                    remoteUrl = "http://127.0.0.1:1/config.json",
                    defaultBaseUrl = "http://127.0.0.1:1",
                    defaultCorpus = null,
                )
            val corpusless =
                CorpusSearchEngine(
                    config = config,
                    baseClient = OkHttpClient(),
                    json = json,
                )

            val outcome = corpusless.search("mountain wallpaper", page = 1, filters = GlobalSearchFilters())

            val failure = outcome as Failure
            assertEquals(ImageSearchError.SERVER, failure.error)
            assertEquals("corpus not configured", failure.detail)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun blankQueriesAreRejected() =
        runTest {
            val outcome = engine.search("   ", page = 1, filters = GlobalSearchFilters())

            val failure = outcome as Failure
            assertEquals(ImageSearchError.BAD_RESPONSE, failure.error)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun rateLimitedAnswersTheTypedError() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))

            val outcome = engine.search("mountain wallpaper", page = 1, filters = GlobalSearchFilters())

            assertEquals(ImageSearchError.RATE_LIMITED, (outcome as Failure).error)
        }

    @Test
    fun serverErrorsAnswerTheTypedError() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))

            val outcome = engine.search("mountain wallpaper", page = 1, filters = GlobalSearchFilters())

            val failure = outcome as Failure
            assertEquals(ImageSearchError.SERVER, failure.error)
            assertEquals("HTTP 500", failure.detail)
        }

    @Test
    fun garbageBodiesAnswerTheTypedError() =
        runTest {
            server.enqueue(MockResponse().setBody("this is not json"))

            val outcome = engine.search("mountain wallpaper", page = 1, filters = GlobalSearchFilters())

            assertEquals(ImageSearchError.BAD_RESPONSE, (outcome as Failure).error)
        }

    @Test
    fun anEmptyCorpusIsAnHonestEmptyPage() =
        runTest {
            server.enqueue(MockResponse().setBody("[]").setHeader("Content-Type", "application/json"))

            val outcome = engine.search("never harvested query", page = 1, filters = GlobalSearchFilters())

            val page = (outcome as Success).page
            // An uncovered query is NOT an error — the live lanes carry it.
            assertTrue(page.results.isEmpty())
            assertFalse(page.hasMore)
        }
}
