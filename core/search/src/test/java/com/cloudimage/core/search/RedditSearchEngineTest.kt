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
 * The keyless Reddit engine's contract (v1.3.3), exercised against
 * scripted MockWebServers standing in for reddit.com's front doors: the
 * request shape (multi-subreddit scope first, the browser identity, NO
 * authorization header of any kind, safe search, the pacing), the
 * listing-to-Wallpaper mapping (escaped preview URLs, thumbnail
 * variants, provenance titles), cursor pagination in both scopes, the
 * whole-site fallback that makes the lane answer ANY query, the
 * front-door ladder with its sticky door, and the failure taxonomy.
 */
class RedditSearchEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: RedditSearchEngine
    private val json = Json { ignoreUnknownKeys = true }
    private val filters = GlobalSearchFilters(sizeTier = SearchSizeTier.ANY, safeSearch = true)

    /** A second front door for the ladder tests; null when unused. */
    private var server2: MockWebServer? = null

    /** A realistic subreddit search listing: two image posts, one link post, one over-18 post. */
    private val listing =
        """
        {
          "kind": "Listing",
          "data": {
            "after": "t3_cursor1",
            "dist": 4,
            "children": [
              {
                "kind": "t3",
                "data": {
                  "title": "Misty Mountain at Dawn [1920x1080]",
                  "subreddit": "wallpapers",
                  "permalink": "/r/wallpapers/comments/abc/misty_mountain/",
                  "url": "https://i.redd.it/mountain.jpg",
                  "post_hint": "image",
                  "is_reddit_media_domain": true,
                  "over_18": false,
                  "preview": {
                    "enabled": true,
                    "images": [
                      {
                        "source": {
                          "url": "https://preview.redd.it/mountain.jpg?width=1920&amp;height=1080&amp;auto=webp&amp;s=abc",
                          "width": 1920,
                          "height": 1080
                        },
                        "resolutions": [
                          {"url": "https://preview.redd.it/mountain.jpg?width=108&amp;height=61&amp;crop=smart&amp;auto=webp&amp;s=abc", "width": 108, "height": 61},
                          {"url": "https://preview.redd.it/mountain.jpg?width=640&amp;height=360&amp;crop=smart&amp;auto=webp&amp;s=abc", "width": 640, "height": 360}
                        ]
                      }
                    ]
                  }
                }
              },
              {
                "kind": "t3",
                "data": {
                  "title": "Amoled black minimal",
                  "subreddit": "Amoledbackgrounds",
                  "permalink": "/r/Amoledbackgrounds/comments/def/amoled/",
                  "url": "https://i.redd.it/amoled.png",
                  "post_hint": "image",
                  "is_reddit_media_domain": true,
                  "over_18": false,
                  "preview": {
                    "enabled": true,
                    "images": [
                      {
                        "source": {
                          "url": "https://preview.redd.it/amoled.png?width=2560&amp;height=1440&amp;auto=webp&amp;s=def",
                          "width": 2560,
                          "height": 1440
                        },
                        "resolutions": [
                          {"url": "https://preview.redd.it/amoled.png?width=320&amp;height=180&amp;crop=smart&amp;auto=webp&amp;s=def", "width": 320, "height": 180}
                        ]
                      }
                    ]
                  }
                }
              },
              {
                "kind": "t3",
                "data": {
                  "title": "Interesting article (not an image)",
                  "subreddit": "wallpaper",
                  "url": "https://example.com/article",
                  "post_hint": "link",
                  "is_reddit_media_domain": false,
                  "over_18": false,
                  "preview": {
                    "enabled": true,
                    "images": [
                      {
                        "source": {"url": "https://preview.redd.it/article.jpg?width=1200&amp;height=630", "width": 1200, "height": 630},
                        "resolutions": []
                      }
                    ]
                  }
                }
              },
              {
                "kind": "t3",
                "data": {
                  "title": "Sketchy post",
                  "subreddit": "wallpapers",
                  "url": "https://i.redd.it/sketchy.jpg",
                  "post_hint": "image",
                  "is_reddit_media_domain": true,
                  "over_18": true,
                  "preview": {
                    "enabled": true,
                    "images": [
                      {
                        "source": {"url": "https://preview.redd.it/sketchy.jpg?width=1000&amp;height=600", "width": 1000, "height": 600},
                        "resolutions": []
                      }
                    ]
                  }
                }
              }
            ]
          }
        }
        """.trimIndent()

    /** The wallpaper subs' answer to a query they hold no image for. */
    private val emptyListing = """{"kind": "Listing", "data": {"after": null, "dist": 0, "children": []}}"""

    /** A site-wide answer: the same shape, its own cursor. */
    private val wideListing = listing.replace("t3_cursor1", "t3_wide1")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Zero pacing: the tests exercise the contract, not Reddit's budget.
        engine = newEngine()
    }

    @After
    fun tearDown() {
        server.shutdown()
        server2?.shutdown()
    }

    private fun host(): String = server.url("/").toString().trimEnd('/')

    private fun newEngine(
        hosts: List<String>? = null,
        interval: Long = 0L,
    ): RedditSearchEngine =
        RedditSearchEngine(
            baseClient = OkHttpClient(),
            json = json,
            overrideHosts = hosts ?: listOf(host()),
            minRequestIntervalMs = interval,
            // The intra-page gaps are pacing, not logic — zeroed so the
            // contract tests run at mock speed.
            ladderGapMs = 0L,
            fallbackGapMs = 0L,
        )

    @Test
    fun `maps image posts with unescaped URLs, thumbnail variants and provenance`() =
        runTest {
            server.enqueue(MockResponse().setBody(listing))
            val outcome = engine.search("mountain", 1, filters)

            val page = (outcome as Success).page
            assertEquals(2, page.results.size)

            val first = page.results[0]
            assertEquals("gs:https://i.redd.it/mountain.jpg", first.id)
            assertEquals("https://i.redd.it/mountain.jpg", first.fullUrl)
            assertEquals(GLOBAL_SEARCH_PROVIDER_ID, first.providerId)
            assertEquals("r/wallpapers", first.title)
            assertEquals(1920, first.width)
            assertEquals(1080, first.height)
            assertEquals("https://www.reddit.com/r/wallpapers/comments/abc/misty_mountain/", first.sourceUrl)
            // The 640 variant — not the 108, not the original.
            assertTrue(first.thumbUrl.startsWith("https://preview.redd.it/mountain.jpg?"))
            assertTrue(first.thumbUrl.contains("width=640"))
            // HTML-escaped entities must be gone.
            assertFalse(first.thumbUrl.contains("&amp;"))
            assertTrue(first.thumbUrl.contains("&auto=webp"))

            // The over-18 post and the link post never surfaced.
            assertTrue(page.results.none { it.fullUrl.contains("sketchy") })
            assertTrue(page.results.none { it.fullUrl.contains("article") })
            assertTrue(page.hasMore)
        }

    @Test
    fun `falls back to the preview source when the post URL is not a direct image`() =
        runTest {
            val crosspost =
                listing.replace("\"url\": \"https://i.redd.it/mountain.jpg\"", "\"url\": \"https://example.com/gallery/mountain\"")
            server.enqueue(MockResponse().setBody(crosspost))
            val outcome = engine.search("mountain", 1, filters)

            val first = (outcome as Success).page.results[0]
            assertEquals("gs:https://preview.redd.it/mountain.jpg?width=1920&height=1080&auto=webp&s=abc", first.id)
            assertEquals(first.id.substringAfter("gs:"), first.fullUrl)
        }

    @Test
    fun `the request carries the subreddit scope, safe search and the browser identity`() =
        runTest {
            server.enqueue(MockResponse().setBody(listing))
            engine.search("mountain", 1, filters)

            val recorded = server.takeRequest()
            val path = recorded.path.orEmpty()
            assertTrue(path.startsWith("/r/${RedditSearchEngine.SUBREDDITS}/search.json"))
            assertTrue(path.contains("q=mountain"))
            assertTrue(path.contains("restrict_sr=1"))
            assertTrue(path.contains("include_over_18=off"))
            assertTrue(path.contains("limit=100"))
            assertTrue(path.contains("raw_json=1"))
            // The stock signed-out browser agent — never a bot-styled one.
            assertEquals(RedditSearchEngine.USER_AGENT, recorded.getHeader("User-Agent"))
        }

    @Test
    fun `no authorization header is ever sent - the lane is keyless`() =
        runTest {
            server.enqueue(MockResponse().setBody(listing))
            engine.search("mountain", 1, filters)

            // No bearer token, no basic auth, no api key, no client id —
            // the signed-out browser's request is the whole identity.
            assertNull(server.takeRequest().getHeader("Authorization"))
        }

    @Test
    fun `page two rides the cursor page one earned`() =
        runTest {
            server.enqueue(MockResponse().setBody(listing))
            server.enqueue(MockResponse().setBody(listing))
            engine.search("mountain", 1, filters)
            engine.search("mountain", 2, filters)

            server.takeRequest()
            val second = server.takeRequest()
            assertTrue(second.path.orEmpty().contains("after=t3_cursor1"))
        }

    @Test
    fun `a null cursor ends the feed`() =
        runTest {
            val ended = listing.replace("\"after\": \"t3_cursor1\"", "\"after\": null")
            server.enqueue(MockResponse().setBody(ended))
            val outcome = engine.search("mountain", 1, filters)

            val page = (outcome as Success).page
            assertFalse(page.hasMore)

            // A page past the end answers empty rather than repeating page one.
            server.enqueue(MockResponse().setBody(listing))
            val pastEnd = engine.search("mountain", 2, filters)
            assertTrue((pastEnd as Success).page.results.isEmpty())
        }

    @Test
    fun `the size tier verifies exactly against preview dimensions`() =
        runTest {
            server.enqueue(MockResponse().setBody(listing))
            val fhd = filters.copy(sizeTier = SearchSizeTier.FHD)
            val outcome = engine.search("mountain", 1, fhd)

            val page = (outcome as Success).page
            // Both image posts clear FHD: 1920x1080 and 2560x1440 (its
            // short side, 1440, is above the tier's 1080 floor).
            assertEquals(
                listOf(
                    "gs:https://i.redd.it/mountain.jpg",
                    "gs:https://i.redd.it/amoled.png",
                ),
                page.results.map { it.id },
            )
        }

    @Test
    fun `tier-filtered subs results keep the subs chain - the fallback is a scope verdict`() =
        runTest {
            server.enqueue(MockResponse().setBody(listing))
            // UHD filters out every image the subs answered with — but the
            // subs DO cover the query, so the whole-site ask never fires.
            val uhd = filters.copy(sizeTier = SearchSizeTier.UHD)
            val outcome = engine.search("mountain", 1, uhd)

            val page = (outcome as Success).page
            assertTrue(page.results.isEmpty())
            // The subs' own cursor keeps the walk alive toward UHD posts.
            assertTrue(page.hasMore)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `an empty subs page asks the whole site - the lane answers any query`() =
        runTest {
            server.enqueue(MockResponse().setBody(emptyListing))
            server.enqueue(MockResponse().setBody(wideListing))

            val outcome = engine.search("indian women", 1, filters)

            val page = (outcome as Success).page
            // The site-wide listing's two image posts surfaced.
            assertEquals(2, page.results.size)
            assertTrue(page.hasMore)

            val subsAsk = server.takeRequest()
            assertTrue(subsAsk.path.orEmpty().startsWith("/r/${RedditSearchEngine.SUBREDDITS}/search.json"))
            val siteWideAsk = server.takeRequest()
            val widePath = siteWideAsk.path.orEmpty()
            assertTrue(widePath.startsWith("/search.json"))
            assertTrue(widePath.contains("q=indian+women"))
            assertFalse(widePath.contains("restrict_sr"))
            assertTrue(widePath.contains("include_over_18=off"))
        }

    @Test
    fun `a wide query's page two continues the site-wide chain`() =
        runTest {
            server.enqueue(MockResponse().setBody(emptyListing))
            server.enqueue(MockResponse().setBody(wideListing))
            server.enqueue(MockResponse().setBody(wideListing))
            engine.search("indian women", 1, filters)
            val outcome = engine.search("indian women", 2, filters)

            assertEquals(2, (outcome as Success).page.results.size)
            assertEquals(3, server.requestCount)

            server.takeRequest() // the subs ask
            server.takeRequest() // the site-wide page 1
            val pageTwo = server.takeRequest()
            val path = pageTwo.path.orEmpty()
            assertTrue(path.startsWith("/search.json"))
            assertTrue(path.contains("after=t3_wide1"))
            assertFalse(path.contains("restrict_sr"))
        }

    @Test
    fun `a failed site-wide ask degrades to the subs' own honest page`() =
        runTest {
            server.enqueue(MockResponse().setBody(emptyListing))
            server.enqueue(MockResponse().setResponseCode(429))

            val outcome = engine.search("indian women", 1, filters)

            // The subs' page stands: empty, ended — never a failure the
            // merged engine would have to fold away.
            val page = (outcome as Success).page
            assertTrue(page.results.isEmpty())
            assertFalse(page.hasMore)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun `the ladder advances past a refusing door and sticks to the door that answered`() =
        runTest {
            val second = MockWebServer()
            second.start()
            server2 = second
            engine = newEngine(hosts = listOf(host(), second.url("/").toString().trimEnd('/')))

            // Door one refuses; door two answers.
            server.enqueue(MockResponse().setResponseCode(403).setBody("<html>blocked</html>"))
            second.enqueue(MockResponse().setBody(listing))
            val outcome = engine.search("mountain", 1, filters)
            assertEquals(2, (outcome as Success).page.results.size)
            assertEquals(1, server.requestCount)
            assertEquals(1, second.requestCount)

            // The next search starts at the door that last answered.
            second.enqueue(MockResponse().setBody(listing))
            engine.search("valley", 1, filters)
            assertEquals(1, server.requestCount)
            assertEquals(2, second.requestCount)
        }

    @Test
    fun `a 404 fails without touring the ladder - no door answers a bad path differently`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))
            val outcome = engine.search("mountain", 1, filters)

            val failure = outcome as Failure
            assertEquals(ImageSearchError.SERVER, failure.error)
            assertEquals("HTTP 404", failure.detail)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `every door answering 429 surfaces RATE_LIMITED`() =
        runTest {
            val second = MockWebServer()
            second.start()
            server2 = second
            engine = newEngine(hosts = listOf(host(), second.url("/").toString().trimEnd('/')))

            server.enqueue(MockResponse().setResponseCode(429))
            second.enqueue(MockResponse().setResponseCode(429))
            val outcome = engine.search("mountain", 1, filters)

            assertEquals(ImageSearchError.RATE_LIMITED, (outcome as Failure).error)
            assertEquals(1, server.requestCount)
            assertEquals(1, second.requestCount)
        }

    @Test
    fun `HTTP 429 answers RATE_LIMITED`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))
            val outcome = engine.search("mountain", 1, filters)
            assertEquals(ImageSearchError.RATE_LIMITED, (outcome as Failure).error)
        }

    @Test
    fun `an HTTP error status answers SERVER with the code`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(403).setBody("<html>blocked</html>"))
            val outcome = engine.search("mountain", 1, filters)
            val failure = outcome as Failure
            assertEquals(ImageSearchError.SERVER, failure.error)
            assertEquals("HTTP 403", failure.detail)
        }

    @Test
    fun `a malformed body answers BAD_RESPONSE`() =
        runTest {
            server.enqueue(MockResponse().setBody("<html>not json</html>"))
            val outcome = engine.search("mountain", 1, filters)
            assertEquals(ImageSearchError.BAD_RESPONSE, (outcome as Failure).error)
        }

    @Test
    fun `a blank query fails without a request`() =
        runTest {
            val outcome = engine.search("   ", 1, filters)
            assertEquals(ImageSearchError.BAD_RESPONSE, (outcome as Failure).error)
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `unknown dimensions never pass a real tier`() =
        runTest {
            val noDims =
                listing
                    .replace("\"width\": 1920,", "\"width\": 0,")
                    .replace("\"width\": 2560,", "\"width\": 0,")
            server.enqueue(MockResponse().setBody(noDims))
            val fhd = filters.copy(sizeTier = SearchSizeTier.FHD)
            val outcome = engine.search("mountain", 1, fhd)

            assertTrue((outcome as Success).page.results.isEmpty())
        }

    @Test
    fun `ids are stable across identical pages`() =
        runTest {
            server.enqueue(MockResponse().setBody(listing))
            server.enqueue(MockResponse().setBody(listing))
            val first = (engine.search("mountain", 1, filters) as Success).page
            val second = (engine.search("mountain", 1, filters) as Success).page
            assertEquals(first.results.map { it.id }, second.results.map { it.id })
        }

    @Test
    fun `pacing spaces consecutive requests`() =
        runTest {
            val paced =
                newEngine(interval = 200L)
            server.enqueue(MockResponse().setBody(listing))
            server.enqueue(MockResponse().setBody(listing))
            paced.search("one", 1, filters)
            paced.search("two", 1, filters)
            // Both requests fired — behind the pace mutex, with real
            // spacing, because OkHttp runs on a real dispatcher.
            assertEquals(2, server.requestCount)
        }
}
