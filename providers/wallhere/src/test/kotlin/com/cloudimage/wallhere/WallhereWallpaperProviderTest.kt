package com.cloudimage.wallhere

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ContentRating
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
 * WallHere provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures captured from the site's LIVE markup (October
 * 2026 — fetched through a real browser context, the zone being
 * Cloudflare-walled against datacenter vantages): the masonry grid
 * (`current-item-photo` anchors wrapping lazy `!s1`/`!s2` images whose
 * `title` rows carry the site's own tags), the pagination bar in its
 * `?page=N` dialect with its `<li class="active">` current-page mark,
 * and the wallpaper page's definitive record (`itemprop="contentURL"`
 * display image, the `get.wallhere.com` download anchor, the
 * `profile-link` uploader, and the meta description's original
 * resolution) — all covered without a network, exactly like the
 * WallpaperFlare suite this mirrors.
 */
class WallhereWallpaperProviderTest {
    private val provider = WallhereWallpaperProvider()

    /**
     * URL-routed responses; unmatched URLs answer 500 to fail loudly.
     * Iteration is insertion-ordered, so a deeper page's longer key must
     * be declared before the listing's bare one.
     */
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

    /** A refused exchange — the zone's own challenge answer, as the fake sees it. */
    private fun denied(): ProviderHttpResponse = ProviderHttpResponse(403, emptyMap(), ByteArray(0))

    private fun configureWith(routes: Map<String, ProviderHttpResponse>): FakeClient =
        FakeClient().apply {
            this.routes = routes
            provider.configure(this, ProviderSettings { null })
        }

    // Fixtures: shapes captured from wallhere.com's live pages.

    /**
     * The grid as the site serves it — the exact masonry shape its own
     * listings render: one `current-item-photo` anchor per wallpaper
     * (the numeric id in the detail href, the tag row in the img's
     * `title`, the 1x thumb in `src`, the 2x in `data-src`). The first
     * cell is the live homepage's own first item verbatim in structure —
     * the PNG `images/` family with an adult tag row; the second is the
     * `photos/` JPG family; the third carries site furniture
     * (`wallpaper desktop`) the tag filter must drop; the fourth is the
     * flipped attribute order the parser also reads; the fifth repeats
     * the first cell's wallpaper to prove the id dedupe.
     */
    private val grid =
        """
        <div class="wallpapers-list">
        <a href="/en/wallpaper/2298592" class="current-item-photo">
                    <img width="226" height="340" alt="boobs,headgear,rabbit,sexy,woman" title="boobs,headgear,rabbit,sexy,woman" src="https://c.wallhere.com/images/15/a8/a0b0b4965e59ab5fad45b9fcbd1c-2298592.png!s1" data-src="https://c.wallhere.com/images/15/a8/a0b0b4965e59ab5fad45b9fcbd1c-2298592.png!s2" srcset="https://c.wallhere.com/images/15/a8/a0b0b4965e59ab5fad45b9fcbd1c-2298592.png!s1 1x, https://c.wallhere.com/images/15/a8/a0b0b4965e59ab5fad45b9fcbd1c-2298592.png!s2 2x"></a>
        <a href="/en/wallpaper/2270942" class="current-item-photo">
                    <img width="240" height="340" alt="Black Myth Wukong,Monkey King,monkey,animals,portrait display,closed eyes" title="Black Myth Wukong,Monkey King,monkey,animals,portrait display,closed eyes" src="https://c.wallhere.com/photos/84/71/Black_Myth_Wukong_Monkey_King_monkey_animals_portrait_display_closed_eyes_video_games_video_game_characters-2270942.jpg!s1" data-src="https://c.wallhere.com/photos/84/71/Black_Myth_Wukong_Monkey_King_monkey_animals_portrait_display_closed_eyes_video_games_video_game_characters-2270942.jpg!s2" srcset="https://c.wallhere.com/photos/84/71/Black_Myth_Wukong_Monkey_King_monkey_animals_portrait_display_closed_eyes_video_games_video_game_characters-2270942.jpg!s1 1x, https://c.wallhere.com/photos/84/71/Black_Myth_Wukong_Monkey_King_monkey_animals_portrait_display_closed_eyes_video_games_video_game_characters-2270942.jpg!s2 2x"></a>
        <a href="/en/wallpaper/1579551" class="current-item-photo">
                    <img width="604" height="340" alt="space,galaxy,planet,fantasy,wallpaper desktop,landscape" title="space,galaxy,planet,fantasy,wallpaper desktop,landscape" src="https://c.wallhere.com/images/cb/23/ac4c38042e2394ecb8d8ffbed81c-1579551.png!s1" data-src="https://c.wallhere.com/images/cb/23/ac4c38042e2394ecb8d8ffbed81c-1579551.png!s2" srcset="https://c.wallhere.com/images/cb/23/ac4c38042e2394ecb8d8ffbed81c-1579551.png!s1 1x"></a>
        <a class="current-item-photo" href="/en/wallpaper/598783">
                    <img width="320" height="340" alt="mountain,snow,sunset" title="mountain,snow,sunset" src="https://c.wallhere.com/photos/4b/90/mountain_snow_sunset-598783.jpg!s1" data-src="https://c.wallhere.com/photos/4b/90/mountain_snow_sunset-598783.jpg!s2"></a>
        <a href="/en/wallpaper/2298592" class="current-item-photo">
                    <img width="226" height="340" alt="boobs,headgear,rabbit,sexy,woman" title="boobs,headgear,rabbit,sexy,woman" src="https://c.wallhere.com/images/15/a8/a0b0b4965e59ab5fad45b9fcbd1c-2298592.png!s1"></a>
        </div>
        """.trimIndent()

