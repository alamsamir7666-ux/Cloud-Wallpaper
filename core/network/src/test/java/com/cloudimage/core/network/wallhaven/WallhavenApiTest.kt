package com.cloudimage.core.network.wallhaven

import com.cloudimage.core.network.CloudimageHttpClient
import com.cloudimage.core.network.NetworkError
import com.cloudimage.core.network.NetworkResult
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

class WallhavenApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: WallhavenApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
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
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun searchSendsEveryParameterEncoded() =
        runTest {
            server.enqueue(MockResponse().setBody("{}"))

            api.search(
                WallhavenSearchRequest(
                    query = "mountain sunset",
                    categories = setOf(WallhavenCategory.ANIME),
                    purity = setOf(WallhavenPurity.SFW, WallhavenPurity.SKETCHY),
                    sorting = WallhavenSorting.RANDOM,
                    order = WallhavenOrder.ASC,
                    page = 3,
                    seed = "ab12cd34",
                ),
            )

            val recorded = server.takeRequest()
            assertEquals("/api/v1/search", recorded.path?.substringBefore("?"))
            val query = requireNotNull(recorded.requestUrl)
            assertEquals("mountain sunset", query.queryParameter("q"))
            assertEquals("010", query.queryParameter("categories"))
            assertEquals("110", query.queryParameter("purity"))
            assertEquals("random", query.queryParameter("sorting"))
            assertEquals("asc", query.queryParameter("order"))
            assertEquals("3", query.queryParameter("page"))
            assertEquals("ab12cd34", query.queryParameter("seed"))
        }

    @Test
    fun blankQueryIsOmittedFromTheUrl() =
        runTest {
            server.enqueue(MockResponse().setBody("{}"))

            api.search(WallhavenSearchRequest(query = "   "))

            val query = requireNotNull(server.takeRequest().requestUrl)
            assertNull(query.queryParameter("q"))
            assertEquals("111", query.queryParameter("categories"))
            assertEquals("100", query.queryParameter("purity"))
            assertEquals("toplist", query.queryParameter("sorting"))
            assertEquals("desc", query.queryParameter("order"))
        }

    @Test
    fun searchParsesDataAndMeta() =
        runTest {
            server.enqueue(
                MockResponse().setBody(
                    """
                    {
                      "data": [
                        {
                          "id": "42wq8l",
                          "url": "https://wallhaven.cc/w/42wq8l",
                          "purity": "sfw",
                          "category": "anime",
                          "dimension_x": 1920,
                          "dimension_y": 1080,
                          "resolution": "1920x1080",
                          "colors": ["#000000"],
                          "path": "https://w.wallhaven.cc/full/42/wallhaven-42wq8l.jpg",
                          "thumbs": {
                            "large": "https://w.wallhaven.cc/full/42/wallhaven-42wq8l.jpg",
                            "original": "https://w.wallhaven.cc/full/42/wallhaven-42wq8l.jpg",
                            "small": "https://w.wallhaven.cc/full/42/wallhaven-42wq8l.jpg"
                          },
                          "future_field": "ignored"
                        }
                      ],
                      "meta": {
                        "current_page": 2,
                        "last_page": 500,
                        "per_page": 24,
                        "total": 12000,
                        "query": "sunset",
                        "seed": "ab12cd34"
                      }
                    }
                    """.trimIndent(),
                ),
            )

            val result = api.search(WallhavenSearchRequest(query = "sunset", page = 2))

            val response = (result as NetworkResult.Success).value
            val wallpaper = response.data.single()
            assertEquals("42wq8l", wallpaper.id)
            assertEquals(1920, wallpaper.dimensionX)
            assertEquals(1080, wallpaper.dimensionY)
            assertEquals("https://w.wallhaven.cc/full/42/wallhaven-42wq8l.jpg", wallpaper.path)
            assertEquals("https://wallhaven.cc/w/42wq8l", wallpaper.url)
            assertEquals(2, response.meta.currentPage)
            assertEquals(500, response.meta.lastPage)
            assertEquals("sunset", response.meta.query)
            assertTrue(response.meta.hasNextPage)
        }

    @Test
    fun wallpaperFetchesSingleDto() =
        runTest {
            server.enqueue(
                MockResponse().setBody("""{"id": "9mjoy1", "purity": "sketchy", "file_size": 409600}"""),
            )

            val result = api.wallpaper("9mjoy1")

            assertEquals("/api/v1/w/9mjoy1", server.takeRequest().path)
            val dto = (result as NetworkResult.Success).value
            assertEquals("9mjoy1", dto.id)
            assertEquals("sketchy", dto.purity)
            assertEquals(409600L, dto.fileSize)
        }

    @Test
    fun httpErrorSurfacesAsTypedFailure() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(503))

            val result = api.search(WallhavenSearchRequest())

            val error = (result as NetworkResult.Failure).error
            assertTrue(error is NetworkError.Http)
            assertEquals(503, (error as NetworkError.Http).code)
        }
}
