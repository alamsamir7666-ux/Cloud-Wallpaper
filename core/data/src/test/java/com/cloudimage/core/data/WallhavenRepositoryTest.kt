package com.cloudimage.core.data

import com.cloudimage.core.data.repository.WallhavenRepositoryImpl
import com.cloudimage.core.model.ContentRating
import com.cloudimage.core.model.WallpaperQuery
import com.cloudimage.core.model.WallpaperSorting
import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
import com.cloudimage.core.network.wallhaven.WallhavenApi
import kotlinx.coroutines.test.runTest
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

/**
 * The repository is tested against a real [WallhavenApi] pointed at a
 * MockWebServer, so the clamp and the mapping are verified as they will run
 * in production — including the exact query parameters on the wire.
 */
class WallhavenRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: WallhavenRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository =
            WallhavenRepositoryImpl(
                api =
                    WallhavenApi(
                        client =
                            CloudimageHttpClient(
                                okHttpClient = OkHttpClient(),
                                json =
                                    Json {
                                        ignoreUnknownKeys = true
                                        coerceInputValues = true
                                    },
                            ),
                        baseUrl = server.url("/api/v1").toString(),
                    ),
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun searchMapsWallpapersAndComputesNextPage() =
        runTest {
            server.enqueue(searchBody(ids = listOf("a1", "a2"), currentPage = 1, lastPage = 4))

            val result = repository.search(WallpaperQuery(), page = 1)

            val page = (result as NetworkResult.Success).value
            assertEquals(listOf("a1", "a2"), page.wallpapers.map { it.id })
            assertEquals(2, page.nextPage)
            val first = page.wallpapers.first()
            assertEquals("wallhaven", first.providerId)
            assertEquals("https://w.wallhaven.cc/full/a1/large.jpg", first.thumbUrl)
            assertEquals("https://w.wallhaven.cc/full/a1/original.jpg", first.fullUrl)
            assertEquals(1920, first.width)
            assertEquals(1200, first.height)
            assertEquals("https://wallhaven.cc/w/a1", first.sourceUrl)
            assertEquals(ContentRating.SFW, first.contentRating)
        }

    @Test
    fun lastPageHasNoNextPage() =
        runTest {
            server.enqueue(searchBody(ids = listOf("z9"), currentPage = 9, lastPage = 9))

            val result = repository.search(WallpaperQuery(), page = 9)

            assertNull((result as NetworkResult.Success).value.nextPage)
        }

    @Test
    fun sketchyPurityIsMappedAndRequested() =
        runTest {
            server.enqueue(searchBody(ids = listOf("s1"), currentPage = 1, lastPage = 2, purity = "sketchy"))

            repository.search(
                WallpaperQuery(contentRatings = setOf(ContentRating.SFW, ContentRating.SKETCHY)),
                page = 1,
            )

            val query = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("110", query.queryParameter("purity"))
        }

    @Test
    fun nsfwIsNeverRequestedAndDegradesToSfw() =
        runTest {
            server.enqueue(searchBody(ids = listOf("n1"), currentPage = 1, lastPage = 1))

            repository.search(WallpaperQuery(contentRatings = setOf(ContentRating.NSFW)), page = 1)

            val query = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("100", query.queryParameter("purity"))
        }

    @Test
    fun emptyCategoriesFallBackToAllCategories() =
        runTest {
            server.enqueue(searchBody(ids = listOf("c1"), currentPage = 1, lastPage = 1))

            repository.search(WallpaperQuery(categories = emptySet()), page = 1)

            val query = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("111", query.queryParameter("categories"))
        }

    @Test
    fun sortingAndSeedTravelThrough() =
        runTest {
            server.enqueue(searchBody(ids = listOf("r1"), currentPage = 1, lastPage = 2))

            repository.search(
                WallpaperQuery(sorting = WallpaperSorting.RANDOM, seed = "seed1234"),
                page = 1,
            )

            val query = requireNotNull(server.takeRequest().requestUrl)
            assertEquals("random", query.queryParameter("sorting"))
            assertEquals("seed1234", query.queryParameter("seed"))
        }

    @Test
    fun searchPropagatesHttpFailure() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429))

            val result = repository.search(WallpaperQuery(), page = 1)

            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Http)
            assertEquals(429, (error as NetworkError.Http).code)
        }

    @Test
    fun getWallpaperMapsDetails() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "id": "9mjoy1",
                      "url": "https://wallhaven.cc/w/9mjoy1",
                      "purity": "sketchy",
                      "dimension_x": 3840,
                      "dimension_y": 2160,
                      "resolution": "3840x2160",
                      "file_size": 5242880,
                      "path": "https://w.wallhaven.cc/full/9m/wallhaven-9mjoy1.jpg"
                    }
                    """.trimIndent(),
                ),
            )

            val result = repository.getWallpaper("9mjoy1")

            assertEquals("/api/v1/w/9mjoy1", server.takeRequest().path)
            val details = (result as NetworkResult.Success).value
            assertEquals("9mjoy1", details.wallpaper.id)
            assertEquals(ContentRating.SKETCHY, details.wallpaper.contentRating)
            assertEquals("3840x2160", details.resolution)
            assertEquals(5242880L, details.fileSizeBytes)
        }

    private fun searchBody(
        ids: List<String>,
        currentPage: Int,
        lastPage: Int,
        purity: String = "sfw",
    ): MockResponse {
        val data =
            ids.joinToString(separator = ", ") { id ->
                // language=JSON
                """
                {
                  "id": "$id",
                  "url": "https://wallhaven.cc/w/$id",
                  "purity": "$purity",
                  "dimension_x": 1920,
                  "dimension_y": 1200,
                  "resolution": "1920x1200",
                  "path": "https://w.wallhaven.cc/full/$id/original.jpg",
                  "thumbs": {
                    "large": "https://w.wallhaven.cc/full/$id/large.jpg",
                    "original": "https://w.wallhaven.cc/full/$id/original.jpg",
                    "small": "https://w.wallhaven.cc/full/$id/small.jpg"
                  }
                }
                """.trimIndent()
            }
        return MockResponse().setBody(
            """
            {
              "data": [$data],
              "meta": {
                "current_page": $currentPage,
                "last_page": $lastPage,
                "per_page": ${ids.size},
                "total": ${ids.size * lastPage}
              }
            }
            """.trimIndent(),
        )
    }
}
