package com.cloudimage.wallpapercave

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
 * WallpaperCave provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures cut from the live site's markup: the two-step
 * search, the latest-feed pagination seam, thumbnail-to-original mapping,
 * dimension fidelity and the polite fallbacks all stay covered without a
 * network.
 */
class WallpaperCaveWallpaperProviderTest {
    private val provider = WallpaperCaveWallpaperProvider()

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

    // Fixtures: shapes captured from wallpapercave.com, trimmed to the parts
    // the parsers key on.

    private val latestGrid =
        """
        <div id="grid-container">
        <a href="/w/uwp5093523" title="planet, space, universe" class="wpimg" target="_blank"><img src="/uwpr/uwp5093523.jpeg" width="200" height="356" /></a>
        <a href="/w/uwp5093522" title="lake, mountain, forest" class="wpimg" target="_blank"><img src="/uwpr/uwp5093522.jpeg" width="200" height="356" /></a>
        <a href="/w/uwp5093507" title="Sanji Vinsmoke" class="wpimg" target="_blank"><img src="/uwpr/uwp5093507.png" width="200" height="113" /></a>
        </div>
        """.trimIndent()

    /** The load-more payload: JSON wrapping grid anchors, quotes JSON-escaped. */
    private val moreLatest =
        """
        [{"next_page":1,"imgs":"<a href=\"/w/uwp5093500\" title=\"Boa Hancock\" class=\"wpimg\"><img src=\"/uwpr/uwp5093500.jpeg\" width=\"200\" height=\"113\" /><\/a><a href=\"/w/uwp5093507\" title=\"Sanji Vinsmoke\" class=\"wpimg\"><img src=\"/uwpr/uwp5093507.png\" width=\"200\" height=\"113\" /><\/a>"}]
        """.trimIndent()

    /**
     * Four albums — the third in the home page's `falbumthumbnail` variant,
     * the fourth with its title attribute BEFORE the class (both orders
     * occur live).
     */
    private val searchAlbums =
        """
        <div id="searchresults">
        <a href="/1920x1080-manga-wallpapers" class="albumthumbnail" title="74 wallpapers in 1920x1080 Manga Wallpapers"><div class="aall" photos="74"><span class="overlay">74</span></div></a>
        <a href="/91-days-wallpapers" class="albumthumbnail" title="55 wallpapers in 91 Days Wallpapers"><div class="aall" photos="55"><span class="overlay">55</span></div></a>
        <a href="/autumn-season-wallpapers" class="falbumthumbnail even" title="99 wallpapers in Autumn Season"><div class="faall" photos="99"></div></a>
        <a href="/dandys-world-wallpapers" title="12 wallpapers in Dandy&#039;s World" class="albumthumbnail even" title="12 wallpapers in Dandy&#039;s World"><div class="aall" photos="12"></div></a>
        </div>
        """.trimIndent()

    /**
     * The site's category pages — the same album anchors a search answers
     * with, slugs captured from the live /categories/anime-manga listing.
     */
    private val animeCategoryAlbums =
        """
        <div class="searchresults">
        <a href="/a-silent-voice-hd-wallpapers" class="albumthumbnail" title="41 wallpapers in A Silent Voice HD Wallpapers"><div class="aall" photos="41"><span class="overlay">41</span></div></a>
        <a href="/akame-ga-kill-wallpapers" class="albumthumbnail" title="55 wallpapers in Akame ga Kill Wallpapers"><div class="aall" photos="55"></div></a>
        <a href="/anohana-the-flower-we-saw-that-day-wallpapers" class="albumthumbnail" title="30 wallpapers in Anohana"><div class="aall" photos="30"></div></a>
        <a href="/alya-sometimes-hides-her-feelings-in-russian-wallpapers" class="albumthumbnail" title="12 wallpapers in Alya"><div class="aall" photos="12"></div></a>
        </div>
        """.trimIndent()

    /** One topic-album item per `id|file|width|height|alt` spec, real markup shape. */
    private fun topicPage(vararg specs: String): String =
        specs
            .joinToString("") { spec ->
                val (id, file, width, height, alt) = spec.split('|')
                """
                <div class="wallpaper" id="dom$id">
                <div class="wpbuttons"><a href="https://www.facebook.com/sharer/" class="fblink"><img src="/img/fb.png" alt="Share on Facebook"></a></div>
                <a href="/w/$id" class="wpinkw"><picture>
                <source media="(max-width: 500px)" srcset="/mwp/$file">
                <source media="(min-width: 700px)" srcset="/dwp1x/$file, /dwp2x/$file 2x">
                <img src="/wp/$file" width="$width" height="$height" alt="$alt" class="wimg" />
                </picture></a>
                </div>
                """.trimIndent()
            }.let { "<div id=\"albumwp\">$it</div>" }