    /**
     * The pagination bar as the live homepage renders it: the active
     * first item, numbered `?page=` links, ellipsis, the two deepest
     * pages, and the `»` next arrow. Its deepest link (70324) is what
     * the parser reads as the bar's max.
     */
    private val bar =
        """
        <ul class="pagination pagination-lg"><li><a href="javascript:void(0)"><i class="fa fa-angle-left">«</i></a></li><li class="active"><a href="/">1</a></li><li class=""><a href="/?page=2">2</a></li><li class=""><a href="/?page=3">3</a></li><li class=""><a href="/?page=4">4</a></li><li class=""><a href="/?page=5">5</a></li><li class=""><a href="javascript:void(0)">...</a></li><li class=""><a href="/?page=70323">70323</a></li><li class=""><a href="/?page=70324">70324</a></li><li><a href="/?page=2" title="»"><i class="fa fa-angle-right">»</i></a></li></ul>
        """.trimIndent()

    /** A listing page: the grid plus its bar. */
    private val listing = "$grid\n$bar"

    /**
     * The bar of a LAST page: the active item is the deepest numbered
     * link — no page beyond it exists.
     */
    private val lastPageBar =
        """
        <ul class="pagination pagination-lg"><li><a href="/en/tag/9"><i class="fa fa-angle-left">«</i></a></li><li class=""><a href="/en/tag/9">1</a></li><li class="active"><a href="/en/tag/9?page=2">2</a></li><li><a href="javascript:void(0)" title="»"><i class="fa fa-angle-right">»</i></a></li></ul>
        """.trimIndent()

    /**
     * The wallpaper page's definitive record as the live page states
     * it: the meta description whose tail carries the ORIGINAL's
     * resolution, the uploader's profile link, the `contentURL` display
     * image (its own 1200x1696 display pair and its `alt` tag row), the
     * `get.wallhere.com` download anchor — and a related-wallpaper grid
     * cell whose `current-item-photo` anchors the detail pass must
     * ignore.
     */
    private val detailPage =
        """
        <html><head>
        <meta name="description" content="Downloads lrxv Wallpaper  : Black Myth Wukong, Monkey King, monkey, animals, portrait display, closed eyes, video games, video game characters, CGI, video game man, frown, blurred, blurry background 1570x2220,2270942">
        <meta property="og:image" content="https://c.wallhere.com/photos/84/71/Black_Myth_Wukong_Monkey_King_monkey_animals_portrait_display_closed_eyes_video_games_video_game_characters-2270942.jpg!d">
        </head><body>
        <a href="/en/user/5701380" class="profile-link">lrxv / 220 Wallpapers</a>
        <img itemprop="contentURL" width="1200" height="1696" src="https://c.wallhere.com/photos/84/71/Black_Myth_Wukong_Monkey_King_monkey_animals_portrait_display_closed_eyes_video_games_video_game_characters-2270942.jpg!d" srcset="https://c.wallhere.com/photos/84/71/Black_Myth_Wukong_Monkey_King_monkey_animals_portrait_display_closed_eyes_video_games_video_game_characters-2270942.jpg!d" alt="Black Myth Wukong, Monkey King, monkey, animals, portrait display, closed eyes, video games, video game characters, CGI, video game man, frown, blurred, blurry background">
        <a href="https://get.wallhere.com/photo/Black-Myth-Wukong-Monkey-King-monkey-animals-portrait-display-closed-eyes-video-games-video-game-characters-CGI-video-game-man-frown-blurred-blurry-background-2270942.jpg" class="current-page-photo" onclick="hubCommon.ajaxShowDownload(); return false" title="Download Wallpaper Black Myth Wukong, Monkey King, monkey, animals, portrait display, closed eyes, video games, video game characters, CGI, video game man, frown, blurred, blurry background">Download</a>
        <a href="/en/wallpaper/598783" class="current-item-photo">
                    <img width="320" height="340" alt="mountain,snow,sunset" title="mountain,snow,sunset" src="https://c.wallhere.com/photos/4b/90/mountain_snow_sunset-598783.jpg!s1" data-src="https://c.wallhere.com/photos/4b/90/mountain_snow_sunset-598783.jpg!s2"></a>
        </body></html>
        """.trimIndent()

