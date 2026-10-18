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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The Reddit engine's contract, exercised against a scripted MockWebServer
 * standing in for reddit.com: the request shape (multi-subreddit scope,
 * safe-search, the pacing), the listing-to-Wallpaper mapping (escaped
 * preview URLs, thumbnail variants, provenance titles), cursor pagination,
 * the exact tier verification, and the failure taxonomy.
 */
class RedditSearchEngineTest {
    private lateinit var server: MockWebServer
    private lateinit var engine: RedditSearchEngine
    private val json = Json { ignoreUnknownKeys = true }
    private val filters = GlobalSearchFilters(sizeTier = SearchSizeTier.ANY, safeSearch = true)

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

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Zero pacing: the tests exercise the contract, not Reddit's budget.
        engine =
            RedditSearchEngine(
                baseClient = OkHttpClient(),
                json = json,
                overrideBaseUrl = server.url("/").toString().trimEnd('/'),
                minRequestIntervalMs = 0L,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

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
    fun `the request carries the subreddit scope, safe search and the user agent`() =
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
            assertEquals(RedditSearchEngine.USER_AGENT, recorded.getHeader("User-Agent"))
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
                RedditSearchEngine(
                    baseClient = OkHttpClient(),
                    json = json,
                    overrideBaseUrl = server.url("/").toString().trimEnd('/'),
                    minRequestIntervalMs = 200L,
                )
            server.enqueue(MockResponse().setBody(listing))
            server.enqueue(MockResponse().setBody(listing))
            paced.search("one", 1, filters)
            paced.search("two", 1, filters)
            // Both requests fired — behind the pace mutex, with real
            // spacing, because OkHttp runs on a real dispatcher.
            assertEquals(2, server.requestCount)
        }

    // ---- The installed-app OAuth lane (v1.2.25) ----

    private val tokenBody =
        """{"access_token": "tok-1", "token_type": "bearer", "expires_in": 3600, "scope": "*"}"""

    private fun authedEngine(auth: RedditAppAuth): RedditSearchEngine =
        RedditSearchEngine(
            baseClient = OkHttpClient(),
            json = json,
            oauth = auth,
            clientIdProvider = { "my-client-id" },
            overrideBaseUrl = server.url("/").toString().trimEnd('/'),
            minRequestIntervalMs = 0L,
        )

    @Test
    fun `oauth mode mints a token then searches with a bearer header`() =
        runTest {
            val auth =
                RedditAppAuth(
                    baseClient = OkHttpClient(),
                    json = json,
                    deviceIdStore = { "device-id-00000000000000" },
                    overrideTokenBaseUrl = server.url("/").toString().trimEnd('/'),
                )
            engine = authedEngine(auth)

            server.enqueue(MockResponse().setBody(tokenBody))
            server.enqueue(MockResponse().setBody(listing))

            val outcome = engine.search("mountain", 1, filters)

            // Results parse exactly as the anonymous lane's do.
            assertEquals(2, (outcome as Success).page.results.size)

            // Request 1: the token mint.
            val mint = server.takeRequest()
            assertEquals("/api/v1/access_token", mint.path)
            // Request 2: the search, authorized.
            val search = server.takeRequest()
            assertTrue(search.path!!.startsWith("/r/wallpapers"))
            assertEquals("Bearer tok-1", search.getHeader("Authorization"))
        }

    @Test
    fun `the token is minted once and reused across searches`() =
        runTest {
            val auth =
                RedditAppAuth(
                    baseClient = OkHttpClient(),
                    json = json,
                    deviceIdStore = { "device-id-00000000000000" },
                    overrideTokenBaseUrl = server.url("/").toString().trimEnd('/'),
                )
            engine = authedEngine(auth)

            server.enqueue(MockResponse().setBody(tokenBody))
            server.enqueue(MockResponse().setBody(listing))
            server.enqueue(MockResponse().setBody(listing))

            engine.search("mountain", 1, filters)
            engine.search("mountain", 2, filters)

            // One mint + two searches — never a second token request.
            assertEquals(3, server.requestCount)
            assertEquals("/api/v1/access_token", server.takeRequest().path)
            assertEquals("Bearer tok-1", server.takeRequest().getHeader("Authorization"))
            assertEquals("Bearer tok-1", server.takeRequest().getHeader("Authorization"))
        }

