package com.cloudimage.unsplash

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsplashWallpaperProviderTest {
    private val provider = UnsplashWallpaperProvider()

    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        var responder: () -> ProviderHttpResponse = { ProviderHttpResponse(500, emptyMap(), ByteArray(0)) }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url to headers
            return responder()
        }
    }

    private fun configure(
        key: String? = "test-key",
        body: String = "[]",
    ): FakeClient =
        FakeClient().apply {
            responder = { ProviderHttpResponse(200, emptyMap(), body.toByteArray()) }
            provider.configure(this, ProviderSettings { if (it == provider.meta.id) key else null })
        }

    @Test
    fun `popular sends the key as client-id header`() =
        runTest {
            val client = configure()

            provider.popular(page = 2)

            val (url, headers) = client.requests.single()
            assertEquals("https://api.unsplash.com/photos?page=2&per_page=30&order_by=latest", url)
            assertEquals("Client-ID test-key", headers["Authorization"])
        }

    @Test
    fun `search maps filters onto order_by`() =
        runTest {
            val client = configure()

            provider.search("forest", page = 1, filters = Filters.of("sorting" to "date"))

            val url = client.requests.single().first
            assertTrue(url.startsWith("https://api.unsplash.com/search/photos?"))
            assertTrue(url.contains("query=forest"))
            assertTrue(url.contains("order_by=latest"))
        }

    @Test
    fun `blank search fails without a request`() =
        runTest {
            val client = configure()

            val result = provider.search("  ", page = 1, filters = Filters.None)

            assertTrue(result.isFailure)
            assertTrue(client.requests.isEmpty())
        }

    @Test
    fun `photos map into wallpapers with pagination`() =
        runTest {
            configure(
                body =
                    """
                    [
                      {"id": "abc", "width": 4000, "height": 3000, "alt_description": "a forest",
                       "urls": {"small": "https://img/small.jpg", "full": "https://img/full.jpg"},
                       "user": {"name": "Jane Doe"}}
                    ]
                    """.trimIndent(),
            )

            val page = provider.popular(page = 1).getOrThrow()

            val wallpaper = page.wallpapers.single()
            assertEquals("abc", wallpaper.id)
            assertEquals("cloudimage.unsplash", wallpaper.providerId)
            assertEquals("https://img/small.jpg", wallpaper.thumbUrl)
            assertEquals("https://img/full.jpg", wallpaper.fullUrl)
            assertEquals("a forest", wallpaper.title)
            // One item < per_page -> no next page.
            assertEquals(null, page.nextPage)
        }

    @Test
    fun `full page announces the next page`() =
        runTest {
            val photos = (1..30).joinToString(",", prefix = "[", postfix = "]") { """{"id": "p$it", "urls": {}}""" }
            configure(body = photos)

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(30, page.wallpapers.size)
            assertEquals(2, page.nextPage)
        }

    @Test
    fun `missing key fails with a readable error`() =
        runTest {
            val client = configure(key = null)

            val result = provider.popular(page = 1)

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("API key"))
            assertTrue(client.requests.isEmpty())
        }

    @Test
    fun `http error degrades to a failure`() =
        runTest {
            val client =
                FakeClient().apply {
                    responder = { ProviderHttpResponse(401, emptyMap(), ByteArray(0)) }
                    provider.configure(this, ProviderSettings { "test-key" })
                }

            val result = provider.popular(page = 1)

            assertTrue(result.isFailure)
        }
}
