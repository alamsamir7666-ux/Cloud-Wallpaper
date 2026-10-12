package com.cloudimage.wallpapersden

import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WallpapersDen provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures cut from the live site's markup: the microdata
 * listing grid (latest, popular, category, tag), the pagination bar's
 * rel="next" contract in both wire shapes, the blank-query and
 * deep-pagination guards, the detail record's token-decoded original,
 * the constructed-download scheme, the host-vocabulary routing and the
 * search seam guard all stay covered without a network.
 */
class WallpapersDenWallpaperProviderTest {
    private val provider = WallpapersDenWallpaperProvider()

    /** URL-routed responses; unmatched URLs answer 500 to fail loudly. */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()
        var routes: Map<String, ProviderHttpResponse> = emptyMap()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            return routes[url]
                ?: routes.entries
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

    // Fixtures: shapes captured from wallpapersden.com, trimmed to the
    // parts the parsers key on.

    /** The latest feed's first cells — real markup, the doom and spider cards. */
    private val listingGrid =
        """
        <ul class="flex flex-wrap">
                        <li class="w-full sm:w-2/4 md:w-1/4 lg:w-1/4 xl:w-1/4 items-center">
                <div class="m-1 p-px">
                    <figure class="card border-gray-trans-2 border p-px" style="margin:0">
                        <a itemprop="url" href="/doom-avengers-4k-wallpaper/" aria-label="Doom Avengers 4K Wallpaper">
                            <img itemprop="thumbnail" class="img-thumbnail" width="320px" height="240px" alt="Doom Avengers 4K Wallpaper" src="https://images.wallpapersden.com/image/ws-doom-avengers-4k_93389.jpg">
                        </a>
                        <div class="absolute opacity-0 card-section">
                            <figcaption itemprop="caption description" class="text-center font-light cursor-pointer"><a href="/doom-avengers-4k-wallpaper/">Doom Avengers 4K </a></figcaption>
                        </div>
                    </figure>
                </div>
            </li>
            <li class="w-full sm:w-2/4 md:w-1/4 lg:w-1/4 xl:w-1/4 items-center">
                <div class="m-1 p-px">
                    <figure class="card border-gray-trans-2 border p-px" style="margin:0">
                        <a itemprop="url" href="https://wallpapersden.com/4k-poster-of-spider-man-brand-new-day-wallpaper/" aria-label="4K Poster of Spider-Man Brand New Day Wallpaper">
                            <img itemprop="thumbnail" class="img-thumbnail" width="320px" height="240px" alt="4K Poster of Spider-Man Brand New Day Wallpaper" src="https://images.wallpapersden.com/image/ws-4k-poster-of-spider-man-brand-new-day_93384.jpg">
                        </a>
                    </figure>
                </div>
            </li>
        </ul>
        """.trimIndent()

    /** A phone-titled card — the portrait naming convention. */
    private val portraitGrid =
        """
        <a itemprop="url" href="/spider-man-brand-new-day-hd-poster-iphone-wallpaper/" aria-label="Spider-Man Brand New Day HD Poster iPhone Wallpaper">
            <img itemprop="thumbnail" class="img-thumbnail" width="320px" height="240px" alt="Spider-Man Brand New Day HD Poster iPhone Wallpaper" src="https://images.wallpapersden.com/image/ws-spider-man-brand-new-day-hd-poster-iphone_93311.jpg">
        </a>
        """.trimIndent()

    /** The Spotlight sidebar card — real markup, NO microdata: the parser must skip it. */
    private val spotlightCard =
        """
        <div class="flex flex-wrap">
            <div class="w-full sm:w-2/4 md:w-1/4 lg:w-1/4 xl:w-1/4 items-center">
                <div class="m-1 p-px">
                    <div class="card border-gray-trans-2 border p-px">
                        <a href="https://wallpapersden.com/kawhi-leonard-4k-la-clippers-wallpaper/">
                            <img class="img-thumbnail" importance="low" width="320px" height="240px" loading="lazy" alt="Kawhi Leonard 4K LA Clippers Wallpapers" src="https://images.wallpapersden.com/image/ws-kawhi-leonard-4k-la-clippers_77559.jpg">
                        </a>
                    </div>
                </div>
            </div>
        </div>
        """.trimIndent()

