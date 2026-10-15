package com.cloudimage.wallhere

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live check against wallhere.com, for maintenance: the fixture tests
 * pin the markup shapes, this answers whether the SITE still speaks
 * them.
 *
 * Run on demand with:
 *
 * `WALLHERE_LIVE=1 ./gradlew :providers:wallhere:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI
 * and plain `check` never touch the network. NOTE for maintainers: the
 * site's Cloudflare zone refuses datacenter addresses outright (CI
 * runners included), so this check is meant for a developer machine on
 * a residential or carrier IP, where the site serves normally. On such
 * a machine the shared app client's Cloudflare machinery is not even
 * needed; this check rides plain HttpURLConnection.
 */
class WallhereLiveCheckTest {
    private val provider = WallhereWallpaperProvider()

    /**
     * The plugin facade over HttpURLConnection, sending the app's own
     * User-Agent — the same identity [com.cloudimage.core.network]
     * sends.
     */
    private class LiveClient : ProviderHttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)",
            )
            val status = connection.responseCode
            val body =
                if (status in 200..399) {
                    connection.inputStream.use { it.readBytes() }
                } else {
                    ByteArray(0)
                }
            val responseHeaders = connection.headerFields.orEmpty()
            connection.disconnect()
            return ProviderHttpResponse(status, responseHeaders, body)
        }
    }

    @Test
    fun homepageStillSpeaksTheGrid() =
        runTest {
            assumeTrue(System.getenv("WALLHERE_LIVE") == "1")
            provider.configure(LiveClient(), ProviderSettings { null })

            val page = provider.popular(1, Filters.None).getOrThrow()

            assertTrue("homepage grid parsed no wallpapers", page.wallpapers.isNotEmpty())
            assertTrue(
                "grid items must carry CDN thumbnails",
                page.wallpapers.all { it.thumbUrl.contains("wallhere.com") },
            )
            assertTrue("popular feed must offer a next page", page.nextPage != null)
        }

    @Test
    fun searchStillAnswers() =
        runTest {
            assumeTrue(System.getenv("WALLHERE_LIVE") == "1")
            provider.configure(LiveClient(), ProviderSettings { null })

            val page = provider.search("nature", 1, Filters.None).getOrThrow()

            assertTrue("search parsed no wallpapers", page.wallpapers.isNotEmpty())
        }

    @Test
    fun randomStillServesFreshBatches() =
        runTest {
            assumeTrue(System.getenv("WALLHERE_LIVE") == "1")
            provider.configure(LiveClient(), ProviderSettings { null })

            val wallpapers = provider.random().getOrThrow()

            assertTrue("random batch parsed no wallpapers", wallpapers.isNotEmpty())
        }
}
