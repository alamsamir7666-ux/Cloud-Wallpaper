package com.cloudimage.wallpapercave

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
 * Live check against wallpapercave.com, for maintenance: the fixture tests
 * pin the markup shapes, this answers whether the SITE still speaks them.
 *
 * Run on demand with:
 *
 * `WALLPAPERCAVE_LIVE=1 ./gradlew :providers:wallpapercave:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI and
 * plain `check` never touch the network. Requests are sequential and few,
 * exactly what one user browsing the app produces.
 */
class WallpaperCaveLiveCheckTest {
    private val provider = WallpaperCaveWallpaperProvider()

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

    private fun live(): Boolean = System.getenv("WALLPAPERCAVE_LIVE") == "1"

    private fun configured(): WallpaperCaveWallpaperProvider = provider.apply { configure(LiveClient(), ProviderSettings { null }) }

    /** Both fields of every item must carry an original — /uwpr/ is AVIF. */
    private fun assertOriginalUrls(
        where: String,
        urls: List<String>,
    ) {
        assertTrue(
            "$where carries ${urls.count { !it.contains("/wp/") && !it.contains("/uwp/") }} non-original urls",
            urls.all { it.contains("/wp/") || it.contains("/uwp/") },
        )
    }

    @Test
    fun `the latest feed serves original urls on both fields`() =
        runTest {
            assumeTrue(live())

            val page = configured().popular(page = 1).getOrThrow()

            assertTrue("latest page 1 served ${page.wallpapers.size} items", page.wallpapers.size >= 10)
            assertOriginalUrls("latest page 1", page.wallpapers.flatMap { listOf(it.thumbUrl, it.fullUrl) })
        }

    @Test
    fun `the anime category streams past its curated topic`() =
        runTest {
            assumeTrue(live())

            val anime = Filters.of("category" to "anime")
            val pages =
                listOf(1, 2, 3).map { page -> configured().popular(page = page, filters = anime).getOrThrow() }

            assertTrue("curated page 1 served ${pages[0].wallpapers.size}", pages[0].wallpapers.size >= 20)
            assertTrue("stream page 2 served ${pages[1].wallpapers.size}", pages[1].wallpapers.size >= 10)
            assertTrue("page 2 must offer page 3", pages[1].nextPage != null)
            assertTrue("stream page 3 served ${pages[2].wallpapers.size}", pages[2].wallpapers.size >= 10)

            val ids = pages.flatMap { it.wallpapers }.map { it.id }
            assertTrue("ids repeat across pages", ids.size == ids.distinct().size)
            assertOriginalUrls("anime stream", pages.flatMap { p -> p.wallpapers.flatMap { listOf(it.thumbUrl, it.fullUrl) } })
        }

    @Test
    fun `the people category streams past its curated topic`() =
        runTest {
            assumeTrue(live())

            val people = Filters.of("category" to "people")
            val page1 = configured().popular(page = 1, filters = people).getOrThrow()
            val page2 = configured().popular(page = 2, filters = people).getOrThrow()

            assertTrue("curated page 1 served ${page1.wallpapers.size}", page1.wallpapers.size >= 20)
            assertTrue("stream page 2 served ${page2.wallpapers.size}", page2.wallpapers.size >= 10)
            assertTrue("page 2 must offer page 3", page2.nextPage != null)

            val ids = (page1.wallpapers + page2.wallpapers).map { it.id }
            assertTrue("ids repeat across pages", ids.size == ids.distinct().size)
        }

    @Test
    fun `query preset tabs stream live - girls and cars`() =
        runTest {
            assumeTrue(live())

            val girls = listOf(1, 2, 3).map { page -> configured().search("girls", page = page).getOrThrow() }
            assertTrue("girls curated page served ${girls[0].wallpapers.size}", girls[0].wallpapers.size >= 20)
            assertTrue("girls stream page 2 served ${girls[1].wallpapers.size}", girls[1].wallpapers.size >= 10)
            assertTrue("girls page 2 must offer page 3", girls[1].nextPage != null)
            assertTrue("girls stream page 3 served ${girls[2].wallpapers.size}", girls[2].wallpapers.size >= 10)
            val girlsIds = girls.flatMap { it.wallpapers }.map { it.id }
            assertTrue("girls ids repeat across pages", girlsIds.size == girlsIds.distinct().size)
            assertOriginalUrls("girls feed", girls.flatMap { p -> p.wallpapers.flatMap { listOf(it.thumbUrl, it.fullUrl) } })

            val cars = listOf(1, 2).map { page -> configured().search("cars", page = page).getOrThrow() }
            assertTrue("cars curated page served ${cars[0].wallpapers.size}", cars[0].wallpapers.size >= 20)
            assertTrue("cars stream page 2 served ${cars[1].wallpapers.size}", cars[1].wallpapers.size >= 10)
            val carIds = cars.flatMap { it.wallpapers }.map { it.id }
            assertTrue("car ids repeat across pages", carIds.size == carIds.distinct().size)
            assertOriginalUrls("cars feed", cars.flatMap { p -> p.wallpapers.flatMap { listOf(it.thumbUrl, it.fullUrl) } })
        }

    @Test
    fun `search still merges albums`() =
        runTest {
            assumeTrue(live())

            val page = configured().search("naruto", page = 1).getOrThrow()

            assertTrue("search served ${page.wallpapers.size}", page.wallpapers.size >= 10)
            assertOriginalUrls("search", page.wallpapers.flatMap { listOf(it.thumbUrl, it.fullUrl) })
        }
}