    @Test
    fun `a 401 search mints a fresh token and replays once`() =
        runTest {
            val auth =
                RedditAppAuth(
                    baseClient = OkHttpClient(),
                    json = json,
                    deviceIdStore = { "device-id-00000000000000" },
                    overrideTokenBaseUrl = server.url("/").toString().trimEnd('/'),
                )
            engine = authedEngine(auth)

            server.enqueue(MockResponse().setBody(tokenBody)) // first mint
            server.enqueue(MockResponse().setResponseCode(401)) // token refused
            server.enqueue(
                MockResponse().setBody(tokenBody.replace("tok-1", "tok-2")),
            ) // fresh mint
            server.enqueue(MockResponse().setBody(listing)) // the replay

            val outcome = engine.search("mountain", 1, filters)

            assertEquals(2, (outcome as Success).page.results.size)
            assertEquals(4, server.requestCount)
            server.takeRequest() // mint 1
            server.takeRequest() // refused search
            server.takeRequest() // mint 2
            val replay = server.takeRequest()
            assertEquals("Bearer tok-2", replay.getHeader("Authorization"))
        }

    @Test
    fun `a twice-refused token surfaces the server failure never a loop`() =
        runTest {
            val auth =
                RedditAppAuth(
                    baseClient = OkHttpClient(),
                    json = json,
                    deviceIdStore = { "device-id-00000000000000" },
                    overrideTokenBaseUrl = server.url("/").toString().trimEnd('/'),
                )
            engine = authedEngine(auth)

            server.enqueue(MockResponse().setBody(tokenBody))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody(tokenBody.replace("tok-1", "tok-2")))
            server.enqueue(MockResponse().setResponseCode(401))

            val outcome = engine.search("mountain", 1, filters)

            val failure = outcome as Failure
            assertEquals(ImageSearchError.SERVER, failure.error)
            assertEquals("HTTP 401", failure.detail)
            // Mint, refuse, mint, refuse — and STOP.
            assertEquals(4, server.requestCount)
        }

    @Test
    fun `a failed mint falls back to the anonymous request`() =
        runTest {
            val auth =
                RedditAppAuth(
                    baseClient = OkHttpClient(),
                    json = json,
                    deviceIdStore = { "device-id-00000000000000" },
                    overrideTokenBaseUrl = server.url("/").toString().trimEnd('/'),
                )
            engine = authedEngine(auth)

            server.enqueue(MockResponse().setResponseCode(401)) // mint refused
            server.enqueue(MockResponse().setBody(listing)) // anonymous search succeeds

            val outcome = engine.search("mountain", 1, filters)

            assertEquals(2, (outcome as Success).page.results.size)
            server.takeRequest() // the failed mint
            val fallbackSearch = server.takeRequest()
            assertEquals(null, fallbackSearch.getHeader("Authorization"))
            assertTrue(fallbackSearch.path!!.startsWith("/r/wallpapers"))
        }

    @Test
    fun `no client id keeps the anonymous request exactly as before`() =
        runTest {
            engine =
                RedditSearchEngine(
                    baseClient = OkHttpClient(),
                    json = json,
                    oauth =
                        RedditAppAuth(
                            baseClient = OkHttpClient(),
                            json = json,
                            deviceIdStore = { "device-id-00000000000000" },
                            overrideTokenBaseUrl = server.url("/").toString().trimEnd('/'),
                        ),
                    clientIdProvider = { null },
                    overrideBaseUrl = server.url("/").toString().trimEnd('/'),
                    minRequestIntervalMs = 0L,
                )
            server.enqueue(MockResponse().setBody(listing))

            val outcome = engine.search("mountain", 1, filters)

            assertEquals(2, (outcome as Success).page.results.size)
            assertEquals(1, server.requestCount)
            val only = server.takeRequest()
            assertEquals(null, only.getHeader("Authorization"))
        }

    @Test
    fun `oauth requests answer rate limiting with the typed failure`() =
        runTest {
            val auth =
                RedditAppAuth(
                    baseClient = OkHttpClient(),
                    json = json,
                    deviceIdStore = { "device-id-00000000000000" },
                    overrideTokenBaseUrl = server.url("/").toString().trimEnd('/'),
                )
            engine = authedEngine(auth)

            server.enqueue(MockResponse().setBody(tokenBody))
            server.enqueue(MockResponse().setResponseCode(429))

            val outcome = engine.search("mountain", 1, filters)

            assertEquals(ImageSearchError.RATE_LIMITED, (outcome as Failure).error)
        }
}