    private val wallpaperPage =
        """
        <html><body>
        <img data-url="wp14981887" src="https://wallpapercave.com/wp/wp14981887.webp" alt="Wally West Wallpaper (Comic)" title="Wally West Wallpaper (Comic)" data-slug="wally-west-flash-wallpapers" class="wpimg" width="700" height="1076" />
        </body></html>
        """.trimIndent()

    private val userWallpaperPage =
        """
        <html><body>
        <img data-url="uwp5093523" src="https://wallpapercave.com/uwp/uwp5093523.jpeg" data-slug="uwp" class="wpimg" />
        </body></html>
        """.trimIndent()

    // ------------------------------------------------------------- popular

    @Test
    fun `popular maps the latest grid with derived originals and tags`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapercave.com/latest-uploads" to ok(latestGrid)),
                )

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(3, page.wallpapers.size)
            val first = page.wallpapers.first()
            assertEquals("uwp5093523", first.id)
            assertEquals("cloudimage.wallpapercave", first.providerId)
            // The /uwpr/ thumb is served as AVIF (undecodable below Android
            // 12) — both fields carry the disclosed original instead.
            assertEquals("https://wallpapercave.com/uwp/uwp5093523.jpeg", first.thumbUrl)
            assertEquals("https://wallpapercave.com/uwp/uwp5093523.jpeg", first.fullUrl)
            assertEquals(200, first.width)
            assertEquals(356, first.height)
            assertEquals(listOf("planet", "space", "universe"), first.tags)
            assertEquals(ContentRating.SFW, first.contentRating)
            assertEquals(2, page.nextPage)
            assertEquals("https://wallpapercave.com/latest-uploads", client.requests.single())
        }

    @Test
    fun `popular page two serves the load-more batch without the seam duplicates`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapercave.com/latest-uploads" to ok(latestGrid),
                    "https://wallpapercave.com/morelatest" to ok(moreLatest),
                ),
            )

            provider.popular(page = 1).getOrThrow()
            val page = provider.popular(page = 2).getOrThrow()

            // uwp5093507 sat at page 1's tail AND in the load-more batch —
            // the recent-ids set absorbs the seam.
            assertEquals(listOf("uwp5093500"), page.wallpapers.map { it.id })
            assertNull(page.nextPage)
        }

    @Test
    fun `popular page two answers everything when page one was never loaded`() =
        runTest {
            configureWith(
                mapOf("https://wallpapercave.com/morelatest" to ok(moreLatest)),
            )

            val page = provider.popular(page = 2).getOrThrow()

            // A fresh instance has no dedupe history — nothing is hidden.
            assertEquals(listOf("uwp5093500", "uwp5093507"), page.wallpapers.map { it.id })
        }

    @Test
    fun `popular past the second page answers empty`() =
        runTest {
            val page = provider.popular(page = 3).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun `an anime category routes to the curated anime topic`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapercave.com/anime-wallpapers" to
                            ok(
                                topicPage(
                                    "wp14981887|wp14981887.webp|736|1131|Wally West Wallpaper (Comic)",
                                    // Short alphanumeric ids occur on curated albums too.
                                    "qq5qUZy|qq5qUZy.jpg|1920|1200|Anime Wallpaper 12",
                                ),
                            ),
                    ),
                )

            val page = provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()

            assertEquals("https://wallpapercave.com/anime-wallpapers", client.requests.single())
            assertEquals(2, page.wallpapers.size)
            val wallpaper = page.wallpapers.first()
            assertEquals("wp14981887", wallpaper.id)
            // Topic pages publish the file's TRUE dimensions.
            assertEquals(736, wallpaper.width)
            assertEquals(1131, wallpaper.height)
            // The picture's img loads the original directly.
            assertEquals("https://wallpapercave.com/wp/wp14981887.webp", wallpaper.fullUrl)
            assertEquals("https://wallpapercave.com/wp/qq5qUZy.jpg", page.wallpapers[1].fullUrl)
            // The category continues into its album stream.
            assertEquals(2, page.nextPage)
        }

    // ------------------------------------------------------ category stream

    @Test
    fun `a category page two serves the first album batch of the category stream`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapercave.com/anime-wallpapers" to
                            ok(topicPage("wpA1|wpA1.webp|1000|1600|Curated Anime")),
                        "https://wallpapercave.com/categories/anime-manga" to ok(animeCategoryAlbums),
                        "https://wallpapercave.com/a-silent-voice-hd-wallpapers" to
                            ok(topicPage("wpS1|wpS1.webp|1920|1080|A Silent Voice")),
                        "https://wallpapercave.com/akame-ga-kill-wallpapers" to
                            ok(topicPage("wpK1|wpK1.webp|1920|1080|Akame ga Kill")),
                        "https://wallpapercave.com/anohana-the-flower-we-saw-that-day-wallpapers" to
                            ok(topicPage("wpN1|wpN1.webp|1920|1080|Anohana")),
                    ),
                )

            provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            val page = provider.popular(page = 2, filters = Filters.of("category" to "anime")).getOrThrow()

            // Three albums merged, none overlapping the curated topic.
            assertEquals(listOf("wpS1", "wpK1", "wpN1"), page.wallpapers.map { it.id })
            // The fourth album remains — the stream offers a third page.
            assertEquals(3, page.nextPage)
            // The category page was fetched exactly once.
            assertEquals(
                1,
                client.requests.count { it.startsWith("https://wallpapercave.com/categories/") },
            )
        }

    @Test
    fun `a category skips a batch the session already served entirely`() =
        runTest {
            configureWith(
                mapOf(
                    // The curated topic — also the whole content of the first
                    // three category albums, so page two's first batch is
                    // nothing but duplicates.
                    "https://wallpapercave.com/anime-wallpapers" to
                        ok(
                            topicPage(
                                "wpA1|wpA1.webp|1000|1600|Curated Anime",
                                "wpA2|wpA2.webp|1000|1600|Curated Anime 2",
                            ),
                        ),
                    "https://wallpapercave.com/categories/anime-manga" to ok(animeCategoryAlbums),
                    "https://wallpapercave.com/a-silent-voice-hd-wallpapers" to
                        ok(
                            topicPage(
                                "wpA1|wpA1.webp|1000|1600|Curated Anime",
                                "wpA2|wpA2.webp|1000|1600|Curated Anime 2",
                            ),
                        ),
                    "https://wallpapercave.com/akame-ga-kill-wallpapers" to
                        ok(
                            topicPage(
                                "wpA1|wpA1.webp|1000|1600|Curated Anime",
                                "wpA2|wpA2.webp|1000|1600|Curated Anime 2",
                            ),
                        ),
                    "https://wallpapercave.com/anohana-the-flower-we-saw-that-day-wallpapers" to
                        ok(
                            topicPage(
                                "wpA1|wpA1.webp|1000|1600|Curated Anime",
                                "wpA2|wpA2.webp|1000|1600|Curated Anime 2",
                            ),
                        ),
                    "https://wallpapercave.com/alya-sometimes-hides-her-feelings-in-russian-wallpapers" to
                        ok(topicPage("wpF1|wpF1.webp|800|1200|Alya")),
                ),
            )

            provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            val page = provider.popular(page = 2, filters = Filters.of("category" to "anime")).getOrThrow()

            // The all-duplicate batch was consumed and skipped; the fourth
            // album answers instead — and the stream is exhausted after it.
            assertEquals(listOf("wpF1"), page.wallpapers.map { it.id })
            assertNull(page.nextPage)
        }

    @Test
    fun `a category exhausts its album list with no next page`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapercave.com/anime-wallpapers" to
                            ok(topicPage("wpA1|wpA1.webp|1000|1600|Curated Anime")),
                        "https://wallpapercave.com/categories/anime-manga" to ok(animeCategoryAlbums),
                        "https://wallpapercave.com/a-silent-voice-hd-wallpapers" to
                            ok(topicPage("wpS1|wpS1.webp|1920|1080|A Silent Voice")),
                        "https://wallpapercave.com/akame-ga-kill-wallpapers" to
                            ok(topicPage("wpK1|wpK1.webp|1920|1080|Akame ga Kill")),
                        "https://wallpapercave.com/anohana-the-flower-we-saw-that-day-wallpapers" to
                            ok(topicPage("wpN1|wpN1.webp|1920|1080|Anohana")),
                        "https://wallpapercave.com/alya-sometimes-hides-her-feelings-in-russian-wallpapers" to
                            ok(topicPage("wpF1|wpF1.webp|800|1200|Alya")),
                    ),
                )

            val anime = Filters.of("category" to "anime")
            provider.popular(page = 1, filters = anime).getOrThrow()
            provider.popular(page = 2, filters = anime).getOrThrow()
            val last = provider.popular(page = 3, filters = anime).getOrThrow()

            assertEquals(listOf("wpF1"), last.wallpapers.map { it.id })
            assertNull(last.nextPage)
            // The category page stays cached across the whole session.
            assertEquals(
                1,
                client.requests.count { it.startsWith("https://wallpapercave.com/categories/") },
            )
        }

    @Test
    fun `a failing category album is skipped and the rest still answer`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapercave.com/anime-wallpapers" to
                        ok(topicPage("wpA1|wpA1.webp|1000|1600|Curated Anime")),
                    "https://wallpapercave.com/categories/anime-manga" to ok(animeCategoryAlbums),
                    "https://wallpapercave.com/a-silent-voice-hd-wallpapers" to
                        ok(topicPage("wpS1|wpS1.webp|1920|1080|A Silent Voice")),
                    // akame-ga-kill is unrouted and answers 500.
                    "https://wallpapercave.com/anohana-the-flower-we-saw-that-day-wallpapers" to
                        ok(topicPage("wpN1|wpN1.webp|1920|1080|Anohana")),
                ),
            )

            provider.popular(page = 1, filters = Filters.of("category" to "anime")).getOrThrow()
            val page = provider.popular(page = 2, filters = Filters.of("category" to "anime")).getOrThrow()

            assertEquals(listOf("wpS1", "wpN1"), page.wallpapers.map { it.id })
        }

    @Test
    fun `a category restart forgets the earlier session`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapercave.com/anime-wallpapers" to
                        ok(
                            topicPage(
                                "wpA1|wpA1.webp|1000|1600|Curated Anime",
                                "wpA2|wpA2.webp|1000|1600|Curated Anime 2",
                            ),
                        ),
                    "https://wallpapercave.com/categories/anime-manga" to ok(animeCategoryAlbums),
                    "https://wallpapercave.com/a-silent-voice-hd-wallpapers" to
                        ok(topicPage("wpS1|wpS1.webp|1920|1080|A Silent Voice")),
                ),
            )

            val anime = Filters.of("category" to "anime")
            provider.popular(page = 1, filters = anime).getOrThrow()
            provider.popular(page = 2, filters = anime).getOrThrow()

            // The feed restarted (source re-selected, tab revisited): page 1
            // re-serves the curated topic in full, not the session's leavings.
            val restart = provider.popular(page = 1, filters = anime).getOrThrow()

            assertEquals(listOf("wpA1", "wpA2"), restart.wallpapers.map { it.id })
            assertEquals(2, restart.nextPage)
        }

    @Test
    fun `a people category walks the people stream`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapercave.com/people-wallpapers" to
                            ok(topicPage("wpP1|wpP1.webp|1000|1600|People")),
                        "https://wallpapercave.com/categories/people" to
                            ok(
                                """
                                <a href="/alexander-hamilton-wallpapers" class="albumthumbnail" title="28 wallpapers in Alexander Hamilton Wallpapers"><div class="aall" photos="28"></div></a>
                                """.trimIndent(),
                            ),
                        "https://wallpapercave.com/alexander-hamilton-wallpapers" to
                            ok(topicPage("wpH1|wpH1.webp|1920|1080|Hamilton")),
                    ),
                )

            val people = Filters.of("category" to "people")
            val first = provider.popular(page = 1, filters = people).getOrThrow()
            val second = provider.popular(page = 2, filters = people).getOrThrow()

            assertEquals(listOf("wpP1"), first.wallpapers.map { it.id })
            assertEquals(listOf("wpH1"), second.wallpapers.map { it.id })
            assertEquals(
                listOf(
                    "https://wallpapercave.com/people-wallpapers",
                    "https://wallpapercave.com/categories/people",
                    "https://wallpapercave.com/alexander-hamilton-wallpapers",
                ),
                client.requests,
            )
        }

    @Test
    fun `a query preset walks its category feed - curated topic then stream`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapercave.com/girls-wallpapers" to
                            ok(topicPage("wpG1|wpG1.webp|1000|1600|Curated Girls")),
                        "https://wallpapercave.com/search?q=girls" to
                            ok(
                                """
                                <a href="/lbx-girls-wallpapers" class="albumthumbnail" title="70 wallpapers in LBX Girls Wallpapers"><div class="aall" photos="70"></div></a>
                                <a href="/iranian-girls-wallpapers" class="albumthumbnail" title="12 wallpapers in Iranian Girls Wallpapers"><div class="aall" photos="12"></div></a>
                                """.trimIndent(),
                            ),
                        "https://wallpapercave.com/lbx-girls-wallpapers" to
                            ok(topicPage("wpL1|wpL1.webp|1920|1080|LBX Girls")),
                        "https://wallpapercave.com/iranian-girls-wallpapers" to
                            ok(topicPage("wpI1|wpI1.webp|1920|1080|Iranian Girls")),
                    ),
                )

            val first = provider.search("girls", page = 1).getOrThrow()
            val second = provider.search("girls", page = 2).getOrThrow()

            assertEquals("https://wallpapercave.com/girls-wallpapers", client.requests.first())
            assertEquals(listOf("wpG1"), first.wallpapers.map { it.id })
            assertEquals(2, first.nextPage)
            // The girls shelf has no category page of its own - its stream is
            // the search listing, the same album anchors.
            assertEquals("https://wallpapercave.com/search?q=girls", client.requests[1])
            assertEquals(listOf("wpL1", "wpI1"), second.wallpapers.map { it.id })
            assertNull(second.nextPage)
        }

    @Test
    fun `the cars tab walks the vehicles category stream`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapercave.com/cars-wallpapers" to
                            ok(topicPage("wpC1|wpC1.webp|1920|1080|Curated Cars")),
                        "https://wallpapercave.com/categories/vehicles/cars" to
                            ok(
                                """
                                <a href="/acura-wallpapers" class="albumthumbnail" title="30 wallpapers in Acura Wallpapers"><div class="aall" photos="30"></div></a>
                                <a href="/alfa-romeo-wallpapers" class="albumthumbnail" title="55 wallpapers in Alfa Romeo Wallpapers"><div class="aall" photos="55"></div></a>
                                """.trimIndent(),
                            ),
                        "https://wallpapercave.com/acura-wallpapers" to ok(topicPage("wpAC|wpAC.webp|1920|1080|Acura")),
                        "https://wallpapercave.com/alfa-romeo-wallpapers" to ok(topicPage("wpAR|wpAR.webp|1920|1080|Alfa Romeo")),
                    ),
                )

            val first = provider.search("cars", page = 1).getOrThrow()
            val second = provider.search("cars", page = 2).getOrThrow()

            assertEquals(listOf("wpC1"), first.wallpapers.map { it.id })
            assertEquals(2, first.nextPage)
            assertEquals(listOf("wpAC", "wpAR"), second.wallpapers.map { it.id })
            assertNull(second.nextPage)
            assertEquals("https://wallpapercave.com/categories/vehicles/cars", client.requests[1])
        }

    @Test
    fun `category terms match exactly, case and whitespace insensitive`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapercave.com/cars-wallpapers" to
                            ok(topicPage("wpC1|wpC1.webp|1920|1080|Curated Cars")),
                        "https://wallpapercave.com/girls-wallpapers" to
                            ok(topicPage("wpG1|wpG1.webp|1000|1600|Curated Girls")),
                        "https://wallpapercave.com/search" to ok(searchAlbums),
                        "https://wallpapercave.com/1920x1080-manga-wallpapers" to
                            ok(topicPage("wp1|wp1.webp|1920|1080|Manga Cover")),
                    ),
                )

            // The exact term in any case, even padded, walks its feed.
            provider.search("CARS", page = 1).getOrThrow()
            assertEquals("https://wallpapercave.com/cars-wallpapers", client.requests.first())
            provider.search("  Girls ", page = 1).getOrThrow()
            assertEquals("https://wallpapercave.com/girls-wallpapers", client.requests[1])

            // A longer phrase or a different word is a query like any other.
            provider.search("girls aesthetic", page = 1).getOrThrow()
            assertTrue("https://wallpapercave.com/search?q=girls+aesthetic" in client.requests)
            provider.search("car", page = 1).getOrThrow()
            assertTrue("https://wallpapercave.com/search?q=car" in client.requests)
        }

    @Test
    fun `interleaved tabs keep their sessions separate`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapercave.com/cars-wallpapers" to
                            ok(topicPage("wpC1|wpC1.webp|1920|1080|Curated Cars")),
                        "https://wallpapercave.com/girls-wallpapers" to
                            ok(topicPage("wpG1|wpG1.webp|1000|1600|Curated Girls")),
                        "https://wallpapercave.com/categories/vehicles/cars" to
                            ok(
                                """
                                <a href="/acura-wallpapers" class="albumthumbnail" title="30 wallpapers in Acura Wallpapers"><div class="aall" photos="30"></div></a>
                                """.trimIndent(),
                            ),
                        "https://wallpapercave.com/search?q=girls" to
                            ok(
                                """
                                <a href="/lbx-girls-wallpapers" class="albumthumbnail" title="70 wallpapers in LBX Girls Wallpapers"><div class="aall" photos="70"></div></a>
                                """.trimIndent(),
                            ),
                        "https://wallpapercave.com/acura-wallpapers" to ok(topicPage("wpAC|wpAC.webp|1920|1080|Acura")),
                        "https://wallpapercave.com/lbx-girls-wallpapers" to ok(topicPage("wpL1|wpL1.webp|1920|1080|LBX Girls")),
                    ),
                )

            // Users swipe between tabs: each feed's cursor and seen-ids stay its own.
            provider.search("cars", page = 1).getOrThrow()
            provider.search("girls", page = 1).getOrThrow()
            val carsTwo = provider.search("cars", page = 2).getOrThrow()
            val girlsTwo = provider.search("girls", page = 2).getOrThrow()

            assertEquals(listOf("wpAC"), carsTwo.wallpapers.map { it.id })
            assertEquals(listOf("wpL1"), girlsTwo.wallpapers.map { it.id })
            // Each stream page was fetched exactly once for its own tab.
            assertEquals(1, client.requests.count { it == "https://wallpapercave.com/categories/vehicles/cars" })
            assertEquals(1, client.requests.count { it == "https://wallpapercave.com/search?q=girls" })
        }

    @Test
    fun `a category stops at its page cap without another request`() =
        runTest {
            val client = configureWith(emptyMap())

            // MAX_CATEGORY_PAGES is 15: the sixteenth page is refused before
            // any request fires.
            val page =
                provider
                    .popular(page = 16, filters = Filters.of("category" to "anime"))
                    .getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue(client.requests.isEmpty())
        }

    @Test
    fun `http error degrades popular to a failure`() =
        runTest {
            configureWith(emptyMap())

            assertTrue(provider.popular(page = 1).isFailure)
        }

    @Test
    fun `unconfigured provider fails instead of crashing`() =
        runTest {
            assertTrue(provider.popular(page = 1).isFailure)
        }

    // -------------------------------------------------------------- search

    @Test
    fun `search merges the first album batch and keeps paging`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapercave.com/search" to ok(searchAlbums),
                        "https://wallpapercave.com/1920x1080-manga-wallpapers" to
                            ok(topicPage("wp1|wp1.webp|1920|1080|Manga Cover")),
                        "https://wallpapercave.com/91-days-wallpapers" to
                            ok(topicPage("wp2|wp2.webp|3840|2160|91 Days")),
                        "https://wallpapercave.com/autumn-season-wallpapers" to
                            ok(topicPage("wp3|wp3.webp|1600|900|Autumn")),
                    ),
                )

            val page = provider.search("manga", page = 1).getOrThrow()

            assertEquals(listOf("wp1", "wp2", "wp3"), page.wallpapers.map { it.id })
            assertEquals(2, page.nextPage)
            // One search page plus three topic fetches.
            assertEquals(4, client.requests.size)
            assertEquals("https://wallpapercave.com/search?q=manga", client.requests.first())
        }

    @Test
    fun `search page two takes the next album batch without refetching the query`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallpapercave.com/search" to ok(searchAlbums),
                        "https://wallpapercave.com/dandys-world-wallpapers" to
                            ok(topicPage("wp9|wp9.webp|1000|1000|Dandy")),
                    ),
                )

            // Prime the album cache with page 1.
            provider.search("manga", page = 1).getOrThrow()

            val page = provider.search("manga", page = 2).getOrThrow()

            assertEquals(listOf("wp9"), page.wallpapers.map { it.id })
            // The /search page was fetched exactly once across both pages.
            assertEquals(1, client.requests.count { it.startsWith("https://wallpapercave.com/search") })
            assertNull(page.nextPage)
        }

    @Test
    fun `a failing album is skipped and the rest still answer`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapercave.com/search" to ok(searchAlbums),
                    "https://wallpapercave.com/1920x1080-manga-wallpapers" to
                        ok(topicPage("wp1|wp1.webp|1920|1080|Manga Cover")),
                    // 91-days is unrouted and answers 500; autumn answers fine.
                    "https://wallpapercave.com/autumn-season-wallpapers" to
                        ok(topicPage("wp3|wp3.webp|1600|900|Autumn")),
                ),
            )

            val page = provider.search("manga", page = 1).getOrThrow()

            assertEquals(listOf("wp1", "wp3"), page.wallpapers.map { it.id })
        }

    @Test
    fun `search beyond the album list answers empty`() =
        runTest {
            configureWith(
                mapOf(
                    "https://wallpapercave.com/search" to ok(searchAlbums),
                    "https://wallpapercave.com/dandys-world-wallpapers" to
                        ok(topicPage("wp9|wp9.webp|1000|1000|Dandy")),
                ),
            )

            // Four albums, three per page: page 2 holds exactly the fourth.
            assertEquals(
                listOf("wp9"),
                provider
                    .search("manga", page = 2)
                    .getOrThrow()
                    .wallpapers
                    .map { it.id },
            )

            val beyond = provider.search("manga", page = 3).getOrThrow()

            assertTrue(beyond.wallpapers.isEmpty())
            assertNull(beyond.nextPage)
        }

    @Test
    fun `blank search degrades to the latest feed`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapercave.com/latest-uploads" to ok(latestGrid)),
                )

            val page = provider.search("   ", page = 1).getOrThrow()

            assertEquals("https://wallpapercave.com/latest-uploads", client.requests.single())
            assertEquals(3, page.wallpapers.size)
        }

    @Test
    fun `a search with no albums answers an empty page`() =
        runTest {
            configureWith(
                mapOf("https://wallpapercave.com/search" to ok("<p>No results</p>")),
            )

            val page = provider.search("zzz-not-a-thing", page = 1).getOrThrow()

            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    // ------------------------------------------------------------ details

    @Test
    fun `details resolves the definitive original from the wallpaper page`() =
        runTest {
            configureWith(
                mapOf("https://wallpapercave.com/w/wp14981887" to ok(wallpaperPage)),
            )

            val details = provider.details("wp14981887").getOrThrow()

            assertEquals("https://wallpapercave.com/wp/wp14981887.webp", details.wallpaper.fullUrl)
            assertEquals("Wally West Wallpaper (Comic)", details.wallpaper.title)
            assertEquals("https://wallpapercave.com/w/wp14981887", details.sourceUrl)
        }

    @Test
    fun `details of a user upload carries no dimensions rather than inventing them`() =
        runTest {
            configureWith(
                mapOf("https://wallpapercave.com/w/uwp5093523" to ok(userWallpaperPage)),
            )

            val details = provider.details("uwp5093523").getOrThrow()

            assertEquals("https://wallpapercave.com/uwp/uwp5093523.jpeg", details.wallpaper.fullUrl)
            assertNull(details.wallpaper.width)
            assertNull(details.wallpaper.height)
        }

    @Test
    fun `details falls back to the id-derived url when the page no longer parses`() =
        runTest {
            configureWith(
                mapOf("https://wallpapercave.com/w/wp16556709" to ok("<html>redesigned</html>")),
            )

            val details = provider.details("wp16556709").getOrThrow()

            assertEquals("https://wallpapercave.com/wp/wp16556709.webp", details.wallpaper.fullUrl)
            assertEquals("https://wallpapercave.com/w/wp16556709", details.sourceUrl)
        }

    @Test
    fun `details degrades to the derived url when the site answers non-2xx`() =
        runTest {
            configureWith(emptyMap())

            // The wallpaper page is enrichment, not a gate: a transient 5xx
            // must not kill the viewer when the id already discloses a
            // working image URL.
            val details = provider.details("wp14981887").getOrThrow()

            assertEquals("https://wallpapercave.com/wp/wp14981887.webp", details.wallpaper.fullUrl)
            assertEquals("https://wallpapercave.com/w/wp14981887", details.sourceUrl)
        }

    // ---------------------------------------------------------- suggestions

    @Test
    fun `suggestions answer only from tags already seen, never a request`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallpapercave.com/latest-uploads" to ok(latestGrid)),
                )

            // A fresh instance suggests nothing and fires nothing.
            assertTrue(provider.suggestTags("plan").getOrThrow().isEmpty())
            assertTrue(client.requests.isEmpty())

            provider.popular(page = 1).getOrThrow()

            assertEquals(listOf("planet"), provider.suggestTags("plan").getOrThrow())
            assertEquals(listOf("space"), provider.suggestTags("spa").getOrThrow())
            assertTrue(client.requests.none { it.contains("search") })
        }

    // --------------------------------------------------------- home sections

    @Test
    fun `sections stay offline and speak the host vocabulary`() =
        runTest {
            val client = configureWith(emptyMap())

            val sections = provider.sections()

            assertEquals(
                listOf(
                    "latest",
                    "anime",
                    "girls",
                    "cars",
                    "people",
                    "games",
                    "movies",
                    "nature",
                    "space",
                    "animals",
                    "bikes",
                    "sports",
                    "abstract",
                ),
                sections.map { it.id },
            )
            assertEquals("Latest Uploads", sections.first().title)
            assertEquals("Girls", sections.single { it.id == "girls" }.title)
            assertEquals("Cars", sections.single { it.id == "cars" }.title)
            // Anime and people ride the host's own category values...
            assertEquals(setOf("anime"), sections.single { it.id == "anime" }.filters.valuesFor("category"))
            assertEquals(setOf("people"), sections.single { it.id == "people" }.filters.valuesFor("category"))
            // ...every other shelf is a tag-style query preset the host routes through search().
            listOf("girls", "cars", "games", "movies", "nature", "space", "animals", "bikes", "sports", "abstract").forEach { id ->
                assertEquals(setOf(id), sections.single { it.id == id }.filters.valuesFor("query"))
            }
            assertTrue(client.requests.isEmpty())
        }

    // --------------------------------------------------------------- parser

    @Test
    fun `thumbnail families map to their original directories`() {
        assertEquals("/uwp/uwp5093523.jpeg", WallpaperCaveParser.toOriginalPath("/uwpr/uwp5093523.jpeg"))
        assertEquals("/uwp/uwp5093523.jpeg", WallpaperCaveParser.toOriginalPath("/fuwp-255/uwp5093523.jpeg"))
        assertEquals("/wp/wp15547868.jpg", WallpaperCaveParser.toOriginalPath("/fwp-255/wp15547868.jpg"))
        assertEquals("/wp/wp14981887.webp", WallpaperCaveParser.toOriginalPath("/mwp/wp14981887.webp"))
        assertEquals("/wp/wp14981887.webp", WallpaperCaveParser.toOriginalPath("/dwp2x/wp14981887.webp"))
        assertEquals("/wp/wp1.webp", WallpaperCaveParser.toOriginalPath("/wp/wp1.webp"))
        assertNull(WallpaperCaveParser.toOriginalPath("/something/else.jpg"))
    }

    @Test
    fun `entities unescape once, numbers included`() {
        assertEquals("Dandy's World", WallpaperCaveParser.unescapeEntities("Dandy&#039;s World"))
        assertEquals("a & b", WallpaperCaveParser.unescapeEntities("a &amp; b"))
        assertEquals("«quote»", WallpaperCaveParser.unescapeEntities("&#171;quote&#187;"))
        // Pre-escaped text stays single-escaped after one pass.
        assertEquals("&lt;tag&gt;", WallpaperCaveParser.unescapeEntities("&amp;lt;tag&amp;gt;"))
    }

    @Test
    fun `album titles unescape their entities`() {
        val albums = WallpaperCaveParser.parseAlbums(searchAlbums)

        assertEquals(4, albums.size)
        assertEquals("12 wallpapers in Dandy's World", albums.last().title)
        assertEquals(12, albums.last().photoCount)
        assertEquals("dandys-world-wallpapers", albums.last().slug)
    }

    @Test
    fun `grid items with unknown thumbnail paths drop instead of lying`() =
        runTest {
            val grid =
                """
                <a href="/w/uwp1" title="one"><img src="/weird/uwp1.jpeg" width="200" height="100" /></a>
                <a href="/w/uwp2" title="two"><img src="/uwpr/uwp2.jpeg" width="200" height="100" /></a>
                """.trimIndent()

            configureWith(
                mapOf("https://wallpapercave.com/latest-uploads" to ok(grid)),
            )

            val page = provider.popular(page = 1).getOrThrow()

            assertEquals(listOf("uwp2"), page.wallpapers.map { it.id })
        }
}
