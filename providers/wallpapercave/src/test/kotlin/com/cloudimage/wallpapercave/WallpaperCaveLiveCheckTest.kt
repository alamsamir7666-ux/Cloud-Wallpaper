package com.cloudimage.wallpapercave

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
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

    /**
     * Field shape per the v1.2.1 contract: `fullUrl` must carry an
     * original path (`/wp/` or `/uwp/` — the multi-megabyte file downloads
     * and the detail screen's display tiers derive from it), while
     * `thumbUrl` must carry a preview family (`/fuwp/` or `/dwp1x/` — the
     * card-sized renders grids ride). An original-as-thumb is the v1.2.0
     * starvation bug; a family-less thumb is a parser drift. (v1.2.28 fix:
     * this used to demand originals on BOTH fields, which the v1.2.1
     * preview-thumbs design itself made unmeetable — every live run
     * failed misleadingly while the provider was healthy.)
     */
    private fun assertFieldShapes(
        where: String,
        wallpapers: List<Wallpaper>,
    ) {
        assertTrue(
            "$where carries ${wallpapers.count { !it.fullUrl.contains("/wp/") && !it.fullUrl.contains("/uwp/") }} non-original fullUrls",
            wallpapers.all { it.fullUrl.contains("/wp/") || it.fullUrl.contains("/uwp/") },
        )
        val nonPreviewThumbs = wallpapers.count { !it.thumbUrl.contains("/fuwp/") && !it.thumbUrl.contains("/dwp1x/") }
        assertTrue(
            "$where carries $nonPreviewThumbs non-preview thumbUrls",
            wallpapers.all { it.thumbUrl.contains("/fuwp/") || it.thumbUrl.contains("/dwp1x/") },
        )
    }

    @Test
    fun `the latest feed rides preview thumbs over original fulls`() =
        runTest {
            assumeTrue(live())

            val page = configured().popular(page = 1).getOrThrow()

            assertTrue("latest page 1 served ${page.wallpapers.size} items", page.wallpapers.size >= 10)
            assertFieldShapes("latest page 1", page.wallpapers)
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
            assertFieldShapes("anime stream", pages.flatMap { p -> p.wallpapers })
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
            assertFieldShapes("girls feed", girls.flatMap { it.wallpapers })

            val cars = listOf(1, 2).map { page -> configured().search("cars", page = page).getOrThrow() }
            assertTrue("cars curated page served ${cars[0].wallpapers.size}", cars[0].wallpapers.size >= 20)
            assertTrue("cars stream page 2 served ${cars[1].wallpapers.size}", cars[1].wallpapers.size >= 10)
            val carIds = cars.flatMap { it.wallpapers }.map { it.id }
            assertTrue("car ids repeat across pages", carIds.size == carIds.distinct().size)
            assertFieldShapes("cars feed", cars.flatMap { it.wallpapers })
        }

    @Test
    fun `search still merges albums`() =
        runTest {
            assumeTrue(live())

            val page = configured().search("naruto", page = 1).getOrThrow()

            assertTrue("search served ${page.wallpapers.size}", page.wallpapers.size >= 10)
            assertFieldShapes("search", page.wallpapers)
        }
}