    /** The pagination bar — real markup: numbered links plus the rel="next" anchor. */
    private val listingPagination =
        """
        <div class="flex flex-wrap text-white-50 m-1 justify-center">
            <div class="cursor-not-allowed page-item px-4 py-2 m-1 bg-secondary disabled" aria-disabled="true" aria-label="« Previous">‹ Previous </div>
            <div class="page-item px-4 py-2 m-1 bg-secondary bg-black" aria-current="page"> 1 </div>
            <a class="px-4 py-2 m-1 bg-secondary hover:bg-black hover:text-white-900" href="https://wallpapersden.com/latest-wallpapers/page/2/">2</a>
            <a class="px-4 py-2 m-1 bg-secondary hover:bg-black hover:text-white-900" href="https://wallpapersden.com/latest-wallpapers/page/3/">3</a>
            <a class="px-4 py-2 m-1 bg-secondary hover:bg-black hover:text-white-900" href="https://wallpapersden.com/latest-wallpapers/page/2/" rel="next" aria-label="Next »">Next ›</a>
        </div>
        """.trimIndent()

    /** The LAST page of a result: Previous only, no rel="next" — the stop signal. */
    private val lastPagePagination =
        """
        <div class="flex flex-wrap text-white-50 m-1 justify-center">
            <a class="px-4 py-2 m-1 bg-secondary hover:bg-black hover:text-white-900" href="https://wallpapersden.com/latest-wallpapers/page/1/" rel="prev" aria-label="« Previous">‹ Previous </a>
            <div class="page-item px-4 py-2 m-1 bg-secondary bg-black" aria-current="page"> 2 </div>
        </div>
        """.trimIndent()

    /** Search pagination: the query-string shape, next rides `page=N`. */
    private val searchPagination =
        """
        <div class="flex flex-wrap text-white-50 m-1 justify-center">
            <div class="page-item px-4 py-2 m-1 bg-secondary bg-black" aria-current="page"> 1 </div>
            <a class="px-4 py-2 m-1 bg-secondary hover:bg-black hover:text-white-900" href="https://wallpapersden.com/search?q=batman&amp;page=2">2</a>
            <a class="px-4 py-2 m-1 bg-secondary hover:bg-black hover:text-white-900" href="https://wallpapersden.com/search?q=batman&amp;page=2" rel="next" aria-label="Next »">Next ›</a>
        </div>
        """.trimIndent()

    /**
     * The wallpaper page's definitive parts, captured live: the meta's
     * download URL (the original — its token decodes to 93384 at
     * 3840x2160), the preview image with its alt title, and the
     * resolution menu's own links.
     */
    private val wallpaperPage =
        """
        <html><head>
        <meta itemprop="image" content="https://images.wallpapersden.com/image/download/4k-poster-of-spider-man-brand-new-day_bmhobWmUmZqaraWkpJRobWllrWdma2U.jpg">
        <meta property="og:image" content="https://images.wallpapersden.com/image/download/4k-poster-of-spider-man-brand-new-day_bmhobWmUmZqaraWkpJRobWllrWdma2U.jpg">
        </head><body>
        <div class="flex flex-wrap items-stretch">
            <div class="w-full md:w-3/5">
                <a href="https://images.wallpapersden.com/image/download/4k-poster-of-spider-man-brand-new-day_bmhobWmUmZqaraWkpJRobWllrWdma2U.jpg" target="_blank">
                    <img importance="high" class="img_flex" alt="4K Poster of Spider-Man Brand New Day Wallpaper" src="https://images.wallpapersden.com/image/wxl-4k-poster-of-spider-man-brand-new-day_93384.jpg" width="622" height="345">
                </a>
            </div>
            <a title="4K Poster of Spider-Man Brand New Day Wallpaper, HD Background Image" href="https://images.wallpapersden.com/image/download/4k-poster-of-spider-man-brand-new-day_bmhobWmUmZqaraWkpJRobWllrWdma2U.jpg" target="_blank" rel="nofollow" class="block bg-blue-900 text-center text-white-50 hover:bg-blue-800 hover:text-white-900 p-2 sm:mb-1 md:mb-0 sm:mx-1 md:mx-3"> Download Original Wallpaper </a>
            <a target="_blank" rel="nofollow" href="https://images.wallpapersden.com/4k-poster-of-spider-man-brand-new-day-wallpaper/1920x1080/"> 1920x1080 </a>
        </div>
        </body></html>
        """.trimIndent()

    // ------------------------------------------------------------- popular

