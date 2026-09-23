package com.cloudimage.pixabay

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixabayWallpaperProviderTest {
    private val provider = PixabayWallpaperProvider()

    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()
        var responder: () -> ProviderHttpResponse = { ProviderHttpResponse(500, emptyMap(), ByteArray(0)) }

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            return responder()
        }
    }

    private fun configure(
        key: String? = "test-key",
        body: String = """{"hits": []}""",
    ): FakeClient =
        FakeClient().apply {
            responder = { ProviderHttpResponse(200, emptyMap(), body.toByteArray()) }
            provider.configure(this, ProviderSettings { if (it == provider.meta.id) key else null })
        }

    @Test
    fun `popular keeps the key in the query and search on`() =
        runTest {
            val client = configure()

            provider.popular(page = 2)

            val url = client.requests.single()
            assertTrue(url.startsWith("https://pixabay.com/api/?key=test-key&"))
            assertTrue(url.contains("safesearch=true"))
            assertTrue(url.contains("order=popular"))
            assertTrue(url.contains("page=2"))
            assertTrue(url.contains("image_type=photo"))
        }

    @Test
    fun `date sorting maps to latest order`() =
        runTest {
            val client = configure()

            provider.search("sunset", page = 1, filters = Filters.of("sorting" to "date"))

            assertTrue(client.requests.single().contains("order=latest"))
        }

    @Test
    fun `hits map into wallpapers with tag parsing`() =
        runTest {
            configure(
                body =
                    """
                    {"hits": [
                      {"id": 77, "tags": "sunset, beach, evening",
                       "webformatURL": "https://pixabay.com/w.jpg",
                       "largeImageURL": "https://pixabay.com/l.jpg",
                       "imageWidth": 3840, "imageHeight": 2160, "user": "jane"}
                    ], "totalHits": 500}
                    """.trimIndent(),
            )

            val page = provider.search("sunset", page = 1, filters = Filters.None).getOrThrow()

            val wallpaper = page.wallpapers.single()
            assertEquals("77", wallpaper.id)
            assertEquals("cloudimage.pixabay", wallpaper.providerId)
            assertEquals("https://pixabay.com/w.jpg", wallpaper.thumbUrl)
            assertEquals("https://pixabay.com/l.jpg", wallpaper.fullUrl)
            assertEquals(listOf("sunset", "beach", "evening"), wallpaper.tags)
            assertEquals(3840, wallpaper.width)
        }

    @Test
    fun `full page announces the next page`() =
        runTest {
            val hits = (1..30).joinToString(",", prefix = "[", postfix = "]") { """{"id": $it, "tags": ""}""" }
            configure(body = """{"hits": $hits, "totalHits": 500}""")

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
    fun `blank search fails without a request`() =
        runTest {
            val client = configure()

            val result = provider.search(" ", page = 1, filters = Filters.None)

            assertTrue(result.isFailure)
            assertTrue(client.requests.isEmpty())
        }
}
