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
 * facade, with fixtures captured from the site's LIVE markup (v1.3.0 —
 * fetched through a real browser context, not search-engine caches):
 * the schema.org microdata grid (`li itemprop="associatedMedia"` carrying
 * keywords, description dimensions, the page-slug anchor and the lazy CDN
 * image), the search URL family (`/search?wallpaper={query}` with `+`
 * spaces — the URL the site's own form and tag chips build), the
 * pagination bar in its `?page=` and `/page=N` dialects, and the
 * wallpaper page's `view_img`/h1/license record — all covered without a
 * network, exactly like the HDQWalls suite this mirrors.
 */
class WallpaperFlareWallpaperProviderTest {
    private val provider = WallpaperFlareWallpaperProvider()

    /** URL-routed responses; unmatched URLs answer 500 to fail loudly. */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()
        val headerLog = mutableListOf<Map<String, String>>()
        var routes: Map<String, ProviderHttpResponse> = emptyMap()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            headerLog += headers
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

    // Fixtures: shapes captured from wallpaperflare.com's live pages.

    /**
     * The grid as the site serves it — the exact microdata shape its own
     * pages render: one `li itemprop="associatedMedia"` per wallpaper
     * (keywords meta, description meta stating TRUE dimensions, schema.org
     * width/height spans, the page-slug anchor, the lazy CDN image with
     * `-thumb` `data-src` and `-preview` `srcset`, and the caption). The
     * first cell is the live homepage's own first item verbatim in
     * structure (2560x1440, "man near Torii gate wallpaper").
     */
    private val searchGrid =
        """
        <ul class="coho-list">
        <li itemprop="associatedMedia" itemscope="" itemtype="http://schema.org/ImageObject" class="item shadow" data-w="366" data-h="206">
            <meta itemprop="fileFormat" content="image/jpeg">
            <meta itemprop="keywords" content="landscape, anime, digital art, fantasy art, night, stars, sunset, asia, mountain, HD wallpapers, PC wallpapers, mobile wallpapers, tablet wallpapers, HD desktop, free download, 1080P, 2K, 4K, 5K">
            <meta itemprop="description" content="This HD wallpaper is about man near Torii gate wallpaper, gray temple wallpaper, landscape, Original wallpaper dimensions is 2560x1440px, file size is 232.07KB">
            <meta itemprop="contentSize" content="232.07KB">
            <div class="res">
                <span itemprop="width" itemscope="" itemtype="http://schema.org/QuantitativeValue">
                    <span itemprop="value">2560</span>
                    <meta itemprop="unitText" content="px">
                </span>x<span itemprop="height" itemscope="" itemtype="http://schema.org/QuantitativeValue">
                    <span itemprop="value">1440</span>
                    <meta itemprop="unitText" content="px">
                </span>px
            </div>
            <figure>
                <a itemprop="url" href="https://www.wallpaperflare.com/man-near-torii-gate-wallpaper-gray-temple-wallpaper-landscape-wallpaper-cqg" target="_blank">
                    <img itemprop="contentUrl" alt="man near Torii gate wallpaper, gray temple wallpaper, landscape HD wallpaper" title="man near Torii gate wallpaper, gray temple wallpaper, landscape HD wallpaper" class="lazy loading" data-src="https://c4.wallpaperflare.com/wallpaper/142/751/831/landscape-anime-digital-art-fantasy-art-wallpaper-thumb.jpg" data-srcset="https://c4.wallpaperflare.com/wallpaper/142/751/831/landscape-anime-digital-art-fantasy-art-wallpaper-preview.jpg 2x,https://c4.wallpaperflare.com/wallpaper/142/751/831/landscape-anime-digital-art-fantasy-art-wallpaper-preview.jpg 3x" srcset="https://c4.wallpaperflare.com/wallpaper/142/751/831/landscape-anime-digital-art-fantasy-art-wallpaper-preview.jpg 2x" src="https://c4.wallpaperflare.com/wallpaper/142/751/831/landscape-anime-digital-art-fantasy-art-wallpaper-thumb.jpg">
                </a>
                <figcaption itemprop="caption" class="overflow">man near Torii gate wallpaper, gray temple wallpaper, landscape</figcaption>
            </figure>
        </li>
        <li itemprop="associatedMedia" itemscope="" itemtype="http://schema.org/ImageObject" class="item shadow" data-w="366" data-h="206">
            <meta itemprop="keywords" content="digital art, men, city, futuristic, night, neon, science fiction, HD wallpapers, PC wallpapers, mobile wallpapers, free download">
            <meta itemprop="description" content="This HD wallpaper is about digital art, men, city, futuristic, night, neon, science fiction, Original wallpaper dimensions is 3840x1633px, file size is 1.11MB">
            <div class="res">
                <span itemprop="width" itemscope="" itemtype="http://schema.org/QuantitativeValue">
                    <span itemprop="value">3840</span>
                </span>x<span itemprop="height" itemscope="" itemtype="http://schema.org/QuantitativeValue">
                    <span itemprop="value">1633</span>
                </span>px
            </div>
            <figure>
                <a itemprop="url" href="https://www.wallpaperflare.com/digital-art-men-city-futuristic-night-neon-science-fiction-wallpaper-udroj" target="_blank">
                    <img itemprop="contentUrl" alt="digital art, men, city, futuristic, night, neon, science fiction HD wallpaper" data-src="https://c4.wallpaperflare.com/wallpaper/39/346/426/digital-art-men-city-futuristic-night-hd-wallpaper-thumb.jpg" src="https://c4.wallpaperflare.com/wallpaper/39/346/426/digital-art-men-city-futuristic-night-hd-wallpaper-thumb.jpg">
                </a>
                <figcaption itemprop="caption" class="overflow">digital art, men, city, futuristic, night, neon, science fiction</figcaption>
            </figure>
        </li>
        <li itemprop="associatedMedia" itemscope="" itemtype="http://schema.org/ImageObject" class="item shadow" data-w="366" data-h="206">
            <meta itemprop="keywords" content="waterfall, brazil, forest, nature">
            <meta itemprop="description" content="This HD wallpaper is about waterfall, brazil, forest, Original wallpaper dimensions is 4000x6000px, file size is 3.4MB">
            <div class="res">4000x6000px</div>
            <figure>
                <a itemprop="url" href="/waterfalls-caracol-falls-brazil-forest-wallpaper-fgjhh" target="_blank">
                    <img itemprop="contentUrl" src="https://c1.wallpaperflare.com/wallpaper/626/958/440/waterfalls-caracol-falls-brazil-forest-wallpaper-preview.jpg">
                </a>
                <figcaption itemprop="caption" class="overflow">waterfall, brazil, forest</figcaption>
            </figure>
        </li>
        </ul>
        """.trimIndent()

    /**
     * A legacy/unknown grid: anchors wrapping CDN images with the text the
     * old markup printed beside them — the generalized fallback's input.
     */
    private val legacyGrid =
        """
        <a href="https://www.wallpaperflare.com/abstract-grunge-texture-old-wallpaper-yeqdd" title="abstract grunge texture old">
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

    /** A pagination bar in the site's path-keyed dialect (`/page=N`). */
    private val paginationPathStyle =
        """
        <ul class="pagination">
        <li><a href="https://www.wallpaperflare.com/search/wallpaper=nature/page=1">1</a></li>
        <li><a href="https://www.wallpaperflare.com/search/wallpaper=nature/page=2">2</a></li>
        </ul>
        """.trimIndent()

    /** A wallpaper page: the view image, the h1, the site's own furniture, tag chips. */
    private val detailPage =
        """
        <html><head>
        <title>HD wallpaper: man near Torii gate wallpaper, gray temple wallpaper, landscape 1080P, 2K, 4K, 5K HD wallpapers free download - WallpaperFlare</title>
        <meta name="keywords" content="landscape, anime, digital art, fantasy art">
        <meta name="description" content="This HD wallpaper is about man near Torii gate wallpaper, gray temple wallpaper, landscape, Original wallpaper dimensions is 2560x1440px, file size is 232.07KB">
        </head><body>
        <h1>HD wallpaper: man near Torii gate wallpaper, gray temple wallpaper, landscape</h1>
        <meta itemprop="representativeOfPage" content="true">
        <figure>
        <img itemprop="contentUrl" class="view_img" id="vimg" data-height="410" alt="man near Torii gate wallpaper, gray temple wallpaper, landscape, HD wallpaper" src="https://c4.wallpaperflare.com/wallpaper/142/751/831/landscape-anime-digital-art-fantasy-art-wallpaper-preview.jpg">
        <figcaption>man near Torii gate wallpaper, gray temple wallpaper, landscape, HD wallpaper</figcaption>
        </figure>
        <a href="https://www.wallpaperflare.com/search?wallpaper=gray">gray</a>
        <a href="https://www.wallpaperflare.com/search?wallpaper=temple">temple</a>
        </body></html>
        """.trimIndent()

    // ------------------------------------------------------------------ grid

    @Test
    fun everyDocumentRequestRidesTheHostsOwnIdentity() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.wallpaperflare.com/" to ok(searchGrid + paginationPathStyle),
                    ),
                )

            provider.popular(1, Filters.None).getOrThrow()

            val headers = client.headerLog.single()
            // v1.2.5: the provider impersonates NOTHING — no User-Agent, no
            // sec-fetch family, no sec-ch-ua hints. v1.1.0's full mobile-Chrome
            // fingerprint rode through the host's OkHttp stack, whose TLS
            // signature no header set can imitate, and the zone answered that
            // incoherence with a hard WAF block instead of a challenge — a
            // response no cookie replay can satisfy and the exact regression
            // v1.2.5 exists to undo. An honest request earns a challenge, and
            // a challenge is what the host's WebView machinery is built to eat.
            assertTrue(
                "the provider must send no identity of its own: $headers",
                headers.isEmpty(),
            )
        }

    @Test
    fun deepNavigationsStayEquallyHonest() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.wallpaperflare.com/?page=2" to ok(searchGrid),
                    ),
                )

            provider.popular(2, Filters.None).getOrThrow()

            val headers = client.headerLog.single()
            assertTrue(
                "deep pages must stay as honest as first pages: $headers",
                headers.isEmpty(),
            )
        }

    @Test
    fun searchParsesMicrodataCellsIntoCompleteWallpapers() =
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
            // The id is the PAGE's own slug — the site's href, not the CDN path.
            assertEquals("man-near-torii-gate-wallpaper-gray-temple-wallpaper-landscape-wallpaper-cqg", first.id)
            assertEquals("cloudimage.wallpaperflare", first.providerId)
            // The preview a srcset names wins over the lazy thumb.
            assertEquals(
                "https://c4.wallpaperflare.com/wallpaper/142/751/831/landscape-anime-digital-art-fantasy-art-wallpaper-preview.jpg",
                first.thumbUrl,
            )
            // The original is the preview's suffix-stripped stem.
            assertEquals(
                "https://c4.wallpaperflare.com/wallpaper/142/751/831/landscape-anime-digital-art-fantasy-art-wallpaper.jpg",
                first.fullUrl,
            )
            assertEquals("man near Torii gate wallpaper, gray temple wallpaper, landscape", first.title)
            assertEquals(2560, first.width)
            assertEquals(1440, first.height)
            // The keyword row's real tags, furniture filtered, capped at eight.
            assertEquals(
                listOf("landscape", "anime", "digital art", "fantasy art", "night", "stars", "sunset", "asia"),
                first.tags,
            )
            // The request used the site's own URL family, + for spaces.
            assertEquals(
                "https://www.wallpaperflare.com/search?wallpaper=nature",
                client.requests.single(),
            )
        }

    @Test
    fun cellsWithoutSrcsetServeTheThumbTheyRender() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.wallpaperflare.com/search?wallpaper=nature" to ok(searchGrid),
                    ),
                )

            val page = provider.search("nature", 1, Filters.None).getOrThrow()

            // The second cell carries only `data-src`/`src` thumbs — the
            // thumb it renders is the thumb served, and the stem still
            // points at the original.
            val second = page.wallpapers[1]
            assertEquals("digital-art-men-city-futuristic-night-neon-science-fiction-wallpaper-udroj", second.id)
            assertEquals(
                "https://c4.wallpaperflare.com/wallpaper/39/346/426/digital-art-men-city-futuristic-night-hd-wallpaper-thumb.jpg",
                second.thumbUrl,
            )
            assertEquals(
                "https://c4.wallpaperflare.com/wallpaper/39/346/426/digital-art-men-city-futuristic-night-hd-wallpaper.jpg",
                second.fullUrl,
            )
            assertEquals(3840, second.width)
            assertEquals(1633, second.height)
        }

    @Test
    fun relativeHrefAndTextDimensionsStillParse() {
        // The third cell: a RELATIVE href and dimensions only as flattened
        // text — both spellings the site's own pages use.
        val items = WallpaperFlareParser.parseGrid(searchGrid)
        val third = items.first { it.id == "waterfalls-caracol-falls-brazil-forest-wallpaper-fgjhh" }
        assertEquals("waterfall, brazil, forest", third.title)
        assertEquals(4000, third.width)
        assertEquals(6000, third.height)
        assertEquals(
            "https://c1.wallpaperflare.com/wallpaper/626/958/440/waterfalls-caracol-falls-brazil-forest-wallpaper-preview.jpg",
            third.thumbUrl,
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
    fun legacyAnchorCellsStillYieldUsableItems() {
        // A grid with no microdata li at all: the fallback reads the
        // anchor's href as the id and the anchor text for the record.
        val items = WallpaperFlareParser.parseGrid(legacyGrid)
        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("abstract-grunge-texture-old-wallpaper-yeqdd", item.id)
        assertTrue(item.thumbUrl.endsWith("-thumb.jpg"))
        assertEquals("https://c4.wallpaperflare.com/wallpaper/87/470/339/abstract-grunge-texture-old-wallpaper.jpg", item.fullUrl)
        assertEquals(1920, item.width)
        assertEquals(1200, item.height)
        assertEquals(listOf("texture", "grunge"), item.tags)
    }

    @Test
    fun routeHrefsAreNeverTakenAsWallpaperIds() {
        // Search and download links never masquerade as pages, whatever
        // markup wraps them.
        val html =
            """
            <a href="https://www.wallpaperflare.com/search?wallpaper=nature"><img src="https://c4.wallpaperflare.com/wallpaper/1/2/3/foo-thumb.jpg"></a>
            <a href="https://www.wallpaperflare.com/download"><img src="https://c4.wallpaperflare.com/wallpaper/1/2/3/bar-thumb.jpg"></a>
            """.trimIndent()
        assertTrue(WallpaperFlareParser.parseGrid(html).isEmpty())
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
    fun thePathKeyedDialectIsLearnedToo() =
        runTest {
            // `/page=N` — the dialect the site's own indexed URLs show — is
            // both recognized and templated, so deep pages ride it.
            val client =
                configureWith(
                    mapOf(
                        "https://www.wallpaperflare.com/search/wallpaper=nature/page=2" to ok(searchGrid + paginationPathStyle),
                        "https://www.wallpaperflare.com/search?wallpaper=nature" to ok(searchGrid + paginationPathStyle),
                    ),
                )

            provider.search("nature", 1, Filters.None).getOrThrow()
            provider.search("nature", 2, Filters.None).getOrThrow()

            assertEquals(
                "https://www.wallpaperflare.com/search/wallpaper=nature/page=2",
                client.requests.last(),
            )
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
            val pageUrl = "https://www.wallpaperflare.com/man-near-torii-gate-wallpaper-gray-temple-wallpaper-landscape-wallpaper-cqg"
            configureWith(mapOf(pageUrl to ok(detailPage)))

            val details =
                provider
                    .details("man-near-torii-gate-wallpaper-gray-temple-wallpaper-landscape-wallpaper-cqg")
                    .getOrThrow()

            assertEquals("man near Torii gate wallpaper, gray temple wallpaper, landscape", details.wallpaper.title)
            assertEquals(2560, details.wallpaper.width)
            assertEquals(1440, details.wallpaper.height)
            assertEquals("2560x1440", details.resolution)
            // The thumb is the page's own view image; the original is its stem.
            assertEquals(
                "https://c4.wallpaperflare.com/wallpaper/142/751/831/landscape-anime-digital-art-fantasy-art-wallpaper-preview.jpg",
                details.wallpaper.thumbUrl,
            )
            assertEquals(
                "https://c4.wallpaperflare.com/wallpaper/142/751/831/landscape-anime-digital-art-fantasy-art-wallpaper.jpg",
                details.wallpaper.fullUrl,
            )
            assertEquals(
                "https://www.wallpaperflare.com/man-near-torii-gate-wallpaper-gray-temple-wallpaper-landscape-wallpaper-cqg",
                details.sourceUrl,
            )
            // Keywords meta first, then the page's own tag chips.
            assertEquals(
                listOf("landscape", "anime", "digital art", "fantasy art", "gray", "temple"),
                details.wallpaper.tags,
            )
        }

    @Test
    fun detailsFailsCleanlyOnAnUnrecognizablePage() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.wallpaperflare.com/moved" to ok("<html><body>moved</body></html>"),
                ),
            )

            assertTrue(provider.details("moved").isFailure)
        }

    @Test
    fun detailsFailsCleanlyWhenThePageStatesNoImage() =
        runTest {
            // A page with a name but no view image has nothing honest to
            // show — the host keeps the grid item's own URLs instead.
            configureWith(
                mapOf(
                    "https://www.wallpaperflare.com/imageless" to ok("<html><body><h1>HD wallpaper: a name</h1></body></html>"),
                ),
            )

            assertTrue(provider.details("imageless").isFailure)
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
            val suggestions = provider.suggestTags("wat").getOrThrow()

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
