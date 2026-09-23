package com.cloudimage.pexels

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PexelsWallpaperProviderTest {
    private val provider = PexelsWallpaperProvider()

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
        body: String = """{"photos": []}""",
    ): FakeClient =
        FakeClient().apply {
            responder = { ProviderHttpResponse(200, emptyMap(), body.toByteArray()) }
            provider.configure(this, ProviderSettings { if (it == provider.meta.id) key else null })
        }

    @Test
    fun `popular hits curated with the key header`() =
        runTest {
            val client = configure()

            provider.popular(page = 3)

            val (url, headers) = client.requests.single()
            assertEquals("https://api.pexels.com/v1/curated?per_page=30&page=3", url)
            assertEquals("test-key", headers["Authorization"])
        }

    @Test
    fun `search encodes the query`() =
        runTest {
            val client = configure()

            provider.search("mountain lake", page = 1, filters = Filters.None)

            val url = client.requests.single().first
            assertEquals("https://api.pexels.com/v1/search?per_page=30&page=1&query=mountain%20lake", url)
        }

    @Test
    fun `photos map into wallpapers`() =
        runTest {
            configure(
                body =
                    """
                    {"photos": [
                      {"id": 123, "width": 6000, "height": 4000, "alt": "a lake",
                       "photographer": "John Roe", "url": "https://pexels.com/photo/123/",
                       "src": {"medium": "https://img/m.jpg", "original": "https://img/o.jpg"}}
                    ],
                     "next_page": "https://api.pexels.com/v1/curated?page=2"}
                    """.trimIndent(),
            )

            val page = provider.popular(page = 1).getOrThrow()

            val wallpaper = page.wallpapers.single()
            assertEquals("123", wallpaper.id)
            assertEquals("cloudimage.pexels", wallpaper.providerId)
            assertEquals("https://img/m.jpg", wallpaper.thumbUrl)
            assertEquals("https://img/o.jpg", wallpaper.fullUrl)
            assertEquals(2, page.nextPage)
        }

    @Test
    fun `no next_page field means the feed ended`() =
        runTest {
            configure(body = """{"photos": [{"id": 1, "src": {}}]}""")

            val page = provider.popular(page = 5).getOrThrow()

            assertEquals(null, page.nextPage)
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
    fun `blank search fails without a request`() =
        runTest {
            val client = configure()

            val result = provider.search("", page = 1, filters = Filters.None)

            assertTrue(result.isFailure)
            assertTrue(client.requests.isEmpty())
        }
}
