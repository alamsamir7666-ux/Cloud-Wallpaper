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
 * 2026 — re-verified through a reader vantage that passes the zone's
 * Cloudflare challenge): the masonry grid (`current-item-photo` anchors
 * wrapping lazy `!s1`/`!s2` images whose `title` rows carry the site's
 * own tags), the pagination bar in its `?page=N` dialect with its
 * `<li class="active">` current-page mark, and the wallpaper page's
 * definitive record (`itemprop="contentURL"` display image, the
 * `get.wallhere.com` download anchor, the `profile-link` uploader, and
 * the meta description's original resolution) — all covered without a
 * network, exactly like the WallpaperFlare suite this mirrors. The
 * v1.1.0 additions — the site's own `NSFW=off` purity switch, the
 * baked tag catalogue's exact-match lanes, and offline suggestions
 * from a fresh instance — ride the same fake, as do the v1.1.2
 * additions: the MOBILE listing markup (the same 24-cell grid with no
 * pagination bar, whose full pages must keep the walk going by
 * themselves) and the overlap guard's quiet ends.
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
        val requestHeaders = mutableListOf<Map<String, String>>()
        var routes: Map<String, ProviderHttpResponse> = emptyMap()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            requestHeaders += headers
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
     * A MOBILE listing page's grid (v1.1.2): the same masonry cells the
     * desktop serves, sized to the site's fixed page, with NO pagination
     * bar at all — the shape the site hands its mobile agents, whose
     * "more exists" signal is the page's own infinite-scroll wiring
     * rather than any markup the parser can read. [count] cells with
     * sequential ids starting at [firstId].
     */
    private fun mobileGridOf(
        count: Int,
        firstId: Int = 3000001,
    ): String =
        (0 until count).joinToString("\n") { index ->
            val id = firstId + index
            """
            <a href="/en/wallpaper/$id" class="current-item-photo">
                        <img width="240" height="340" alt="mobile,grid,wallpaper" title="mobile,grid,wallpaper" src="https://c.wallhere.com/photos/11/22/mobile_grid_$id.jpg!s1" data-src="https://c.wallhere.com/photos/11/22/mobile_grid_$id.jpg!s2"></a>
            """.trimIndent()
        }

    /**
     * The bar of a LAST page: the active item is the deepest numbered
     * link — no page beyond it exists.
     */
    private val lastPageBar =
        """
        <ul class="pagination pagination-lg"><li><a href="/en/tag/9"><i class="fa fa-angle-left">«</i></a></li><li class=""><a href="/en/tag/9">1</a></li><li class="active"><a href="/en/tag/9?page=2">2</a></li><li><a href="javascript:void(0)" title="»"><i class="fa fa-angle-right">»</i></a></li></ul>
        """.trimIndent()

    /**
     * A page-two listing whose cells are FRESH (ids no earlier page
     * served) under a last-page bar — the shape every URL-dialect test
     * needs for its second page, now that the first continuation must
     * deliver fresh wallpapers or fail by name (v1.1.3).
     */
    private val freshPageTwo = "${mobileGridOf(24, firstId = 2271000)}\n$lastPageBar"

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
    fun multiWordAdultRowsAreClassifiedNsfw() =
        runTest {
            // The compound adult rows the site's own vocabulary carries — "panties
            // down" and "Lingerie Football League" straight from the suggestion
            // filter's own doc, plus a hyphenated "anime-hentai" — must classify
            // NSFW (v1.1.4: v1.1.2 matched whole tag strings only and let every
            // one of these through as SFW), while innocent words that merely
            // CONTAIN an adult word ("bass", "sea grass") stay SFW: the check is
            // per WORD, never substring.
            val compoundGrid =
                listOf(
                    "9000001" to "panties down,bedroom,woman",
                    "9000002" to "Lingerie Football League,sport,woman",
                    "9000003" to "anime-hentai,cartoon",
                    "9000004" to "bass guitar,sea grass,music",
                ).joinToString("\n") { (id, row) ->
                    """
                    <a href="/en/wallpaper/$id" class="current-item-photo">
                                    <img width="240" height="340" alt="$row" title="$row" src="https://c.wallhere.com/photos/11/22/compound_$id.jpg!s1" data-src="https://c.wallhere.com/photos/11/22/compound_$id.jpg!s2"></a>
                    """.trimIndent()
                }
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(compoundGrid)))
            val wallpapers = provider.popular(1, Filters.None).getOrThrow().wallpapers
            assertEquals(4, wallpapers.size)
            assertEquals(ContentRating.NSFW, wallpapers.first { it.id == "9000001" }.contentRating)
            assertEquals(ContentRating.NSFW, wallpapers.first { it.id == "9000002" }.contentRating)
            assertEquals(ContentRating.NSFW, wallpapers.first { it.id == "9000003" }.contentRating)
            assertEquals(ContentRating.SFW, wallpapers.first { it.id == "9000004" }.contentRating)
        }

    @Test
    fun tagRowsDecodeHtmlEntities() =
        runTest {
            // Tag rows decode their HTML entities before they become tags
            // (v1.1.4): decimal and hex code points ("café", "città"), the
            // apostrophe family ("rock'n'roll" from `&#x27;` and `&#39;`), and a
            // named ampersand ("salt & pepper") — while a DOUBLE-encoded entity
            // (`T&amp;#39;shirt`) stays the literal text `T&#39;shirt` it honestly
            // is, never decoded twice.
            val entityRow = "caf&#233;,citt&#xE0;,rock&#x27;n&#x27;roll,salt &amp; pepper,T&amp;#39;shirt"
            val entityGrid =
                """
                <a href="/en/wallpaper/9100001" class="current-item-photo">
                                <img width="240" height="340" alt="$entityRow" title="$entityRow" src="https://c.wallhere.com/photos/11/22/entities_9100001.jpg!s1" data-src="https://c.wallhere.com/photos/11/22/entities_9100001.jpg!s2"></a>
                """.trimIndent()
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(entityGrid)))
            provider.popular(1, Filters.None).getOrThrow().also { page ->
                assertEquals(
                    listOf("café", "città", "rock'n'roll", "salt & pepper", "T&#39;shirt"),
                    page.wallpapers.single().tags,
                )
            }
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
                        "https://wallhere.com/en/wallpapers?order=popular&page=2" to ok(freshPageTwo),
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
    fun currentPageDisagreementIsADescribedFailure() =
        runTest {
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(listing)))
            // The fixture's bar marks page 1 as current; a page-3 request
            // the site ignored must NAME the mismatch (v1.1.1: v1.1.0
            // answered page one's items with no next page, appending
            // duplicates into keyed grids).
            val failure = provider.popular(3, Filters.None).exceptionOrNull()
            assertEquals(
                "wallhere served page 1 where page 3 was requested — the page parameter did not take",
                (failure as IllegalStateException).message,
            )
        }

    @Test
    fun continuationWithoutAWallpaperGridIsADescribedFailure() =
        runTest {
            // A page-two answer whose bar marks page two but whose grid
            // carries no cells: v1.1.0 answered an empty page whose next
            // page could still exist, looping the host's load-more
            // footer forever.
            configureWith(
                mapOf(
                    "https://wallhere.com/en/wallpapers?order=popular&page=2" to ok(lastPageBar),
                    "https://wallhere.com/en/wallpapers?order=popular" to ok(listing),
                ),
            )
            provider.popular(1, Filters.None).getOrThrow()
            val failure = provider.popular(2, Filters.None).exceptionOrNull()
            assertEquals(
                "wallhere's page 2 carried no recognizable wallpapers",
                (failure as IllegalStateException).message,
            )
        }

    // ------------------------------------------- the mobile markup (v1.1.2)

    @Test
    fun barlessFullPagesWalkOn() =
        runTest {
            // The mobile markup: no bar anywhere, so the page ITSELF is
            // the signal — a full 24-cell page of fresh wallpapers
            // advertises the next one. This is the field-reported shape
            // that stopped every feed at exactly 24 items.
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?order=popular&page=2" to ok(mobileGridOf(24, firstId = 3000025)),
                        "https://wallhere.com/en/wallpapers?order=popular" to ok(mobileGridOf(24)),
                    ),
                )
            provider.popular(1, Filters.None).getOrThrow().also { page ->
                assertEquals(24, page.wallpapers.size)
                assertEquals(2, page.nextPage)
            }
            provider.popular(2, Filters.None).getOrThrow().also { page ->
                assertEquals("https://wallhere.com/en/wallpapers?order=popular&page=2", client.requests.last())
                assertEquals(24, page.wallpapers.size)
                assertTrue(page.wallpapers.all { it.id.toInt() >= 3000025 })
                assertEquals(3, page.nextPage)
            }
        }

    @Test
    fun barlessPartialPagesAreTheHonestEnd() =
        runTest {
            // No bar and a page that came up short of the site's fixed
            // size: the last page of a listing.
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(mobileGridOf(10))))
            provider.popular(1, Filters.None).getOrThrow().also { page ->
                assertEquals(10, page.wallpapers.size)
                assertNull(page.nextPage)
            }
        }

    @Test
    fun barlessEmptyPageTwoIsANamedFailure() =
        runTest {
            // No bar, no grid, page two: page one served a FULL page, so
            // this is not a listing that ran out — it is markup the
            // parser no longer reads answering the first continuation
            // (v1.1.3). v1.1.2 ended quietly here, which is exactly how
            // a broken continuation dialect hid as "exactly 24 items,
            // no error" in the field; the failure now carries its name.
            configureWith(
                mapOf(
                    "https://wallhere.com/en/wallpapers?order=popular&page=2" to ok("<html><body></body></html>"),
                    "https://wallhere.com/en/wallpapers?order=popular" to ok(mobileGridOf(24)),
                ),
            )
            provider.popular(1, Filters.None).getOrThrow()
            val failure = provider.popular(2, Filters.None).exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("carried no wallpapers the parser recognizes"))
        }

    @Test
    fun pageTwoRepeatsPageOneIsANamedFailure() =
        runTest {
            // Page two repeating page one verbatim is not a clamp — no
            // listing this site serves is one page deep when its first
            // page was FULL. It is the page parameter not advancing on
            // this connection (the mobile view ignoring it), and v1.1.3
            // names it: the field report then says WHY the walk stopped
            // instead of merely that it did. Deeper repeats stay quiet
            // clamp-ends (see deepClampedContinuationsStillEndQuietly).
            configureWith(
                mapOf(
                    "https://wallhere.com/en/wallpapers?order=popular&page=2" to ok(mobileGridOf(24)),
                    "https://wallhere.com/en/wallpapers?order=popular" to ok(mobileGridOf(24)),
                ),
            )
            provider.popular(1, Filters.None).getOrThrow()
            val failure = provider.popular(2, Filters.None).exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertTrue(failure!!.message!!.contains("repeated page 1's wallpapers"))
        }

    @Test
    fun deepClampedContinuationsStillEndQuietly() =
        runTest {
            // The honest clamp shape: pages one and two served their
            // fresh twenty-four, page three repeats page two — the walk
            // ended one page too deep, nothing new arrives, and the walk
            // ends with NOTHING appended. The page-2 honesty rules leave
            // this branch exactly as quiet as v1.1.2 kept it.
            configureWith(
                mapOf(
                    "https://wallhere.com/en/wallpapers?order=popular&page=3" to ok(mobileGridOf(24, firstId = 3000025)),
                    "https://wallhere.com/en/wallpapers?order=popular&page=2" to ok(mobileGridOf(24, firstId = 3000025)),
                    "https://wallhere.com/en/wallpapers?order=popular" to ok(mobileGridOf(24)),
                ),
            )
            provider.popular(1, Filters.None).getOrThrow()
            provider.popular(2, Filters.None).getOrThrow()
            provider.popular(3, Filters.None).getOrThrow().also { page ->
                assertTrue(page.wallpapers.isEmpty())
                assertNull(page.nextPage)
            }
        }

    @Test
    fun everyDocumentFetchCarriesTheDesktopIdentity() =
        runTest {
            // v1.1.3: every document fetch claims a desktop Chrome agent
            // through the facade's sanctioned per-request User-Agent
            // override — the site serves mobile agents the bar-less
            // infinite-scroll dialect, and on a phone every host fetch
            // lane is mobile or Android-branded without it. The literal
            // is repeated here on purpose: a UA change should have to
            // walk through this assertion too.
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?order=popular" to ok(listing),
                        "https://wallhere.com/en/wallpaper/2270942" to ok(detailPage),
                    ),
                )
            provider.popular(1, Filters.None).getOrThrow()
            provider.details("2270942").getOrThrow()

            assertTrue(
                client.requestHeaders.all {
                    it["User-Agent"] ==
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                },
            )
        }

    @Test
    fun overlappingContinuationsKeepOnlyTheFreshCells() =
        runTest {
            // A page two that repeats half of page one (an order that
            // shifted between fetches): the repeats drop, the fresh cells
            // ride on — and a short fresh remainder ends the walk.
            val halfRepeat = mobileGridOf(12) + "\n" + mobileGridOf(12, firstId = 3000025)
            configureWith(
                mapOf(
                    "https://wallhere.com/en/wallpapers?order=popular&page=2" to ok(halfRepeat),
                    "https://wallhere.com/en/wallpapers?order=popular" to ok(mobileGridOf(24)),
                ),
            )
            provider.popular(1, Filters.None).getOrThrow()
            provider.popular(2, Filters.None).getOrThrow().also { page ->
                assertEquals(12, page.wallpapers.size)
                assertTrue(page.wallpapers.all { it.id.toInt() >= 3000025 })
                assertNull(page.nextPage)
            }
        }

    @Test
    fun pageOneRevisitsResetTheWalk() =
        runTest {
            // A refreshed feed is a NEW walk: the second page-one fetch
            // must answer its items again, not "everything already
            // seen" — the overlap guard's memory is per-walk, replaced
            // by every page one.
            configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(mobileGridOf(24))))
            provider.popular(1, Filters.None).getOrThrow().also { page ->
                assertEquals(24, page.wallpapers.size)
                assertEquals(2, page.nextPage)
            }
            provider.popular(1, Filters.None).getOrThrow().also { page ->
                assertEquals(24, page.wallpapers.size)
                assertEquals(2, page.nextPage)
            }
        }

    @Test
    fun blankQueriesHonorTheRequestedPage() =
        runTest {
            // The contract's escape hatch walks the latest order at the
            // page the host asked for — v1.1.0 hardcoded page one.
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?order=latest&page=2" to ok("$grid\n$lastPageBar"),
                        "https://wallhere.com/en/wallpapers?order=latest" to ok(listing),
                    ),
                )
            provider.search("   ", 2, Filters.None).getOrThrow()
            assertEquals("https://wallhere.com/en/wallpapers?order=latest&page=2", client.requests.single())
        }

    @Test
    fun anUnrecognizedFirstPageStaysTheEmptyFeed() =
        runTest {
            // Page one keeps the quiet shape: a first page whose grid
            // parses to nothing is the honest "no results" surface, not
            // a source failure.
            configureWith(mapOf("https://wallhere.com/en/wallpapers?q=zzz" to ok(bar)))
            provider.search("zzz", 1, Filters.None).getOrThrow().also { page ->
                assertTrue(page.wallpapers.isEmpty())
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
    fun categoryFiltersWalkTheirOwnTagLanes() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/tag/150" to ok(listing),
                        "https://wallhere.com/en/tag/80" to ok(listing),
                    ),
                )
            provider.popular(1, Filters.of("category" to "anime")).getOrThrow()
            assertEquals("https://wallhere.com/en/tag/150", client.requests.first())
            provider.popular(1, Filters.of("category" to "people")).getOrThrow()
            assertEquals("https://wallhere.com/en/tag/80", client.requests.last())
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
                        "https://wallhere.com/en/wallpapers?q=black+myth&page=2" to ok(freshPageTwo),
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
    fun freshInstancesSuggestFromTheBakedCatalogue() =
        runTest {
            provider.suggestTags("nat").getOrThrow().also { suggestions ->
                assertTrue("fresh instance must suggest baked tags", suggestions.isNotEmpty())
                assertEquals("Nature", suggestions.first())
            }
            provider.suggestTags("long ").getOrThrow().also { suggestions ->
                assertTrue(suggestions.contains("Long hair"))
            }
        }

    @Test
    fun suggestionsWithholdAdultVocabulary() =
        runTest {
            provider.suggestTags("pan").getOrThrow().also { suggestions ->
                assertTrue("Panties" !in suggestions)
            }
            provider.suggestTags("lin").getOrThrow().also { suggestions ->
                assertTrue("Lingerie" !in suggestions)
            }
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

    @Test
    fun sectionPresetsNameCatalogueTags() =
        runTest {
            val sections = provider.sections()
            // The site's tag is "Car" (singular) and "Video games" —
            // the presets NAME catalogue tags so each row rides the
            // exact-match tag lane through search().
            assertTrue(sections.any { it.id == "cars" && it.filters.isSelected("query", "car") })
            assertTrue(sections.any { it.id == "games" && it.filters.isSelected("query", "video games") })
            assertTrue(sections.any { it.id == "night" && it.filters.isSelected("query", "night") })
            assertTrue(sections.any { it.id == "mountains" && it.filters.isSelected("query", "mountains") })
        }

    // -------------------------------------------------------------- purity

    @Test
    fun puritySfwOnlyCarriesTheSitesPuritySwitch() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?order=popular&NSFW=off&page=2" to ok(freshPageTwo),
                        "https://wallhere.com/en/wallpapers?order=popular&NSFW=off" to ok(listing),
                    ),
                )
            val sfw = Filters.of("purity" to "sfw")
            provider.popular(1, sfw).getOrThrow()
            assertEquals("https://wallhere.com/en/wallpapers?order=popular&NSFW=off", client.requests.first())
            provider.popular(2, sfw).getOrThrow().also { page ->
                assertEquals("https://wallhere.com/en/wallpapers?order=popular&NSFW=off&page=2", client.requests.last())
                assertNull(page.nextPage)
            }
        }

    @Test
    fun purityWithSketchyLeavesTheSwitchOff() =
        runTest {
            val client =
                configureWith(mapOf("https://wallhere.com/en/wallpapers?order=popular" to ok(listing)))
            provider.popular(1, Filters.of("purity" to "sfw", "purity" to "sketchy")).getOrThrow()
            assertEquals("https://wallhere.com/en/wallpapers?order=popular", client.requests.single())
        }

    @Test
    fun unspecifiedPurityStaysBare() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?order=popular" to ok(listing),
                    ),
                )
            provider.popular(1, Filters.None).getOrThrow()
            assertEquals("https://wallhere.com/en/wallpapers?order=popular", client.requests.single())
        }

    @Test
    fun randomSortingCarriesThePuritySwitch() =
        runTest {
            val client =
                configureWith(mapOf("https://wallhere.com/en/random?NSFW=off" to ok(grid)))
            provider.popular(1, Filters.of("purity" to "sfw", "sorting" to "random")).getOrThrow()
            assertEquals("https://wallhere.com/en/random?NSFW=off", client.requests.single())
        }

    // ----------------------------------------------------------- tag lanes

    @Test
    fun exactTagQueriesWalkTheTagsOwnGrid() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/tag/212?page=2" to ok("$grid\n$lastPageBar"),
                        "https://wallhere.com/en/tag/212" to ok(listing),
                    ),
                )
            provider.search("nature", 1, Filters.None).getOrThrow().also { page ->
                assertEquals("https://wallhere.com/en/tag/212", client.requests.first())
                assertEquals(2, page.nextPage)
            }
            provider.search("nature", 2, Filters.None)
            assertEquals("https://wallhere.com/en/tag/212?page=2", client.requests.last())
        }

    @Test
    fun tagQueriesResolveCaseAndWhitespaceInsensitively() =
        runTest {
            val client = configureWith(mapOf("https://wallhere.com/en/tag/138" to ok(listing)))
            provider.search("  LONG   HAIR ", 1, Filters.None).getOrThrow()
            assertEquals("https://wallhere.com/en/tag/138", client.requests.single())
        }

    @Test
    fun tagLanesCarryThePuritySwitch() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/tag/212?NSFW=off&page=2" to ok("$grid\n$lastPageBar"),
                        "https://wallhere.com/en/tag/212?NSFW=off" to ok(listing),
                    ),
                )
            val sfw = Filters.of("purity" to "sfw")
            provider.search("nature", 1, sfw).getOrThrow()
            assertEquals("https://wallhere.com/en/tag/212?NSFW=off", client.requests.first())
            provider.search("nature", 2, sfw)
            assertEquals("https://wallhere.com/en/tag/212?NSFW=off&page=2", client.requests.last())
        }

    @Test
    fun unknownQueriesStillRideTheSiteSearch() =
        runTest {
            val client = configureWith(mapOf("https://wallhere.com/en/wallpapers?q=black+myth" to ok(listing)))
            provider.search("black myth", 1, Filters.None).getOrThrow()
            assertEquals("https://wallhere.com/en/wallpapers?q=black+myth", client.requests.single())
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
