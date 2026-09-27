package com.cloudimage.hdqwalls

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
 * HDQWalls provider over a scripted fake of the plugin-facing HTTP facade,
 * with fixtures cut from the live site's markup: the shared listing grid
 * (popular, category, search), the pagination bar's Next-link contract,
 * the blank-query and deep-pagination guards, the detail record's
 * resolution/author/size/tags, and the host-vocabulary routing all stay
 * covered without a network.
 */
class HdqWallsWallpaperProviderTest {
    private val provider = HdqWallsWallpaperProvider()

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

    // Fixtures: shapes captured from hdqwalls.com, trimmed to the parts the
    // parsers key on.

    /** The popular feed's first cells — real markup, single quotes, caption anchor first. */
    private val popularGrid =
        """
        <div class='wall-resp col-lg-4 col-md-4 col-sm-4 col-xs-6 column_padding'>
              <a href='https://hdqwalls.com/garena-free-fire-wallpaper' title='Garena Free Fire Wallpaper' class='caption hidden-md hidden-sm hidden-xs' style='z-index:1'>Garena Free Fire</a>
              <a href='https://hdqwalls.com/garena-free-fire-wallpaper' title='Garena Free Fire Wallpaper'>
                  <img width='602' height='339' src='https://images.hdqwalls.com/wallpapers/bthumb/garena-free-fire-z4.jpg' title='Garena Free Fire Wallpaper' alt='Garena Free Fire Wallpaper' class='thumbnail img-responsive custom_width'>
              </a>
              </div><div class='wall-resp col-lg-4 col-md-4 col-sm-4 col-xs-6 column_padding'>
              <a href='https://hdqwalls.com/windows-xp-bliss-4k-wallpaper' title='Windows XP Bliss 4k Wallpaper' class='caption hidden-md hidden-sm hidden-xs' style='z-index:1'>Windows XP Bliss 4k</a>
              <a href='https://hdqwalls.com/windows-xp-bliss-4k-wallpaper' title='Windows XP Bliss 4k Wallpaper'>
                  <img width='602' height='339' src='https://images.hdqwalls.com/wallpapers/bthumb/windows-xp-bliss-4k-lu.jpg' title='Windows XP Bliss 4k Wallpaper' alt='Windows XP Bliss 4k Wallpaper' class='thumbnail img-responsive custom_width'>
              </a>
              </div>
        """.trimIndent()

    /** The popular feed's pagination bar — Next points at page 2. */
    private val popularPagination =
        """
        <ul class="pagination"><li class="active"><a href="https://hdqwalls.com/popular-wallpapers/page/1">1</a></li><li><a href="https://hdqwalls.com/popular-wallpapers/page/2">2</a></li><li><a href="https://hdqwalls.com/popular-wallpapers/page/3">3</a></li><li class="disabled"><span>...</span></li><li><a href="https://hdqwalls.com/popular-wallpapers/page/5014">5014</a></li><li><a href="https://hdqwalls.com/popular-wallpapers/page/2">Next &raquo;</a></li></ul>
        """.trimIndent()

    /** One category cell with site-relative href — both occur live. */
    private val categoryGrid =
        """
        <div class='wall-resp col-lg-4 col-md-4 col-sm-4 col-xs-6 column_padding'>
              <a href='https://hdqwalls.com/mclaren-f1-618-horsepower-wallpaper' title='Mclaren F1 618 Horsepower Wallpaper' class='caption hidden-md hidden-sm hidden-xs' style='z-index:1'>Mclaren F1 618 Horsepower</a>
              <a href='https://hdqwalls.com/mclaren-f1-618-horsepower-wallpaper' title='Mclaren F1 618 Horsepower Wallpaper'>
                  <img width='602' height='339' src='https://images.hdqwalls.com/wallpapers/bthumb/mclaren-f1-618-horsepower-9k.jpg' title='Mclaren F1 618 Horsepower Wallpaper' alt='Mclaren F1 618 Horsepower Wallpaper' class='thumbnail img-responsive custom_width'>
              </a>
              </div>
        """.trimIndent()

