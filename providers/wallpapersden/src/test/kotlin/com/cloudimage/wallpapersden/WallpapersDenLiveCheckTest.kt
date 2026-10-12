package com.cloudimage.wallpapersden

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
 * Live check against wallpapersden.com, for maintenance: the fixture tests
 * pin the markup shapes, this answers whether the SITE still speaks them.
 *
 * Run on demand with:
 *
 * `WALLPAPERSDEN_LIVE=1 ./gradlew :providers:wallpapersden:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces.
 *
 * The site's Cloudflare config hard-blocks datacenter IPs outright (its
 * "Sorry, you have been blocked" page answers every request from one, with
 * any User-Agent), so this check only passes from a residential or mobile
 * connection — the environment the app itself runs in, where the host's
 * clearance ladder also sits behind every request. From a datacenter shell
 * the first check fails with HTTP 403 and a Cloudflare error body: that is
 * the block, not a broken parser.
 */
class WallpapersDenLiveCheckTest {
    private val provider = WallpapersDenWallpaperProvider()

    /**
     * The plugin facade over HttpURLConnection, sending the app's own
     * User-Agent — the same identity [com.cloudimage.core.network] sends.
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

    private fun live(): Boolean = System.getenv("WALLPAPERSDEN_LIVE") == "1"

    private fun configured(): WallpapersDenWallpaperProvider = provider.apply { configure(LiveClient(), ProviderSettings { null }) }

    @Test
    fun `popular feed answers with real items and a next page`() =
        runTest {
            assumeTrue(live())
            val page = configured().popular(page = 1).getOrThrow()

            assertTrue("expected a full batch, got ${page.wallpapers.size}", page.wallpapers.size >= 15)
            val first = page.wallpapers.first()
            // The id is the page slug — the site's stable address.
            assertTrue(first.id.matches(Regex("[a-z0-9][a-z0-9-]*")))
            assertTrue(first.thumbUrl.startsWith("https://images.wallpapersden.com/image/ws-"))
            assertTrue(first.fullUrl.startsWith("https://images.wallpapersden.com/image/download/"))
            // Regression guard for the card-crop trap: the live listing
            // publishes only its uniform 320x240 attrs, so the grid must
            // carry no dimensions — the true ones are details()'s alone.
            assertTrue(
                "grid items must not publish the card-crop dims (${first.width}x${first.height})",
                first.width == null && first.height == null,
            )
            assertTrue("expected a next page", page.nextPage != null)
        }

    @Test
    fun `page two walks the path pagination without repeating page one`() =
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
            val page1 = configured.search(query = "spider", page = 1).getOrThrow()
            val page2 = configured.search(query = "spider", page = 2).getOrThrow()

            // THE unverified-from-a-datacenter-IP piece: the search
            // pagination's wire shape. If this fails while page 1 answers,
            // the site paginates search differently than its Laravel
            // siblings — check the bar's own links and adjust searchPage().
            assertTrue("expected a full batch, got ${page1.wallpapers.size}", page1.wallpapers.size >= 10)
            val ids1 = page1.wallpapers.map { it.id }.toSet()
            assertTrue("expected fresh items on page 2", page2.wallpapers.none { it.id in ids1 })
        }

    @Test
    fun `an exact category term walks the site's own listing`() =
        runTest {
            assumeTrue(live())
            val page = configured().search(query = "games", page = 1).getOrThrow()

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
            assertTrue(details.wallpaper.fullUrl.startsWith("https://images.wallpapersden.com/image/download/"))
            val dims = "${details.wallpaper.width}x${details.wallpaper.height}"
            assertTrue("expected true dimensions, got $dims", details.wallpaper.width != null && details.wallpaper.height != null)
            assertTrue(details.sourceUrl!!.endsWith("${first.id}-wallpaper/"))
        }

    @Test
    fun `a constructed download URL resolves on the CDN`() =
        runTest {
            assumeTrue(live())
            val first =
                configured()
                    .popular(page = 1)
                    .getOrThrow()
                    .wallpapers
                    .first()

            // The grid item's fullUrl is CONSTRUCTED (the token minted for
            // the 4K render) rather than read off a page: prove the CDN
            // serves it, with a one-byte range request so the check costs
            // a round trip, not a download.
            val client = LiveClient()
            val response = client.get(first.fullUrl, mapOf("Range" to "bytes=0-0"))

            assertTrue(
                "constructed URL answered HTTP ${response.statusCode}",
                response.statusCode in 200..206,
            )
        }

    @Test
    fun `random serves the site's random batch`() =
        runTest {
            assumeTrue(live())
            val wallpapers = configured().random().getOrThrow()

            assertTrue("expected a full batch, got ${wallpapers.size}", wallpapers.size >= 15)
        }
}
