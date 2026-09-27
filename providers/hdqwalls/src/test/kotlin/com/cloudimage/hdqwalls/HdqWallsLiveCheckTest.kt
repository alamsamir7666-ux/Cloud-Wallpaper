package com.cloudimage.hdqwalls

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live check against hdqwalls.com, for maintenance: the fixture tests pin
 * the markup shapes, this answers whether the SITE still speaks them.
 *
 * Run on demand with:
 *
 * `HDQWALLS_LIVE=1 ./gradlew :providers:hdqwalls:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces.
 */
class HdqWallsLiveCheckTest {
    private val provider = HdqWallsWallpaperProvider()

    /**
     * The plugin facade over HttpURLConnection, sending the app's own
     * User-Agent — the same identity [com.cloudimage.core.network] sends
     * and the one the site verifiably serves (it blocks known-bot agents).
     */
    private class LiveClient : ProviderHttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 20_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)",
            )
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val body =
                (if (status in 200..399) connection.inputStream else connection.errorStream)
                    ?.use { it.readBytes() }
                    ?: ByteArray(0)
            connection.disconnect()
            return ProviderHttpResponse(status, emptyMap(), body)
        }
    }

    private fun live(): Boolean = System.getenv("HDQWALLS_LIVE") == "1"

    private fun configured(): HdqWallsWallpaperProvider = provider.apply { configure(LiveClient(), ProviderSettings { null }) }

    @Test
    fun `popular feed answers with real items and a next page`() =
        runTest {
            assumeTrue(live())
            val page = configured().popular(page = 1).getOrThrow()

            assertTrue("expected a full batch, got ${page.wallpapers.size}", page.wallpapers.size >= 15)
            assertEquals(18, page.wallpapers.size)
            val first = page.wallpapers.first()
            assertTrue(first.id.endsWith("-wallpaper"))
            assertTrue(first.thumbUrl.contains("/bthumb/"))
            assertTrue(first.fullUrl.startsWith("https://images.hdqwalls.com/wallpapers/"))
            // Regression guard for the 602x339 bug: the live listing still
            // publishes only its uniform card-crop attrs, so the grid must
            // carry no dimensions — the true ones are details()'s alone.
            assertTrue(
                "grid items must not publish the card-crop dims (${first.width}x${first.height})",
                first.width == null && first.height == null,
            )
            assertTrue("expected a next page", page.nextPage != null)
        }

    @Test
    fun `pages two walks the path pagination without repeating page one`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val page1 = configured.popular(page = 1).getOrThrow()
            val page2 = configured.popular(page = 2).getOrThrow()

            assertTrue(page2.wallpapers.isNotEmpty())
            val ids1 = page1.wallpapers.map { it.id }.toSet()
            val ids2 = page2.wallpapers.map { it.id }
            assertTrue("expected fresh items on page 2", ids2.none { it in ids1 })
        }

    @Test
    fun `the anime category tab walks its listing`() =
        runTest {
            assumeTrue(live())
            val page = configured().popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()

            assertTrue("expected a full batch, got ${page.wallpapers.size}", page.wallpapers.size >= 15)
            assertTrue(page.nextPage != null)
        }

    @Test
    fun `search answers directly and paginates by the query string`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val page1 = configured.search(query = "batman", page = 1).getOrThrow()
            val page2 = configured.search(query = "batman", page = 2).getOrThrow()

            assertTrue("expected a full batch, got ${page1.wallpapers.size}", page1.wallpapers.size >= 15)
            val ids1 = page1.wallpapers.map { it.id }.toSet()
            assertTrue("expected fresh items on page 2", page2.wallpapers.none { it.id in ids1 })
        }

    @Test
    fun `a query-preset shelf rides the site's own search`() =
        runTest {
            assumeTrue(live())
            val page = configured().search(query = "cars", page = 1).getOrThrow()

            assertTrue("expected a full batch, got ${page.wallpapers.size}", page.wallpapers.size >= 15)
            assertTrue(page.nextPage != null)
        }

    @Test
    fun `details reads the definitive live record`() =
        runTest {
            assumeTrue(live())
            val configured = configured()
            val first =
                configured
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .first()

            val details = configured.details(first.id).getOrThrow()

            assertEquals(first.id, details.wallpaper.id)
            assertTrue(details.wallpaper.fullUrl.startsWith("https://images.hdqwalls.com/wallpapers/"))
            val dims = "${details.wallpaper.width}x${details.wallpaper.height}"
            assertTrue("expected true dimensions, got $dims", details.wallpaper.width != null && details.wallpaper.height != null)
            assertTrue(details.sourceUrl!!.endsWith(first.id))
        }

    @Test
    fun `random serves the site's random batch`() =
        runTest {
            assumeTrue(live())
            val wallpapers = configured().random().getOrThrow()

            assertTrue("expected a full batch, got ${wallpapers.size}", wallpapers.size >= 15)
        }
}