    /** Search results: direct wallpapers, pagination in the query string. */
    private val searchGrid =
        """
        <div class='wall-resp col-lg-4 col-md-4 col-sm-4 col-xs-6 column_padding'>
              <a href='https://hdqwalls.com/batgirl-x-batman-wallpaper' title='Batgirl X Batman Wallpaper' class='caption hidden-md hidden-sm hidden-xs' style='z-index:1'>Batgirl X Batman</a>
              <a href='https://hdqwalls.com/batgirl-x-batman-wallpaper' title='Batgirl X Batman Wallpaper'>
                  <img width='602' height='339' src='https://images.hdqwalls.com/wallpapers/bthumb/batgirl-x-batman-jj.jpg' title='Batgirl X Batman Wallpaper' alt='Batgirl X Batman Wallpaper' class='thumbnail img-responsive custom_width'>
              </a>
              </div>
        """.trimIndent()

    private val searchPagination =
        """
        <ul class="pagination"><li class="active"><a href="https://hdqwalls.com/search?q=batman&amp;page=1">1</a></li><li><a href="https://hdqwalls.com/search?q=batman&amp;page=2">2</a></li><li><a href="https://hdqwalls.com/search?q=batman&amp;page=3">3</a></li><li class="disabled"><span>...</span></li><li><a href="https://hdqwalls.com/search?q=batman&amp;page=265">265</a></li><li><a href="https://hdqwalls.com/search?q=batman&amp;page=2">Next &raquo;</a></li></ul>
        """.trimIndent()

    /** The LAST page of a result: Previous only, no Next — the stop signal. */
    private val lastPagePagination =
        """
        <ul class="pagination"><li><a href="https://hdqwalls.com/search?q=iphone&amp;page=1">&laquo; Previous</a></li><li><a href="https://hdqwalls.com/search?q=iphone&amp;page=1">1</a></li><li class="active"><a href="https://hdqwalls.com/search?q=iphone&amp;page=2">2</a></li></ul>
        """.trimIndent()

    /**
     * The wallpaper page's definitive parts: the blockquote (original
     * resolution, author), the tag row, the download labels and the main
     * preview image — all captured from the live markup.
     */
    private val wallpaperPage =
        """
            <html><head>
            <meta property="og:image" content="https://images.hdqwalls.com/wallpapers/the-batman-devil-in-the-night-ke.jpg">
            <script> const dynamic_resolution = 'https://images.hdqwalls.com/download/the-batman-devil-in-the-night-ke-{res}.jpg' </script>
            </head><body>
            <div class="col-lg-8 col-md-8 col-sm-12 col-xs-12 zero">
                <a href='https://images.hdqwalls.com/wallpapers/the-batman-devil-in-the-night-ke.jpg' title='Download The Batman Devil In The Night Wallpaper' target='_blank'>
                  <img src='https://images.hdqwalls.com/download/the-batman-devil-in-the-night-ke-1366x768.jpg'
                  width='1366'
                  height='768'
                  class='d_img_holder img-responsive center-block zero_padding'
                  alt='The Batman Devil In The Night Wallpaper'
                  title='The Batman Devil In The Night Wallpaper'
                  style='cursor: -webkit-zoom-in;'></a>
                <div class="col-xs-12 col-lg-12 col-md-12 col-sm-12 wallpaper_detail">
                    <ul class="float_left">
                        <li class='float_left'>
                            <span class='label label-default'>
                                <i class='fa fa-download'></i> 12493
                            </span>
                        </li>
                        <li id='tags'><i class='fa fa-tags'></i></li><a title='The Batman 2 4k Wallpapers And Images' href='https://hdqwalls.com/the-batman-2-wallpapers'>
            <li style='float:left;'>
                <span class='btn-link btn-link_a btn-xs'>the-batman-2-wallpapers,</span>
            </li>
        </a><a title='Batman 4k Wallpapers And Images' href='https://hdqwalls.com/batman-wallpapers'>
            <li style='float:left;'>
                <span class='btn-link btn-link_a btn-xs'>batman-wallpapers,</span>
            </li>
        </a><a title='Superheroes 4k Wallpapers And Images' href='https://hdqwalls.com/superheroes-wallpapers'>
            <li style='float:left;'>
                <span class='btn-link btn-link_a btn-xs'>superheroes-wallpapers,</span>
            </li>
        </a>
                    </ul>
                </div>
                <div class="col-xs-12 col-lg-12 col-md-12 col-sm-12 zero">
                    <blockquote>
                <footer>Published on July 19, 2026 | Original Resolution:<a href='https://images.hdqwalls.com/wallpapers/the-batman-devil-in-the-night-ke.jpg' class='btn-link btn-link_a' target='_blank'> 3840x2159</a> | Author :
                   <a href='https://www.instagram.com/dreemaxx/' target='_blank' class='btn-link btn-link_a'>
                        <i> dreemaxx</i>
                    </a>
                </footer>
              </blockquote>            </div>
                <div class="col-lg-12 col-md-12 col-sm-12 col-xs-12 text-center" style="margin-bottom:10px;">
                                        <a id='dynamic_resolution' href='#' onclick='return clientResizeAndDownload(this, event)' data-original-url='https://images.hdqwalls.com/wallpapers/the-batman-devil-in-the-night-ke.jpg' data-portrait-url='https://images.hdqwalls.com/download/the-batman-devil-in-the-night-ke-2160x3840.jpg' data-filename='the-batman-devil-in-the-night-ke' rel='nofollow' class='btn btn-light'><i class='fa-regular fa-circle-down'></i> Download Wallpaper <span id='dynamic_resolution_title'><i class='fa fa-spinner fa-spin'></i></span></a>
             <a href='https://images.hdqwalls.com/wallpapers/the-batman-devil-in-the-night-ke.jpg?dl=1' download rel='nofollow' class='btn btn-light' id='dl_original' style='border-radius:10px;margin-bottom:5px;'><i class='fa-regular fa-circle-down'></i> Download Original (3.46MB) </a>                            </div>
            </div>
            </body></html>
        """.trimIndent()

