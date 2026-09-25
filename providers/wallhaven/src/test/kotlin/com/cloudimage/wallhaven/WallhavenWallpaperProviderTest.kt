package com.cloudimage.wallhaven

import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wallhaven provider over a scripted fake of the plugin-facing HTTP
 * facade — the URL shapes, content clamps and wire-to-contract mapping
 * all stay covered without a network.
 */
class WallhavenWallpaperProviderTest {
    private val provider = WallhavenWallpaperProvider()

    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()

        var responder: (String) -> ProviderHttpResponse = { ProviderHttpResponse(500, emptyMap(), ByteArray(0)) }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            return responder(url)
        }
    }

    private fun clientWith(
        body: String,
        apiKey: String? = null,
    ): FakeClient =
        FakeClient().apply {
            responder = { ProviderHttpResponse(200, emptyMap(), body.toByteArray()) }
            provider.configure(
                this,
                com.cloudimage.provider.api
                    .ProviderSettings { apiKey },
            )
        }

    @Test
    fun `default query keeps all categories and sfw purity`() {
        val url = provider.buildSearchUrl(query = null, filters = Filters.None, page = 1)

        assertEquals("https://wallhaven.cc/api/v1/search?categories=111&purity=100&sorting=toplist&order=desc&page=1", url)
    }

    @Test
    fun `text query and filters land in the url`() {
        val filters =
            Filters.of(
                "category" to "anime",
                "category" to "people",
                "purity" to "sketchy",
                "sorting" to "date",
                "order" to "asc",
                "seed" to "abcd1234",
            )

        val url = provider.buildSearchUrl(query = "mountain lake", filters = filters, page = 3)

        assertEquals(
            "https://wallhaven.cc/api/v1/search?q=mountain%20lake&categories=011&purity=010" +
                "&sorting=date&order=asc&page=3&seed=abcd1234",
            url,
        )
    }

    @Test
    fun `nsfw purity is never requestable`() {
        val filters = Filters.of("purity" to "sfw", "purity" to "nsfw", "purity" to "sketchy")

        assertEquals("110", provider.purityParam(filters))
    }

    @Test
    fun `empty purity degrades to sfw`() {
        assertEquals("100", provider.purityParam(Filters.None))
    }

    @Test
    fun `popular maps a full page`() =
        runTest {
            val client =
                clientWith(
                    """
                    {
                      "data": [
                        {
                          "id": "42wq8l",
                          "path": "https://w.wallhaven.cc/full/42/wq8l/42wq8l.jpg",
                          "dimension_x": 1920, "dimension_y": 1080,
                          "purity": "sfw",
                          "colors": ["#000000"],
                          "thumbs": {"large": "https://th.large/42wq8l.jpg"}
                        }
                      ],
                      "meta": {"current_page": 1, "last_page": 4}
                    }
                    """.trimIndent(),
                )

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(1, page.wallpapers.size)
            val wallpaper = page.wallpapers.single()
            assertEquals("42wq8l", wallpaper.id)
            assertEquals("cloudimage.wallhaven", wallpaper.providerId)
            assertEquals("https://th.large/42wq8l.jpg", wallpaper.thumbUrl)
            assertEquals(1920, wallpaper.width)
            assertEquals(ContentRating.SFW, wallpaper.contentRating)
            // Colors are NOT tags (v1.0.9): the palette must not masquerade
            // as tag data now that tags mean something.
            assertTrue(wallpaper.tags.isEmpty())
            assertEquals(2, page.nextPage)
            assertEquals(1, client.requests.size)
        }

    @Test
    fun `wire tags become wallpaper tags and blank ones drop`() =
        runTest {
            clientWith(
                """
                {
                  "data": [
                    {
                      "id": "tagged1",
                      "path": "https://w/full/tagged1.jpg",
                      "purity": "sfw",
                      "tags": [
                        {"id": 1, "name": "forest"},
                        {"id": 2, "name": "mist"},
                        {"id": 3, "name": "   "}
                      ]
                    }
                  ],
                  "meta": {"current_page": 1, "last_page": 1}
                }
                """.trimIndent(),
            )

            val page = provider.popular(page = 1).getOrThrow()

            // The detail screen's "More like this" consumes these as query
            // words — blanks are dropped so they never poison the query.
            assertEquals(listOf("forest", "mist"), page.wallpapers.single().tags)
        }

    @Test
    fun `thumb falls back to original then path`() =
        runTest {
            clientWith(
                """
                {"data": [{"id": "a", "path": "https://w/full/a.jpg",
                  "purity": "sketchy", "thumbs": {"original": "https://th.o/a.jpg"}}],
                 "meta": {"current_page": 2, "last_page": 2}}
                """.trimIndent(),
            )

            val page = provider.search("query", page = 2).getOrThrow()

            assertEquals("https://th.o/a.jpg", page.wallpapers.single().thumbUrl)
            assertEquals(ContentRating.SKETCHY, page.wallpapers.single().contentRating)
            assertEquals(null, page.nextPage)
        }

    @Test
    fun `http error degrades to a failure`() =
        runTest {
            provider.configure(
                FakeClient().apply { responder = { ProviderHttpResponse(503, emptyMap(), ByteArray(0)) } },
                com.cloudimage.provider.api
                    .ProviderSettings { null },
            )

            val result = provider.popular(page = 1)

            assertTrue(result.isFailure)
        }

    @Test
    fun `details carries resolution size and source`() =
        runTest {
            clientWith(
                """
                {"id": "42wq8l", "path": "https://w/full.jpg", "purity": "sfw",
                 "resolution": "1920x1080", "file_size": 1048576, "url": "https://wallhaven.cc/w/42wq8l"}
                """.trimIndent(),
            )

            val details = provider.details("42wq8l").getOrThrow()

            assertEquals("1920x1080", details.resolution)
            assertEquals(1048576L, details.fileSizeBytes)
            assertEquals("https://wallhaven.cc/w/42wq8l", details.sourceUrl)
            assertEquals("42wq8l", details.wallpaper.id)
        }

    @Test
    fun `keyless tag suggestions answer empty without firing a request`() =
        runTest {
            clientWith("", apiKey = null)

            val tags = provider.suggestTags("land").getOrThrow()

            assertTrue(tags.isEmpty())
        }

    @Test
    fun `blank tag suggestions never call the api`() =
        runTest {
            val client = clientWith("", apiKey = "user-key")

            val tags = provider.suggestTags("   ").getOrThrow()

            assertTrue(tags.isEmpty())
            assertTrue(client.requests.isEmpty())
        }

    @Test
    fun `tag suggestions come from wallhaven's own endpoint with the key`() =
        runTest {
            val client =
                clientWith(
                    """
                    {"data": [{"id": 1, "name": "landscape"},
                              {"id": 2, "name": "land art"},
                              {"id": 3, "name": ""}]}
                    """.trimIndent(),
                    apiKey = "user-key",
                )

            val tags = provider.suggestTags("land").getOrThrow()

            assertEquals(listOf("landscape", "land art"), tags)
            assertEquals(
                "https://wallhaven.cc/api/v1/tags?apikey=user-key&q=land",
                client.requests.single(),
            )
        }

    @Test
    fun `a failing tag lookup degrades to a failed result`() =
        runTest {
            provider.configure(
                FakeClient().apply { responder = { ProviderHttpResponse(503, emptyMap(), ByteArray(0)) } },
                com.cloudimage.provider.api
                    .ProviderSettings { "user-key" },
            )

            assertTrue(provider.suggestTags("land").isFailure)
        }

    @Test
    fun `unconfigured provider fails instead of crashing`() =
        runTest {
            assertTrue(provider.popular(page = 1).isFailure)
        }
}
