package com.cloudimage.behance

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Live check against behance.net, for maintenance: the fixture tests pin
 * the state shapes, this answers whether the SITE still serves them —
 * bot gate included, over the same HTTP/2 the app's OkHttp negotiates.
 *
 * Run on demand with:
 *
 * `BEHANCE_LIVE=1 ./gradlew :providers:behance:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI
 * and plain `check` never touch the network. Requests are sequential and
 * few, exactly what one user browsing the app produces.
 */
class BehanceLiveCheckTest {
    private val provider = BehanceWallpaperProvider()

    /**
     * The plugin facade over java.net.http.HttpClient with HTTP/2 forced —
     * the same protocol negotiation OkHttp performs, which the gate
     * demands. HTTP/1.1 loops the challenge forever (verified live).
     */
    private class LiveClient : ProviderHttpClient {
        private val client =
            HttpClient
                .newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20))
                .build()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            val builder =
                HttpRequest
                    .newBuilder()
                    .uri(URI(url))
                    .timeout(Duration.ofSeconds(45))
                    .GET()
            headers.forEach { (name, value) -> builder.header(name, value) }
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            return ProviderHttpResponse(response.statusCode(), response.headers().map(), response.body())
        }
    }

    private fun live() {
        assumeTrue("BEHANCE_LIVE not set", System.getenv("BEHANCE_LIVE") == "1")
        provider.configure(LiveClient(), ProviderSettings { null })
    }

    @Test
    fun popularFeedServesWallpapers() =
        runTest {
            live()
            val page = provider.popular(1, Filters.None).getOrThrow()
            assertTrue("expected wallpapers, got ${page.wallpapers.size}", page.wallpapers.isNotEmpty())
            assertTrue("expected a second page", page.nextPage != null)
            val first = page.wallpapers.first()
            assertTrue(first.id.contains('-'))
            assertTrue(first.thumbUrl.contains("/project_modules/max_632/"))
            assertTrue(first.fullUrl.contains("/project_modules/source/"))
            assertTrue(!first.title.isNullOrBlank())
        }

    @Test
    fun searchPaginatesThroughTheCursor() =
        runTest {
            live()
            val page1 = provider.search("gradient wallpaper", 1, Filters.of("sorting" to "date")).getOrThrow()
            assertTrue(page1.wallpapers.isNotEmpty())
            val page2 = provider.search("gradient wallpaper", 2, Filters.of("sorting" to "date")).getOrThrow()
            assertTrue(page2.wallpapers.isNotEmpty())
            assertTrue(
                "page two must not repeat page one",
                page1.wallpapers
                    .map { it.id }
                    .intersect(page2.wallpapers.map { it.id }.toSet())
                    .isEmpty(),
            )
        }

    @Test
    fun detailsRoundTripsAgainstTheLiveProjectPages() =
        runTest {
            live()
            val page = provider.popular(1, Filters.None).getOrThrow()
            val id = page.wallpapers.first().id
            val details = provider.details(id).getOrThrow()
            assertTrue(details.wallpaper.fullUrl.contains("/project_modules/source/"))
            assertTrue(details.sourceUrl!!.contains("behance.net/gallery/"))
            val resolution = details.resolution
            assertTrue(resolution == null || resolution.contains("×"))
        }

    @Test
    fun sectionsAnswerOffline() =
        runTest {
            live()
            assertTrue(provider.sections().size == 12)
        }
}