    // ------------------------------------------------------------- popular

    @Test
    fun `popular maps the popular grid with bthumb thumbs and disclosed originals`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://hdqwalls.com/popular-wallpapers" to ok(popularGrid + popularPagination)),
                )

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            val first = page.wallpapers.first()
            assertEquals("garena-free-fire-wallpaper", first.id)
            assertEquals("cloudimage.hdqwalls", first.providerId)
            // Real JPEG thumbnails (602x339) — safe on every Android.
            assertEquals("https://images.hdqwalls.com/wallpapers/bthumb/garena-free-fire-z4.jpg", first.thumbUrl)
            // The disclosed original: same file, one directory up.
            assertEquals("https://images.hdqwalls.com/wallpapers/garena-free-fire-z4.jpg", first.fullUrl)
            assertEquals("Garena Free Fire", first.title)
            // Grid items carry NO dimensions: the listing's width/height
            // attributes are the site's uniform 602x339 card crop, true of
            // no wallpaper — the app's info sheet renders null as "—",
            // not a number that is wrong for every item.
            assertNull(first.width)
            assertNull(first.height)
            assertEquals(ContentRating.SFW, first.contentRating)
            // The 4k in the title is a resolution label, not a tag.
            assertEquals(listOf("garena", "free", "fire"), first.tags)
            // The site's own Next link: page 2 follows.
            assertEquals(2, page.nextPage)
            assertEquals("https://hdqwalls.com/popular-wallpapers", client.requests.single())
        }

    @Test
    fun `popular walks page two through the path pagination`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://hdqwalls.com/popular-wallpapers/page/2" to ok(popularGrid + popularPagination)),
                )

            val page = provider.popular(page = 2).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals("https://hdqwalls.com/popular-wallpapers/page/2", client.requests.single())
        }

    @Test
    fun `popular routes sorting date to the latest feed`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://hdqwalls.com/latest-wallpapers" to ok(popularGrid)),
                )

            val page = provider.popular(page = 1, filters = Filters.of("sorting" to "date")).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals("https://hdqwalls.com/latest-wallpapers", client.requests.single())
        }

    @Test
    fun `popular routes the host anime and people categories to their listings`() =
        runTest {
            val animeClient =
                configureWith(
                    mapOf("https://hdqwalls.com/category/anime-wallpapers" to ok(categoryGrid)),
                )
            provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            assertEquals("https://hdqwalls.com/category/anime-wallpapers", animeClient.requests.single())

            val peopleClient =
                configureWith(
                    mapOf("https://hdqwalls.com/category/celebrities-wallpapers" to ok(categoryGrid)),
                )
            provider.popular(page = 1, filters = Filters.of("category" to "people")).getOrThrow()
            assertEquals("https://hdqwalls.com/category/celebrities-wallpapers", peopleClient.requests.single())
        }

    @Test
    fun `popular serves one batch for random sorting`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://hdqwalls.com/random-wallpapers" to ok(categoryGrid)),
                )

            val page = provider.popular(page = 1, filters = Filters.of("sorting" to "random")).getOrThrow()

            assertEquals(1, page.wallpapers.size)
            assertNull(page.nextPage)
            assertEquals("https://hdqwalls.com/random-wallpapers", client.requests.single())
        }

    // -------------------------------------------------------------- search

    @Test
    fun `search returns the site's direct results with the query pagination`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://hdqwalls.com/search?q=batman" to ok(searchGrid + searchPagination)),
                )

            val page = provider.search(query = "batman", page = 1).getOrThrow()

            assertEquals(1, page.wallpapers.size)
            val first = page.wallpapers.first()
            assertEquals("batgirl-x-batman-wallpaper", first.id)
            assertEquals("https://images.hdqwalls.com/wallpapers/bthumb/batgirl-x-batman-jj.jpg", first.thumbUrl)
            assertEquals("https://images.hdqwalls.com/wallpapers/batgirl-x-batman-jj.jpg", first.fullUrl)
            assertEquals("Batgirl X Batman", first.title)
            assertEquals(2, page.nextPage)
            assertEquals("https://hdqwalls.com/search?q=batman", client.requests.single())
        }

    @Test
    fun `search walks page two through the query string`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://hdqwalls.com/search?q=batman&page=2" to ok(searchGrid + searchPagination)),
                )

            val page = provider.search(query = "batman", page = 2).getOrThrow()

            assertEquals(1, page.wallpapers.size)
            assertEquals("https://hdqwalls.com/search?q=batman&page=2", client.requests.single())
        }

    @Test
    fun `search stops on the last page without a next link`() =
        runTest {
            configureWith(
                mapOf("https://hdqwalls.com/search?q=iphone&page=2" to ok(searchGrid + lastPagePagination)),
            )

            val page = provider.search(query = "iphone", page = 2).getOrThrow()

            assertEquals(1, page.wallpapers.size)
            assertNull(page.nextPage)
        }

    @Test
    fun `search lands blank queries on the latest feed`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://hdqwalls.com/latest-wallpapers" to ok(popularGrid)),
                )

            val page = provider.search(query = "   ", page = 1).getOrThrow()

            assertEquals(2, page.wallpapers.size)
            assertEquals("https://hdqwalls.com/latest-wallpapers", client.requests.single())
        }

    @Test
    fun `search caps deep pagination politely`() =
        runTest {
            configureWith(mapOf("https://hdqwalls.com/search?q=batman" to ok(searchGrid + searchPagination)))

            val page = provider.search(query = "batman", page = 101).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    // ------------------------------------------------------------- details

    @Test
    fun `details reads the definitive record`() =
        runTest {
            configureWith(
                mapOf("https://hdqwalls.com/the-batman-devil-in-the-night-wallpaper" to ok(wallpaperPage)),
            )

            val details = provider.details("the-batman-devil-in-the-night-wallpaper").getOrThrow()

            assertEquals("the-batman-devil-in-the-night-wallpaper", details.wallpaper.id)
            assertEquals("https://images.hdqwalls.com/wallpapers/the-batman-devil-in-the-night-ke.jpg", details.wallpaper.fullUrl)
            assertEquals("https://images.hdqwalls.com/wallpapers/bthumb/the-batman-devil-in-the-night-ke.jpg", details.wallpaper.thumbUrl)
            assertEquals("The Batman Devil In The Night", details.wallpaper.title)
            // TRUE dimensions, the only place the site publishes them.
            assertEquals(3840, details.wallpaper.width)
            assertEquals(2159, details.wallpaper.height)
            assertEquals("3840x2159", details.resolution)
            assertEquals("dreemaxx", details.author)
            // 3.46MB as the site labels it.
            assertEquals((3.46 * 1024 * 1024).toLong(), details.fileSizeBytes)
            assertEquals(listOf("the-batman-2", "batman", "superheroes"), details.wallpaper.tags)
            assertEquals("https://hdqwalls.com/the-batman-devil-in-the-night-wallpaper", details.sourceUrl)
        }

    @Test
    fun `details fails honestly on a missing wallpaper`() =
        runTest {
            configureWith(
                mapOf("https://hdqwalls.com/gone-wallpaper" to ProviderHttpResponse(404, emptyMap(), ByteArray(0))),
            )

            val result = provider.details("gone-wallpaper")

            assertTrue(result.isFailure)
        }

    @Test
    fun `details fails honestly on an unrecognized page`() =
        runTest {
            configureWith(
                mapOf("https://hdqwalls.com/redesigned-wallpaper" to ok("<html><body>nothing here</body></html>")),
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
            assertEquals(setOf("superheroes"), byId.getValue("superheroes").filters.valuesFor("query"))
            assertEquals(setOf("bikes"), byId.getValue("bikes").filters.valuesFor("query"))
            assertEquals(setOf("sports"), byId.getValue("sports").filters.valuesFor("query"))
        }

    // -------------------------------------------------------- suggestions

    @Test
    fun `suggestTags answers from seen tags only`() =
        runTest {
            // Nothing seen yet: no suggestions, and no request fired.
            val client =
                configureWith(mapOf("https://hdqwalls.com/popular-wallpapers" to ok(popularGrid)))
            assertTrue(provider.suggestTags("gare").getOrThrow().isEmpty())

            provider.popular(page = 1).getOrThrow()
            // After a feed: the words of its titles are suggestable.
            val suggestions = provider.suggestTags("gare").getOrThrow()
            assertTrue(suggestions.contains("garena"))
            assertTrue(provider.suggestTags("fire").getOrThrow().contains("fire"))
            assertTrue(provider.suggestTags("nothing").getOrThrow().isEmpty())
            assertTrue(client.requests.none { it.contains("ajax") })
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
                    mapOf("https://hdqwalls.com/random-wallpapers" to ok(categoryGrid)),
                )

            val wallpapers = provider.random().getOrThrow()

            assertEquals(1, wallpapers.size)
            assertEquals("mclaren-f1-618-horsepower-wallpaper", wallpapers.first().id)
            assertEquals("https://hdqwalls.com/random-wallpapers", client.requests.single())
        }

    // ------------------------------------------------------- parser edges

    @Test
    fun `grid parsing tolerates attribute order and quote styles`() {
        val html =
            """
            <div class="wall-resp col-lg-4 column_padding">
            <a title='Order Test Wallpaper' class='caption' href="/order-test-wallpaper">Order Test</a>
            <a href="/order-test-wallpaper" title='Order Test Wallpaper'>
            <img src="https://images.hdqwalls.com/wallpapers/bthumb/order-test-xx.jpg" height='339' width='602' alt='Order Test Wallpaper' class='thumbnail'>
            </a>
            </div>
            """.trimIndent()

        val items = HdqWallsParser.parseGrid(html)

        assertEquals(1, items.size)
        assertEquals("order-test-wallpaper", items.first().id)
        // The deliberately reordered attrs (height before width, mixed
        // quotes) still parse; the card-crop dims themselves are not
        // published as dimensions — see GridItem.
        assertEquals("https://images.hdqwalls.com/wallpapers/order-test-xx.jpg", items.first().originalUrl)
        assertEquals("Order Test", items.first().title)
    }

    @Test
    fun `unrelated markup yields empty grids and no next page`() {
        val html = "<html><body><div class='container'><p>no wallpapers here</p></div></body></html>"

        assertTrue(HdqWallsParser.parseGrid(html).isEmpty())
        assertNull(HdqWallsParser.parseNextPage(html))
    }

    @Test
    fun `cells without a bthumb image drop instead of corrupting the batch`() {
        val html =
            """
            <div class='wall-resp col-lg-4 column_padding'>
            <a href='https://hdqwalls.com/broken-wallpaper' title='Broken Wallpaper'><img src='https://example.com/other.jpg'></a>
            </div><div class='wall-resp col-lg-4 column_padding'>
            <a href='https://hdqwalls.com/healthy-wallpaper' title='Healthy Wallpaper'>
            <img src='https://images.hdqwalls.com/wallpapers/bthumb/healthy-aa.jpg' width='602' height='339' title='Healthy Wallpaper' alt='Healthy Wallpaper'>
            </a>
            </div>
            """.trimIndent()

        val items = HdqWallsParser.parseGrid(html)

        assertEquals(1, items.size)
        assertEquals("healthy-wallpaper", items.first().id)
    }

    @Test
    fun `titles lose their wallpaper suffix`() {
        assertEquals("The Batman Devil In The Night", HdqWallsParser.cleanTitle("The Batman Devil In The Night Wallpaper"))
        assertEquals("Lowercase Suffix", HdqWallsParser.cleanTitle("Lowercase Suffix wallpaper"))
        assertEquals("Already Clean", HdqWallsParser.cleanTitle("Already Clean"))
    }
}