    // ------------------------------------------------------------ identity

    @Test
    fun metaIdentifiesTheSourceHonestly() {
        assertEquals("cloudimage.wallhere", provider.meta.id)
        assertEquals("WallHere", provider.meta.name)
        assertEquals(ContentRating.SKETCHY, provider.meta.contentRating)
        assertEquals(false, provider.meta.requiresApiKey)
    }

    @Test
    fun capabilitiesAdvertiseWhatTheSiteServes() {
        assertTrue(Capability.RANDOM in provider.capabilities)
        assertTrue(Capability.SEARCH in provider.capabilities)
        assertTrue(Capability.LATEST in provider.capabilities)
    }

    // --------------------------------------------------------------- feeds

    @Test
    fun popularParsesTheGridIntoWallpapers() =
        runTest {
            val client = configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(listing)))
            provider.popular(1, Filters.None).getOrThrow().also { page ->
                assertEquals(4, page.wallpapers.size)
                assertEquals(2, page.nextPage)
                assertEquals("https://wallhere.com/en/wallpapers?order=popular", client.requests.single())
            }
        }

    @Test
    fun gridItemsCarryThumbAndDisplayFullUrl() =
        runTest {
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(listing)))
            val wallpapers = provider.popular(1, Filters.None).getOrThrow().wallpapers
            val first = wallpapers.first()
            assertEquals("https://c.wallhere.com/images/15/a8/a0b0b4965e59ab5fad45b9fcbd1c-2298592.png!s1", first.thumbUrl)
            assertEquals("https://c.wallhere.com/images/15/a8/a0b0b4965e59ab5fad45b9fcbd1c-2298592.png!d", first.fullUrl)
        }

    @Test
    fun gridItemsPublishNoDimensions() =
        runTest {
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(listing)))
            provider.popular(1, Filters.None).getOrThrow().wallpapers.forEach { wallpaper ->
                assertNull(wallpaper.width)
                assertNull(wallpaper.height)
            }
        }

    @Test
    fun adultTaggedItemsAreClassifiedNsfw() =
        runTest {
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(listing)))
            val wallpapers = provider.popular(1, Filters.None).getOrThrow().wallpapers
            assertEquals(ContentRating.NSFW, wallpapers.first { it.id == "2298592" }.contentRating)
            assertEquals(ContentRating.SFW, wallpapers.first { it.id == "1579551" }.contentRating)
        }

    @Test
    fun tagRowsDropSiteFurnitureAndCap() =
        runTest {
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(listing)))
            val wallpapers = provider.popular(1, Filters.None).getOrThrow().wallpapers
            val space = wallpapers.first { it.id == "1579551" }
            assertEquals(listOf("space", "galaxy", "planet", "fantasy", "landscape"), space.tags)
        }

    @Test
    fun deepPagesAppendTheQueryDialect() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?order=popular&page=2" to ok("$grid\n$lastPageBar"),
                        "https://wallhere.com/en/wallpapers?order=popular" to ok(listing),
                    ),
                )
            provider.popular(1, Filters.None).getOrThrow()
            provider.popular(2, Filters.None).getOrThrow().also { page ->
                assertEquals("https://wallhere.com/en/wallpapers?order=popular&page=2", client.requests.last())
                assertNull(page.nextPage)
            }
        }

    @Test
    fun currentPageDisagreementStopsTheWalk() =
        runTest {
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(listing)))
            // The fixture's bar marks page 1 as current; a page-3 request
            // the site ignored must not report a next page.
            provider.popular(3, Filters.None).getOrThrow().also { page ->
                assertEquals(4, page.wallpapers.size)
                assertNull(page.nextPage)
            }
        }

    @Test
    fun beyondTheCapAnswersEmptyWithoutARequest() =
        runTest {
            val client = configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(listing)))
            provider.popular(101, Filters.None).getOrThrow().also { page ->
                assertTrue(page.wallpapers.isEmpty())
                assertNull(page.nextPage)
                assertTrue(client.requests.isEmpty())
            }
        }

    @Test
    fun nonSuccessfulAnswersAreSourceFailures() =
        runTest {
            val client = configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to denied()))
            val failure = provider.popular(1, Filters.None).exceptionOrNull()
            assertEquals("wallhere answered HTTP 403", (failure as IllegalStateException).message)
            assertEquals("https://wallhere.com/en/wallpapers?order=popular", client.requests.single())
        }

    // --------------------------------------------------------------- filters

    @Test
    fun sortingDateWalksTheLatestOrder() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?order=latest" to ok(listing),
                        "https://wallhere.com/en/wallpapers?order=popular" to ok(listing),
                    ),
                )
            provider.popular(1, Filters.of("sorting" to "date")).getOrThrow()
            assertEquals("https://wallhere.com/en/wallpapers?order=latest", client.requests.single())
        }

    @Test
    fun sortingRandomServesOneFreshBatch() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallhere.com/en/random" to ok(grid)),
                )
            provider.popular(1, Filters.of("sorting" to "random")).getOrThrow().also { page ->
                assertEquals("https://wallhere.com/en/random", client.requests.single())
                assertNull(page.nextPage)
                assertEquals(4, page.wallpapers.size)
            }
        }

    @Test
    fun categoryFiltersWalkTheirOwnSearches() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?q=portrait" to ok(listing),
                        "https://wallhere.com/en/wallpapers?q=anime" to ok(listing),
                    ),
                )
            provider.popular(1, Filters.of("category" to "anime")).getOrThrow()
            assertEquals("https://wallhere.com/en/wallpapers?q=anime", client.requests.first())
            provider.popular(1, Filters.of("category" to "people")).getOrThrow()
            assertEquals("https://wallhere.com/en/wallpapers?q=portrait", client.requests.last())
        }

    // --------------------------------------------------------------- search

    @Test
    fun searchRidesTheSiteFormEncoding() =
        runTest {
            val client = configureWith(mapOf("https://wallhere.com/en/wallpapers?q=black+myth" to ok(listing)))
            provider.search("black myth", 1, Filters.None).getOrThrow().also { page ->
                assertEquals(4, page.wallpapers.size)
                assertEquals("https://wallhere.com/en/wallpapers?q=black+myth", client.requests.single())
                assertEquals(2, page.nextPage)
            }
        }

    @Test
    fun searchPaginatesWithTheQueryDialect() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?q=black+myth&page=2" to ok("$grid\n$lastPageBar"),
                        "https://wallhere.com/en/wallpapers?q=black+myth" to ok(listing),
                    ),
                )
            provider.search("black myth", 1, Filters.None).getOrThrow()
            provider.search("black myth", 2, Filters.None).getOrThrow().also { page ->
                assertEquals("https://wallhere.com/en/wallpapers?q=black+myth&page=2", client.requests.last())
                assertNull(page.nextPage)
            }
        }

    @Test
    fun blankSearchLandsOnTheLatestOrder() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?order=latest" to ok(listing),
                        "https://wallhere.com/en/wallpapers?order=popular" to ok(listing),
                    ),
                )
            provider.search("   ", 1, Filters.None).getOrThrow()
            assertEquals("https://wallhere.com/en/wallpapers?order=latest", client.requests.single())
        }

    @Test
    fun suggestTagsServesHarvestedRows() =
        runTest {
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(listing)))
            provider.popular(1, Filters.None).getOrThrow()
            provider.suggestTags("gal").getOrThrow().also { suggestions ->
                assertEquals(listOf("galaxy"), suggestions)
            }
        }

    @Test
    fun freshInstancesSuggestNothing() =
        runTest {
            assertTrue(provider.suggestTags("gal").getOrThrow().isEmpty())
        }

    // ------------------------------------------------------------- sections

    @Test
    fun sectionsOfferTheRankedFeedsAndPresets() =
        runTest {
            val sections = provider.sections()
            assertEquals(HomeSection.DEFAULT_ID, sections.first().id)
            assertEquals("Popular", sections.first().title)
            assertEquals("Latest", sections.first { it.id == "latest" }.title)
            assertTrue(sections.any { it.id == "anime" && it.filters.isSelected("query", "anime") })
            assertTrue(sections.size >= 10)
        }

    // -------------------------------------------------------------- details

    @Test
    fun detailsReadTheDefinitiveRecord() =
        runTest {
            val client = configureWith(mapOf("https://wallhere.com/en/wallpaper/2270942" to ok(detailPage)))
            provider.details("2270942").getOrThrow().also { details ->
                assertEquals("https://wallhere.com/en/wallpaper/2270942", client.requests.single())
                assertEquals(
                    "https://get.wallhere.com/photo/Black-Myth-Wukong-Monkey-King-monkey-animals-portrait-display-closed-eyes-video-games-video-game-characters-CGI-video-game-man-frown-blurred-blurry-background-2270942.jpg",
                    details.wallpaper.fullUrl,
                )
                assertEquals("", details.wallpaper.thumbUrl)
                assertEquals(1570, details.wallpaper.width)
                assertEquals(2220, details.wallpaper.height)
                assertEquals("1570x2220", details.resolution)
                assertEquals("lrxv", details.author)
                assertEquals("https://wallhere.com/en/wallpaper/2270942", details.sourceUrl)
                assertTrue(details.wallpaper.tags.contains("Black Myth Wukong"))
                assertEquals(ContentRating.SFW, details.wallpaper.contentRating)
            }
        }

    @Test
    fun detailsFallBackToTheDisplayOriginal() =
        runTest {
            val degraded = detailPage.replace("https://get.wallhere.com/", "https://gone.wallhere.com/")
            configureWith(mapOf("https://wallhere.com/en/wallpaper/2270942" to ok(degraded)))
            provider.details("2270942").getOrThrow().also { details ->
                assertTrue(details.wallpaper.fullUrl.endsWith("!d"))
                assertEquals(1570, details.wallpaper.width)
                assertEquals(2220, details.wallpaper.height)
            }
        }

    @Test
    fun unrecognizedPagesAreSourceFailures() =
        runTest {
            configureWith(mapOf("https://wallhere.com/en/wallpaper/2270942" to ok("<html><body>gone</body></html>")))
            val failure = provider.details("2270942").exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }

    // --------------------------------------------------------------- random

    @Test
    fun randomServesTheSitesOwnBatch() =
        runTest {
            val client = configureWith(mapOf("https://wallhere.com/en/random" to ok(grid)))
            provider.random().getOrThrow().also { wallpapers ->
                assertEquals("https://wallhere.com/en/random", client.requests.single())
                assertEquals(4, wallpapers.size)
                assertTrue(wallpapers.all { it.fullUrl.endsWith("!d") })
            }
        }

    // -------------------------------------------------------- parser extras

    @Test
    fun parserReadsTheBarOfABarlessPage() {
        val pagination = WallhereParser.parsePagination("<html><body>no bar here</body></html>")
        assertNull(pagination.currentPage)
        assertEquals(0, pagination.maxPage)
    }

    @Test
    fun parserSwapsOnlyKnownThumbSuffixes() {
        assertNull(WallhereParser.toDisplayUrl("https://c.wallhere.com/images/15/a8/file.png"))
        assertNull(WallhereParser.toDisplayUrl("https://c.wallhere.com/images/15/a8/file.png!d"))
        assertEquals(
            "https://c.wallhere.com/photos/4b/90/file-598783.jpg!d",
            WallhereParser.toDisplayUrl("https://c.wallhere.com/photos/4b/90/file-598783.jpg!s2"),
        )
    }
}
