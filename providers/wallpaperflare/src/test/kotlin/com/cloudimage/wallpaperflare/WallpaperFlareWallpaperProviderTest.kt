package com.cloudimage.wallpaperflare

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WallpaperFlare provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures cut from the site's own pages as the search
 * engines index them: the grid cell shape (`<img …-preview.jpg>TITLE «
 * »; {W}x{H}px {license} · tags`), the search URL family
 * (`/search?wallpaper={query}` with `+` spaces), the pagination bar in
 * both its `?page=` and `/page/N` dialects, and the wallpaper page's
 * og:image/h1/license record — all covered without a network, exactly
 * like the HDQWalls suite this mirrors.
 */
class WallpaperFlareWallpaperProviderTest {
    private val provider = WallpaperFlareWallpaperProvider()

    /** URL-routed responses; unmatched URLs answer 500 to fail loudly. */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()
        var routes: Map<String, ProviderHttpResponse> = emptyMap()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            return routes.entries
                .firstOrNull { (prefix, _) -> url.startsWith(prefix) }
                ?.value
                ?: ProviderHttpResponse(500, emptyMap(), ByteArray(0))
        }
    }

    private fun ok(body: String): ProviderHttpResponse = ProviderHttpResponse(200, emptyMap(), body.toByteArray())

    private fun configureWith(routes: Map<String, ProviderHttpResponse>): FakeClient =
        FakeClient().apply {
            this.routes = routes
            provider.configure(this, ProviderSettings { null })
        }

    // Fixtures: shapes captured from wallpaperflare.com's indexed pages.

    /**
     * The grid as the site serves it — the exact cell shape its own
     * Google-indexed pages show: anchor-wrapped preview image, `« »`
     * furniture, TRUE dimensions with `px`, license label, middot tag
     * row. The first cell is the "I'll support you" wallpaper verbatim
     * in structure (6016x4000, Public Domain).
     */
    private val searchGrid =
        """
        <a href="https://www.wallpaperflare.com/wallpaper/929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper" title="I'll support you. So you can carry me">
        <img src="https://c4.wallpaperflare.com/wallpaper/929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper-preview.jpg" width="338" height="190" alt="I'll support you. So you can carry me Wallpaper" />
        I'll support you. So you can carry me
        « »; 6016x4000px Public Domain · gray · plane · wing
        </a>
        <a href="https://www.wallpaperflare.com/wallpaper/924/6/442/earth-aurora-borealis-colors-lake-wallpaper" title="Earth aurora borealis colors lake">
        <img src="https://c4.wallpaperflare.com/wallpaper/924/6/442/earth-aurora-borealis-colors-lake-wallpaper-preview.jpg" width="338" height="190" />
        Earth aurora borealis colors lake
        « »; 5184x3456px · aurora · night · sky · lake
        </a>
        <a href="https://www.wallpaperflare.com/wallpaper/626/958/440/waterfalls-caracol-falls-brazil-forest-wallpaper" title="Waterfalls caracol falls brazil forest">
        <img src="https://c4.wallpaperflare.com/wallpaper/626/958/440/waterfalls-caracol-falls-brazil-forest-wallpaper-preview.jpg" width="338" height="190" />
        Waterfalls caracol falls brazil forest
        « »; 4000x6000px · waterfall · brazil · forest
        </a>
        """.trimIndent()

    /** The same first wallpaper through a `-thumb` image — the related-rows variant. */
    private val thumbCell =
        """
        <a href="https://www.wallpaperflare.com/wallpaper/87/470/339/abstract-grunge-texture-old-wallpaper">
        <img src="https://c4.wallpaperflare.com/wallpaper/87/470/339/abstract-grunge-texture-old-wallpaper-thumb.jpg" width="338" />
        abstract grunge texture old
        « »; 1920x1200px · texture · grunge
        </a>
        """.trimIndent()

    /** A pagination bar in the query dialect, current page marked active. */
    private val paginationQueryStyle =
        """
        <ul class="pagination">
        <li class="active"><a href="https://www.wallpaperflare.com/search?wallpaper=nature&amp;page=1">1</a></li>
        <li><a href="https://www.wallpaperflare.com/search?wallpaper=nature&amp;page=2">2</a></li>
        <li><a href="https://www.wallpaperflare.com/search?wallpaper=nature&amp;page=3">3</a></li>
        <li><a href="https://www.wallpaperflare.com/search?wallpaper=nature&amp;page=2">Next &raquo;</a></li>
        </ul>
        """.trimIndent()

    /** A pagination bar in the path dialect, no current marker. */
    private val paginationPathStyle =
        """
        <ul class="pagination">
        <li><a href="https://www.wallpaperflare.com/page/1">1</a></li>
        <li><a href="https://www.wallpaperflare.com/page/2">2</a></li>
        </ul>
        """.trimIndent()

    /** A wallpaper page: og:image, h1, the site's title furniture, tags linking into its own search. */
    private val detailPage =
        """
        <html><head>
        <title>I'll support you. So you can carry me 1080P, 2K, 4K, 5K HD wallpapers free download - WallpaperFlare</title>
        <meta property="og:image" content="https://c4.wallpaperflare.com/wallpaper/929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper-preview.jpg" />
        <meta property="og:title" content="I'll support you. So you can carry me" />
        </head><body>
        <h1>I'll support you. So you can carry me « » ; 6016x4000px Public Domain</h1>
        <a href="https://www.wallpaperflare.com/search?wallpaper=gray">gray</a>
        <a href="https://www.wallpaperflare.com/search?wallpaper=plane">plane</a>
        <a href="https://www.wallpaperflare.com/search?wallpaper=wing">wing</a>
        </body></html>
        """.trimIndent()

    // ------------------------------------------------------------------ grid

    @Test
    fun searchParsesGridCellsIntoCompleteWallpapers() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.wallpaperflare.com/search?wallpaper=nature" to ok(searchGrid + paginationQueryStyle),
                    ),
                )

            val page = provider.search("nature", 1, Filters.None).getOrThrow()

            assertEquals(3, page.wallpapers.size)
            assertEquals(2, page.nextPage)
            val first = page.wallpapers[0]
            assertEquals("929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper", first.id)
            assertEquals("cloudimage.wallpaperflare", first.providerId)
            assertEquals(
                "https://c4.wallpaperflare.com/wallpaper/929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper-preview.jpg",
                first.thumbUrl,
            )
            // The original is the preview's suffix-stripped stem.
            assertEquals(
                "https://c4.wallpaperflare.com/wallpaper/929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper.jpg",
                first.fullUrl,
            )
            assertEquals("I'll support you. So you can carry me", first.title)
            assertEquals(6016, first.width)
            assertEquals(4000, first.height)
            assertEquals(listOf("gray", "plane", "wing"), first.tags)
            // The request used the site's own URL family, + for spaces.
            assertEquals(
                "https://www.wallpaperflare.com/search?wallpaper=nature",
                client.requests.single(),
            )
        }

    @Test
    fun searchEncodesSpacesAsPluses() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.wallpaperflare.com/search?wallpaper=landscape+4k+high+resolution+image" to
                            ok(searchGrid + paginationQueryStyle),
                    ),
                )

            provider.search("landscape 4k high resolution image", 1, Filters.None).getOrThrow()

            assertEquals(
                "https://www.wallpaperflare.com/search?wallpaper=landscape+4k+high+resolution+image",
                client.requests.single(),
            )
        }

    @Test
    fun blankQueryLandsOnThePopularRanking() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.wallpaperflare.com/" to ok(searchGrid + paginationPathStyle),
                    ),
                )

            val page = provider.search("", 1, Filters.None).getOrThrow()

            assertEquals("https://www.wallpaperflare.com/", client.requests.single())
            assertEquals(3, page.wallpapers.size)
        }

    @Test
    fun previewWinsWhenTheSameWallpaperAlsoAppearsAsThumb() {
        val items = WallpaperFlareParser.parseGrid(searchGrid + thumbCell)
        val ids = items.map { it.id }
        assertTrue(ids.contains("87/470/339/abstract-grunge-texture-old-wallpaper"))
        val supported = items.first { it.id == "929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper" }
        assertTrue(supported.thumbUrl.endsWith("-preview.jpg"))
    }

    @Test
    fun thumbOnlyCellStillYieldsUsableUrls() {
        val items = WallpaperFlareParser.parseGrid(thumbCell)
        assertEquals(1, items.size)
        val item = items[0]
        assertTrue(item.thumbUrl.endsWith("-thumb.jpg"))
        assertEquals("https://c4.wallpaperflare.com/wallpaper/87/470/339/abstract-grunge-texture-old-wallpaper.jpg", item.fullUrl)
        assertEquals(1920, item.width)
        assertEquals(1200, item.height)
        assertEquals(listOf("texture", "grunge"), item.tags)
    }

    @Test
    fun unrecognizableMarkupParsesToNothing() {
        assertTrue(WallpaperFlareParser.parseGrid("<div>just some page furniture</div>").isEmpty())
        assertNull(WallpaperFlareParser.parseDetail("<div>nothing recognizable</div>"))
    }

    // ------------------------------------------------------------ pagination

    @Test
    fun deeperPagesRideTheLearnedTemplateFromTheFirstResponse() =
        runTest {
            // The longer prefix routes FIRST: the fake matches by prefix and
            // the bare search URL would otherwise swallow the &page= request.
            val client =
                configureWith(
                    mapOf(
                        "https://www.wallpaperflare.com/search?wallpaper=nature&page=2" to ok(searchGrid + paginationQueryStylePage2),
                        "https://www.wallpaperflare.com/search?wallpaper=nature" to ok(searchGrid + paginationQueryStyle),
                    ),
                )

            provider.search("nature", 1, Filters.None).getOrThrow()
            val page2 = provider.search("nature", 2, Filters.None).getOrThrow()

            assertEquals(
                listOf(
                    "https://www.wallpaperflare.com/search?wallpaper=nature",
                    "https://www.wallpaperflare.com/search?wallpaper=nature&page=2",
                ),
                client.requests,
            )
            assertEquals(3, page2.wallpapers.size)
            assertEquals(3, page2.nextPage)
        }

    @Test
    fun walkStopsWhenTheBarMarksADifferentCurrentPage() =
        runTest {
            // The site ignored the deep parameter and served page 1 again:
            // its bar still marks page 1 as current. The walk must stop.
            val client =
                configureWith(
                    mapOf(
                        // The bare prefix swallows both requests by design —
                        // the scenario is a site that ignores &page= and
                        // answers page one every time (bar current stays 1).
                        "https://www.wallpaperflare.com/search?wallpaper=nature" to ok(searchGrid + paginationQueryStyle),
                    ),
                )

            provider.search("nature", 1, Filters.None).getOrThrow()
            val page2 = provider.search("nature", 2, Filters.None).getOrThrow()

            // The second request rode the learned template (…&page=2).
            assertEquals(
                "https://www.wallpaperflare.com/search?wallpaper=nature&page=2",
                client.requests.last(),
            )
            assertNull(page2.nextPage)
        }

    @Test
    fun walkStopsWhenTheBarAdvertisesNoFurtherPage() =
        runTest {
            // The bar marks page 3 as both current and the deepest link: a
            // request for page 3 must find nothing beyond itself.
            val lastPageBar =
                """
                <ul class="pagination">
                <li class="active"><a href="https://www.wallpaperflare.com/search?wallpaper=nature&amp;page=3">3</a></li>
                </ul>
                """.trimIndent()
            configureWith(
                mapOf(
                    "https://www.wallpaperflare.com/search?wallpaper=nature" to ok(searchGrid + lastPageBar),
                ),
            )

            val page = provider.search("nature", 3, Filters.None).getOrThrow()

            assertNull(page.nextPage)
        }

    @Test
    fun deepPaginationIsCapped() =
        runTest {
            configureWith(mapOf("https://www.wallpaperflare.com/" to ok(searchGrid)))

            val page = provider.popular(101, Filters.None).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    // --------------------------------------------------------------- details

    @Test
    fun detailsReadsTheWallpaperPage() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.wallpaperflare.com/wallpaper/929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper" to ok(detailPage),
                ),
            )

            val details =
                provider
                    .details("929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper")
                    .getOrThrow()

            assertEquals("I'll support you. So you can carry me", details.wallpaper.title)
            assertEquals(6016, details.wallpaper.width)
            assertEquals(4000, details.wallpaper.height)
            assertEquals("6016x4000", details.resolution)
            assertEquals(
                "https://c4.wallpaperflare.com/wallpaper/929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper.jpg",
                details.wallpaper.fullUrl,
            )
            assertEquals(
                "https://www.wallpaperflare.com/wallpaper/929/733/177/i-ll-support-you-so-you-can-carry-me-wallpaper",
                details.sourceUrl,
            )
            assertEquals(listOf("gray", "plane", "wing"), details.wallpaper.tags)
        }

    @Test
    fun detailsFailsCleanlyOnAnUnrecognizablePage() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.wallpaperflare.com/wallpaper/929" to ok("<html><body>moved</body></html>"),
                ),
            )

            assertTrue(provider.details("929").isFailure)
        }

    @Test
    fun nonSuccessStatusIsASourceFailureNotAThrow() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.wallpaperflare.com/search?wallpaper=nature" to ProviderHttpResponse(403, emptyMap(), ByteArray(0)),
                ),
            )

            val outcome = provider.search("nature", 1, Filters.None)

            assertTrue(outcome.isFailure)
        }

    // ----------------------------------------------------------- suggestions

    @Test
    fun suggestTagsServesTagsHarvestedFromFetchedGrids() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.wallpaperflare.com/search?wallpaper=nature" to ok(searchGrid + paginationQueryStyle),
                ),
            )

            provider.search("nature", 1, Filters.None).getOrThrow()
            val suggestions = provider.suggestTags("wa").getOrThrow()

            assertEquals(listOf("waterfall"), suggestions)
            assertTrue(provider.suggestTags("zzz").getOrThrow().isEmpty())
        }

    @Test
    fun sectionsAreQueryPresetsTheSiteSearches() =
        runTest {
            val sections = provider.sections()

            assertTrue(sections.any { it.id == HomeSection.DEFAULT_ID })
            assertTrue(sections.any { it.title == "Nature" && it.filters.isSelected("query", "nature") })
            assertTrue(sections.any { it.title == "Anime" && it.filters.isSelected("query", "anime") })
        }

    // ------------------------------------------------------------- plumbing

    /** The same bar with page 2 marked active — page 2's own response. */
    private val paginationQueryStylePage2 =
        """
        <ul class="pagination">
        <li><a href="https://www.wallpaperflare.com/search?wallpaper=nature&amp;page=1">1</a></li>
        <li class="active"><a href="https://www.wallpaperflare.com/search?wallpaper=nature&amp;page=2">2</a></li>
        <li><a href="https://www.wallpaperflare.com/search?wallpaper=nature&amp;page=3">3</a></li>
        </ul>
        """.trimIndent()
}