    @Test
    fun `popular maps the microdata grid with constructed originals`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapersden.com/popular-wallpapers" to ok(listingGrid + spotlightCard + listingPagination)),
                )

            val page = provider.popular(page = 1).getOrThrow()

            // The Spotlight sidebar card carries no microdata and drops.
            assertEquals(2, page.wallpapers.size)
            val first = page.wallpapers.first()
            assertEquals("doom-avengers-4k", first.id)
            assertEquals("cloudimage.wallpapersden", first.providerId)
            // The site's own grid thumbnail.
            assertEquals("https://images.wallpapersden.com/image/ws-doom-avengers-4k_93389.jpg", first.thumbUrl)
            // The constructed original: the site's own download URL scheme,
            // token minted for 4K — a size the site offers for every
            // wallpaper. Byte-identical to the live scheme's output.
            assertEquals(
                "https://images.wallpapersden.com/image/download/doom-avengers-4k_bmhobW6UmZqaraWkpJRobWllrWdma2U.jpg",
                first.fullUrl,
            )
            assertEquals("Doom Avengers 4K Wallpaper", first.title)
            // Grid items carry NO dimensions: the listing's width/height
            // attributes are the site's uniform 320x240 card crop, true of
            // no wallpaper — the app's info sheet renders null as "—".
            assertNull(first.width)
            assertNull(first.height)
            assertEquals(ContentRating.SFW, first.contentRating)
            // The 4k in the title is a resolution label, not a tag.
            assertEquals(listOf("doom", "avengers"), first.tags)
            // The site's own Next link: page 2 follows.
            assertEquals(2, page.nextPage)
            assertEquals("https://wallpapersden.com/popular-wallpapers/", client.requests.single())
        }

    @Test
    fun `popular walks page two through the path pagination`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapersden.com/popular-wallpapers/page/2" to ok(listingGrid + listingPagination)),
                )

            val page = provider.popular(page = 2).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals("https://wallpapersden.com/popular-wallpapers/page/2/", client.requests.single())
        }

    @Test
    fun `popular routes sorting date to the latest feed`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapersden.com/latest-wallpapers" to ok(listingGrid)),
                )

            val page = provider.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals("https://wallpapersden.com/latest-wallpapers/", client.requests.single())
        }

    @Test
    fun `popular serves one batch for random sorting`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapersden.com/random-wallpapers" to ok(portraitGrid)),
                )

            val page = provider.popular(page = 1, filters = Filters.of("sorting" to "random")).getOrThrow()

            assertEquals(1, page.wallpapers.size)
            assertNull(page.nextPage)
            assertEquals("https://wallpapersden.com/random-wallpapers/", client.requests.single())
        }

    @Test
    fun `popular routes the host anime and people categories to their listings`() =
        runTest {
            val animeClient =
                configureWith(
                    mapOf("https://wallpapersden.com/anime-wallpapers" to ok(listingGrid)),
                )
            provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            assertEquals("https://wallpapersden.com/anime-wallpapers/", animeClient.requests.single())

            val peopleClient =
                configureWith(
                    mapOf("https://wallpapersden.com/celebrities-wallpapers" to ok(listingGrid)),
                )
            provider.popular(page = 1, filters = Filters.of("category" to "people")).getOrThrow()
            assertEquals("https://wallpapersden.com/celebrities-wallpapers/", peopleClient.requests.single())
        }

    // -------------------------------------------------------------- search

    @Test
    fun `search rides the site's own search with the query pagination`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapersden.com/search?q=batman" to ok(listingGrid + searchPagination)),
                )

            val page = provider.search(query = "batman", page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals(2, page.nextPage)
            assertEquals("https://wallpapersden.com/search?q=batman", client.requests.single())
        }

    @Test
    fun `search walks page two through the query string`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapersden.com/search?q=batman&page=2" to ok(listingGrid + searchPagination)),
                )

            val page = provider.search(query = "batman", page = 2).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals("https://wallpapersden.com/search?q=batman&page=2", client.requests.single())
        }

    @Test
    fun `search walks an exact category term down the site's own listing`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapersden.com/games-wallpapers" to ok(listingGrid + listingPagination)),
                )

            val page = provider.search(query = "Games", page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals(2, page.nextPage)
            assertEquals("https://wallpapersden.com/games-wallpapers/", client.requests.single())
        }

    @Test
    fun `search walks an exact tag term down the site's own tag listing`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapersden.com/tag/4k-wallpapers" to ok(listingGrid + listingPagination)),
                )

            provider.search(query = "4k", page = 1).getOrThrow()

            assertEquals("https://wallpapersden.com/tag/4k-wallpapers/", client.requests.single())
        }

    @Test
    fun `search lands blank queries on the latest feed`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapersden.com/latest-wallpapers" to ok(listingGrid)),
                )

            val page = provider.search(query = "   ", page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals("https://wallpapersden.com/latest-wallpapers/", client.requests.single())
        }

    @Test
    fun `search stops on the last page without a next link`() =
        runTest {
            configureWith(
                mapOf("https://wallpapersden.com/search?q=iphone&page=2" to ok(listingGrid + lastPagePagination)),
            )

            val page = provider.search(query = "iphone", page = 2).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertNull(page.nextPage)
        }

    @Test
    fun `search caps deep pagination politely`() =
        runTest {
            configureWith(mapOf("https://wallpapersden.com/search?q=batman" to ok(listingGrid + searchPagination)))

            val page = provider.search(query = "batman", page = 101).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun `search ends the feed when a page serves only repeats`() =
        runTest {
            // The seam guard: a mis-shaped page parameter makes the site
            // re-serve page one; the served-id window drops the repeats
            // and an entirely duplicate page ends the feed.
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapersden.com/search?q=batman" to ok(listingGrid + searchPagination),
                        "https://wallpapersden.com/search?q=batman&page=2" to ok(listingGrid + searchPagination),
                    ),
                )

            val page1 = provider.search(query = "batman", page = 1).getOrThrow()
            val page2 = provider.search(query = "batman", page = 2).getOrThrow()

            assertEquals(2, page1.wallpapers.size)
            assertTrue(page2.wallpapers.isEmpty())
            assertNull(page2.nextPage)
        }

    @Test
    fun `search serves fresh items after a page-boundary overlap`() =
        runTest {
            val overlapping =
                """
                <a itemprop="url" href="/doom-avengers-4k-wallpaper/" aria-label="Doom Avengers 4K Wallpaper">
                    <img itemprop="thumbnail" class="img-thumbnail" width="320px" height="240px" alt="Doom Avengers 4K Wallpaper" src="https://images.wallpapersden.com/image/ws-doom-avengers-4k_93389.jpg">
                </a>
                <a itemprop="url" href="/stone-hulk-4k-marvel-rivals-wallpaper/" aria-label="Stone Hulk 4k Marvel Rivals Wallpaper">
                    <img itemprop="thumbnail" class="img-thumbnail" width="320px" height="240px" alt="Stone Hulk 4k Marvel Rivals Wallpaper" src="https://images.wallpapersden.com/image/ws-stone-hulk-4k-marvel-rivals_93388.jpg">
                </a>
                """.trimIndent()
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapersden.com/search?q=batman" to ok(listingGrid + searchPagination),
                        "https://wallpapersden.com/search?q=batman&page=2" to ok(overlapping + searchPagination),
                    ),
                )

            provider.search(query = "batman", page = 1).getOrThrow()
            val page2 = provider.search(query = "batman", page = 2).getOrThrow()

            // The overlap (doom) drops; the fresh item (hulk) survives.
            assertEquals(listOf("stone-hulk-4k-marvel-rivals"), page2.wallpapers.map { it.id })
            assertEquals(3, page2.nextPage)
        }

    // ------------------------------------------------------------- details

    @Test
    fun `details reads the definitive record off the token`() =
        runTest {
            configureWith(
                mapOf("https://wallpapersden.com/4k-poster-of-spider-man-brand-new-day-wallpaper/" to ok(wallpaperPage)),
            )

            val details = provider.details("4k-poster-of-spider-man-brand-new-day").getOrThrow()

            assertEquals("4k-poster-of-spider-man-brand-new-day", details.wallpaper.id)
            // The TRUE original, exactly as the site's own page links it.
            assertEquals(
                "https://images.wallpapersden.com/image/download/4k-poster-of-spider-man-brand-new-day_bmhobWmUmZqaraWkpJRobWllrWdma2U.jpg",
                details.wallpaper.fullUrl,
            )
            assertEquals(
                "https://images.wallpapersden.com/image/ws-4k-poster-of-spider-man-brand-new-day_93384.jpg",
                details.wallpaper.thumbUrl,
            )
            assertEquals("4K Poster of Spider-Man Brand New Day Wallpaper", details.wallpaper.title)
            // TRUE dimensions, decoded from the original's own token.
            assertEquals(3840, details.wallpaper.width)
            assertEquals(2160, details.wallpaper.height)
            assertEquals("3840x2160", details.resolution)
            assertEquals("https://wallpapersden.com/4k-poster-of-spider-man-brand-new-day-wallpaper/", details.sourceUrl)
        }

    @Test
    fun `details fails honestly on a missing wallpaper`() =
        runTest {
            configureWith(
                mapOf("https://wallpapersden.com/gone-wallpaper/" to ProviderHttpResponse(404, emptyMap(), ByteArray(0))),
            )

            val result = provider.details("gone-wallpaper")

            assertTrue(result.isFailure)
        }

    @Test
    fun `details fails honestly on an unrecognized page`() =
        runTest {
            configureWith(
                mapOf("https://wallpapersden.com/redesigned-wallpaper/" to ok("<html><body>nothing here</body></html>")),
            )

            val result = provider.details("redesigned-wallpaper")

            assertTrue(result.isFailure)
        }

    // ------------------------------------------------------------ sections

    @Test
    fun `sections expose fourteen shelves with host presets`() =
        runTest {
            val sections = provider.sections()

            assertEquals(14, sections.size)
            assertEquals("popular", sections.first().id)
            val byId = sections.associateBy { it.id }
            assertEquals("Popular", byId.getValue("popular").title)
            assertTrue(byId.getValue("popular").filters.isEmpty)
            assertEquals(setOf("date"), byId.getValue("latest").filters.valuesFor("sorting"))
            assertEquals(setOf("anime"), byId.getValue("anime").filters.valuesFor("category"))
            assertEquals(setOf("people"), byId.getValue("celebrities").filters.valuesFor("category"))
            assertEquals(setOf("girls"), byId.getValue("girls").filters.valuesFor("query"))
            assertEquals(setOf("cars"), byId.getValue("cars").filters.valuesFor("query"))
            assertEquals(setOf("games"), byId.getValue("games").filters.valuesFor("query"))
            assertEquals(setOf("space"), byId.getValue("space").filters.valuesFor("query"))
            assertEquals(setOf("sports"), byId.getValue("sports").filters.valuesFor("query"))
        }

    // -------------------------------------------------------- suggestions

    @Test
    fun `suggestTags answers from seen tags only`() =
        runTest {
            // Nothing seen yet: no suggestions, and no request fired.
            val client =
                configureWith(mapOf("https://wallpapersden.com/popular-wallpapers" to ok(listingGrid)))
            assertTrue(provider.suggestTags("doom").getOrThrow().isEmpty())

            provider.popular(page = 1).getOrThrow()
            // After a feed: the words of its titles are suggestable.
            val suggestions = provider.suggestTags("doom").getOrThrow()
            assertTrue(suggestions.contains("doom"))
            assertTrue(provider.suggestTags("aveng").getOrThrow().contains("avengers"))
            assertTrue(provider.suggestTags("nothing").getOrThrow().isEmpty())
            assertTrue(client.requests.none { it.contains("search") })
        }

    @Test
    fun `suggestTags answers nothing for a blank query`() =
        runTest {
            assertTrue(provider.suggestTags("").getOrThrow().isEmpty())
        }

    // -------------------------------------------------------------- random

    @Test
    fun `random serves the site's random batch`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapersden.com/random-wallpapers" to ok(portraitGrid)),
                )

            val wallpapers = provider.random().getOrThrow()

            assertEquals(1, wallpapers.size)
            val wallpaper = wallpapers.first()
            assertEquals("spider-man-brand-new-day-hd-poster-iphone", wallpaper.id)
            assertEquals("https://wallpapersden.com/random-wallpapers/", client.requests.single())
            // The iPhone naming convention selects the portrait 4K render.
            assertEquals(
                "https://images.wallpapersden.com/image/download/spider-man-brand-new-day-hd-poster-iphone_bmhoZmaUmZqaraWkpJRnZmtlrWhtaWU.jpg",
                wallpaper.fullUrl,
            )
            assertEquals(
                "https://images.wallpapersden.com/image/ws-spider-man-brand-new-day-hd-poster-iphone_93311.jpg",
                wallpaper.thumbUrl,
            )
        }

    // ------------------------------------------------- the download scheme

    @Test
    fun `the token codec reproduces the live site's own tokens`() {
        // Tokens captured from live wallpaper pages — byte-identical
        // reconstruction is the proof the scheme is the site's own.
        assertEquals(
            "bmhobWmUmZqaraWkpJRobWllrWdma2U",
            WallpapersDenParser.encodeDownloadToken("93384", 3840, 2160),
        )
        assertEquals(
            "bmhobWyUmZqaraWkpJRqZmdlrWdtbWU",
            WallpapersDenParser.encodeDownloadToken("93387", 5120, 2880),
        )
        assertEquals(
            "bmhoZmaUmZqaraWkpJRmbmdlrWdoaWk",
            WallpapersDenParser.encodeDownloadToken("93311", 1920, 2344),
        )
        assertEquals(
            "bmhobW6UmZqaraWkpJRoZ2xrrWllbms",
            WallpapersDenParser.encodeDownloadToken("93389", 3276, 4096),
        )
    }

    @Test
    fun `the token codec decodes the live site's own tokens`() {
        assertEquals(
            "93384_deexpoo_3840x2160",
            WallpapersDenParser.decodeDownloadToken("bmhobWmUmZqaraWkpJRobWllrWdma2U"),
        )
        assertEquals(
            "93389_deexpoo_3276x4096",
            WallpapersDenParser.decodeDownloadToken("bmhobW6UmZqaraWkpJRoZ2xrrWllbms"),
        )
        // Not the site's shape: answered with null, never an exception.
        assertNull(WallpapersDenParser.decodeDownloadToken("!!!not-a-token"))
        assertNull(WallpapersDenParser.decodeDownloadToken(""))
    }

    @Test
    fun `the token codec round-trips arbitrary sizes`() {
        for (size in listOf(1920 to 1080, 3840 to 2160, 2160 to 3840, 7680 to 4320, 1080 to 2340)) {
            val token = WallpapersDenParser.encodeDownloadToken("12345", size.first, size.second)
            assertEquals("12345_deexpoo_${size.first}x${size.second}", WallpapersDenParser.decodeDownloadToken(token))
        }
    }

    // ------------------------------------------------------- parser edges

    @Test
    fun `grid parsing tolerates attribute order and quote styles`() {
        val html =
            """
            <a href="/order-test-wallpaper/" itemprop="url">
            <img width="320px" height="240px" src="https://images.wallpapersden.com/image/ws-order-test_12345.jpg" alt="Order Test Wallpaper" class="img-thumbnail">
            </a>
            """.trimIndent()

        val items = WallpapersDenParser.parseGrid(html)

        assertEquals(1, items.size)
        assertEquals("order-test", items.first().slug)
        assertEquals("12345", items.first().numericId)
        assertEquals("Order Test Wallpaper", items.first().title)
    }

    @Test
    fun `unrelated markup yields empty grids and no next page`() {
        val html = "<html><body><div class='container'><p>no wallpapers here</p></div></body></html>"

        assertTrue(WallpapersDenParser.parseGrid(html).isEmpty())
        assertNull(WallpapersDenParser.parseNextPage(html))
    }

    @Test
    fun `cards without a site thumbnail drop instead of corrupting the batch`() {
        val html =
            """
            <a itemprop="url" href="/broken-wallpaper"><img class="img-thumbnail" src="https://example.com/other.jpg" alt="Broken"></a>
            <a itemprop="url" href="/healthy-wallpaper/"><img itemprop="thumbnail" class="img-thumbnail" src="https://images.wallpapersden.com/image/ws-healthy_67890.jpg" alt="Healthy Wallpaper"></a>
            """.trimIndent()

        val items = WallpapersDenParser.parseGrid(html)

        assertEquals(1, items.size)
        assertEquals("healthy", items.first().slug)
    }

    @Test
    fun `duplicate cells collapse by their numeric id`() {
        val html = listingGrid + listingGrid

        assertEquals(2, WallpapersDenParser.parseGrid(html).size)
    }

    @Test
    fun `titles unescape the entities the site actually uses`() {
        assertEquals("Assassin's Creed Valhalla", WallpapersDenParser.unescapeEntities("Assassin&#39;s Creed Valhalla"))
        assertEquals("Tom &amp; Jerry", WallpapersDenParser.unescapeEntities("Tom &amp;amp; Jerry"))
    }
}
