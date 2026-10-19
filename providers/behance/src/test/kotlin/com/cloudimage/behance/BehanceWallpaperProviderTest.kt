package com.cloudimage.behance

import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gate's 403 body — the cookie value literally in the markup, as served live. */
private const val CHALLENGE =
    """<!doctype html><html><head><meta name="robots" content="noindex,nofollow"></head>
    <body><script>
    document.cookie = "js_challenge_value=206d88e33db1b0d18e54e653413734629d069693be; Path=/";
    window.location.reload();
    </script></body></html>"""

/**
 * Behance provider over a scripted fake of the plugin-facing HTTP facade,
 * with fixtures cut from the live site's embedded state: the bot-gate
 * cookie dance, the cursor-paginated search, the project drilling, the
 * id-derived details fallback and the polite caps all stay covered
 * without a network.
 */
class BehanceWallpaperProviderTest {
    private val provider = BehanceWallpaperProvider()

    /**
     * URL-routed responses behind the site's bot gate: a request without
     * the challenge cookie is answered with the 403 challenge page (the
     * cookie value is literally in its body, exactly as the live gate
     * serves it), and the replay carrying the cookie gets the real page.
     */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        val pages = mutableMapOf<String, ProviderHttpResponse>()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url to headers
            val page = pages[url] ?: return ProviderHttpResponse(500, emptyMap(), ByteArray(0))
            if (headers.keys.any { it.equals("Cookie", ignoreCase = true) }) {
                return page
            }
            return ProviderHttpResponse(403, emptyMap(), CHALLENGE.toByteArray())
        }
    }

    private fun ok(body: String): ProviderHttpResponse = ProviderHttpResponse(200, emptyMap(), body.toByteArray())

    private fun configureWith(pages: Map<String, String>): FakeClient =
        FakeClient().apply {
            this.pages.putAll(pages.mapValues { ok(it.value) })
            provider.configure(this, ProviderSettings { null })
        }

    // ------------------------------------------------------------ fixtures

    private fun searchNode(
        id: Int,
        slug: String,
        name: String,
        flagged: Boolean = false,
        privacy: String = "PUBLIC",
    ): String =
        """{"id":$id,"oid":"abc","url":"https://www.behance.net/gallery/$id/$slug",""" +
            """"isFlagged":$flagged,"isPrivate":false,"slug":"$slug","name":"$name",""" +
            """"publishStatus":"PUBLISHED","privacyLevel":"$privacy",""" +
            """"owners":[{"displayName":"Trey C."}],"covers":{"allAvailable":[]},"stats":{}}"""

    private fun searchHtml(
        nodes: List<String>,
        hasMore: Boolean,
        cursor: String?,
    ): String {
        val pageInfo =
            if (hasMore) {
                """{"hasNextPage":true,"endCursor":"$cursor"}"""
            } else {
                """{"hasNextPage":false,"endCursor":"$cursor"}"""
            }
        return """<html><body>
            <script type="application/json" id="beconfig-store_state">{"search":{"projects":{"search":
            {"nodes":[${nodes.joinToString(",")}],"pageInfo":$pageInfo}}}}
            </script></body></html>"""
    }

    /** A project page: two image modules, one text module, two tags. */
    private fun projectHtml(
        pid: Int,
        slug: String,
        name: String,
        mature: Boolean = false,
    ): String {
        val hashA = "6a409a$pid.63d2c8a9c7b29.png"
        val hashB = "9852df$pid.63d2c8a9c6aee.jpg"

        fun ladder(
            hash: String,
            w: Int,
            h: Int,
            sourceW: Int,
        ): String =
            """{"id":1,"__typename":"ImageModule","width":$w,"height":$h,
            "src":"https://mir-s3-cdn-cf.behance.net/project_modules/disp/$hash",
            "imageSizes":{"allAvailable":[
            {"url":"https://mir-s3-cdn-cf.behance.net/project_modules/source/$hash","width":$sourceW,"type":"JPG"},
            {"url":"https://mir-s3-cdn-cf.behance.net/project_modules/max_632_webp/$hash","width":632,"type":"WEBP"},
            {"url":"https://mir-s3-cdn-cf.behance.net/project_modules/max_632/$hash","width":632,"type":"JPG"},
            {"url":"https://mir-s3-cdn-cf.behance.net/project_modules/disp/$hash","width":600,"type":"JPG"}
            ]},"caption":""}"""
        return """<html><body>
            <script type="application/json" id="beconfig-store_state">{"project":{"project":{
            "id":$pid,"slug":"$slug","name":"$name",
            "url":"https://www.behance.net/gallery/$pid/$slug",
            "hasMatureContent":$mature,"privacyLevel":"PUBLIC",
            "owners":[{"displayName":"Trey C.","username":"trey"}],
            "tags":[{"title":"gradient"},{"title":"minimal wallpaper"}],
            "modules":[
            ${ladder(hashA, 600, 400, 4500)},
            ${ladder(hashB, 600, 600, 2160)},
            {"id":3,"__typename":"TextModule","caption":"just text"}
            ]}}}
            </script></body></html>"""
    }

    /** Page 1 of the wallpaper search: eight nodes, one flagged, one private. */
    private fun searchPage1(): String =
        searchHtml(
            listOf(
                searchNode(111, "Gradient-Wallpapers", "Gradient Wallpapers"),
                searchNode(333, "Flagged-Project", "Flagged Project", flagged = true),
                searchNode(222, "Abstract-Collection", "Abstract Collection"),
                searchNode(777, "Private-Project", "Private Project", privacy = "PRIVATE"),
                searchNode(444, "Minimal-Papers", "Minimal Papers"),
                searchNode(555, "Nature-Shots", "Nature Shots"),
                searchNode(666, "Aesthetic-Pack", "Aesthetic Pack"),
                searchNode(888, "Mobile-Set", "Mobile Set"),
            ),
            hasMore = true,
            cursor = "MjQ=",
        )

    /** Page 2: two live projects and one mature project. */
    private fun searchPage2(): String =
        searchHtml(
            listOf(
                searchNode(999, "Desktop-Bundle", "Desktop Bundle"),
                searchNode(101, "Retro-Waves", "Retro Waves"),
                searchNode(102, "Dark-Moods", "Dark Moods"),
            ),
            hasMore = false,
            cursor = null,
        )

    private fun galleryUrl(
        pid: Int,
        slug: String,
    ): String = "https://www.behance.net/gallery/$pid/$slug"

    // ---------------------------------------------------------------- tests

    @Test
    fun `popular page one drills the search feed through the bot gate`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.behance.net/search/projects/wallpaper?sort=appreciations" to searchPage1(),
                        galleryUrl(111, "Gradient-Wallpapers") to projectHtml(111, "Gradient-Wallpapers", "Gradient Wallpapers"),
                        galleryUrl(222, "Abstract-Collection") to projectHtml(222, "Abstract-Collection", "Abstract Collection"),
                        galleryUrl(444, "Minimal-Papers") to projectHtml(444, "Minimal-Papers", "Minimal Papers"),
                        galleryUrl(555, "Nature-Shots") to projectHtml(555, "Nature-Shots", "Nature Shots"),
                        galleryUrl(666, "Aesthetic-Pack") to projectHtml(666, "Aesthetic-Pack", "Aesthetic Pack"),
                        galleryUrl(888, "Mobile-Set") to projectHtml(888, "Mobile-Set", "Mobile Set"),
                    ),
                )

            val page = provider.popular(1, Filters.None).getOrThrow()

            // The search page's six live projects, two modules each.
            assertEquals(12, page.wallpapers.size)
            assertEquals(2, page.nextPage)

            // The gate was walked: one challenge 403, then the cookie replay.
            val searchRequests =
                client.requests.filter { it.first == "https://www.behance.net/search/projects/wallpaper?sort=appreciations" }
            assertEquals(2, searchRequests.size)
            assertNull(searchRequests[0].second.keys.firstOrNull { it.equals("Cookie", ignoreCase = true) })
            assertEquals(
                "js_challenge_value=206d88e33db1b0d18e54e653413734629d069693be",
                searchRequests[1].second["Cookie"],
            )
            // Every HTML request carries the browser identity the gate demands.
            client.requests.forEach { (_, headers) ->
                assertTrue(headers["User-Agent"]!!.startsWith("Mozilla/5.0"))
                assertTrue(headers["Accept"]!!.startsWith("text/html"))
                assertEquals("en-US,en;q=0.9", headers["Accept-Language"])
            }

            // The flagged and private projects were never drilled.
            assertTrue(client.requests.none { it.first.contains("Flagged-Project") })
            assertTrue(client.requests.none { it.first.contains("Private-Project") })

            val first = page.wallpapers.first()
            assertEquals("111-6a409a111.63d2c8a9c7b29.png-Gradient-Wallpapers", first.id)
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/project_modules/max_632/6a409a111.63d2c8a9c7b29.png",
                first.thumbUrl,
            )
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/project_modules/source/6a409a111.63d2c8a9c7b29.png",
                first.fullUrl,
            )
            assertEquals("Gradient Wallpapers", first.title)
            assertEquals(600, first.width)
            assertEquals(400, first.height)
            assertEquals(listOf("gradient", "minimal wallpaper"), first.tags)
        }

    @Test
    fun `page two rides the cursor and skips the mature project`() =
        runTest {
            val client = configureWith(emptyMap())
            client.pages.putAll(
                mapOf(
                    "https://www.behance.net/search/projects/wallpaper?sort=appreciations" to searchPage1(),
                    "https://www.behance.net/search/projects/wallpaper?sort=appreciations&after=MjQ%3D" to searchPage2(),
                    galleryUrl(111, "Gradient-Wallpapers") to projectHtml(111, "Gradient-Wallpapers", "Gradient Wallpapers"),
                    galleryUrl(222, "Abstract-Collection") to projectHtml(222, "Abstract-Collection", "Abstract Collection"),
                    galleryUrl(444, "Minimal-Papers") to projectHtml(444, "Minimal-Papers", "Minimal Papers"),
                    galleryUrl(555, "Nature-Shots") to projectHtml(555, "Nature-Shots", "Nature Shots"),
                    galleryUrl(666, "Aesthetic-Pack") to projectHtml(666, "Aesthetic-Pack", "Aesthetic Pack"),
                    galleryUrl(888, "Mobile-Set") to projectHtml(888, "Mobile-Set", "Mobile Set"),
                    galleryUrl(999, "Desktop-Bundle") to projectHtml(999, "Desktop-Bundle", "Desktop Bundle"),
                    galleryUrl(101, "Retro-Waves") to projectHtml(101, "Retro-Waves", "Retro Waves"),
                    galleryUrl(102, "Dark-Moods") to projectHtml(102, "Dark-Moods", "Dark Moods", mature = true),
                ).mapValues { ok(it.value) },
            )

            provider.popular(1, Filters.None).getOrThrow()
            val page2 = provider.popular(2, Filters.None).getOrThrow()

            // Page two: the two live projects of search page two, the mature one skipped.
            assertEquals(4, page2.wallpapers.size)
            assertTrue(page2.wallpapers.all { it.id.startsWith("999-") || it.id.startsWith("101-") })
            assertNull(page2.nextPage)
            // The cursor pagination actually walked.
            assertTrue(
                client.requests.any { it.first.endsWith("search/projects/wallpaper?sort=appreciations&after=MjQ%3D") },
            )
        }

    @Test
    fun `sorting filters map onto the site's own orders`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://www.behance.net/search/projects/sunset?sort=published_date" to searchHtml(emptyList(), false, null)),
                )
            provider.search("sunset", 1, Filters.of("sorting" to "date")).getOrThrow()
            assertTrue(client.requests.any { it.first == "https://www.behance.net/search/projects/sunset?sort=published_date" })

            client.requests.clear()
            client.pages["https://www.behance.net/search/projects/sunset"] = ok(searchHtml(emptyList(), false, null))
            provider.search("sunset", 1, Filters.of("sorting" to "relevance")).getOrThrow()
            // The site's recommended order is its default: no sort parameter rides.
            assertTrue(
                client.requests.any { it.first == "https://www.behance.net/search/projects/sunset" && "sort" !in it.first },
            )
        }

    @Test
    fun `multi-word queries ride percent-encoded paths`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://www.behance.net/search/projects/digital%20art%20wallpaper" to searchHtml(emptyList(), false, null)),
                )
            val page = provider.search("digital art wallpaper", 1, Filters.of("sorting" to "relevance")).getOrThrow()
            assertEquals(0, page.wallpapers.size)
            assertTrue(client.requests.any { it.first == "https://www.behance.net/search/projects/digital%20art%20wallpaper" })
        }

    @Test
    fun `details serves the drilled record and the project cache spares a refetch`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.behance.net/search/projects/wallpaper?sort=appreciations" to
                            searchHtml(
                                listOf(searchNode(111, "Gradient-Wallpapers", "Gradient Wallpapers")),
                                hasMore = false,
                                cursor = null,
                            ),
                        galleryUrl(111, "Gradient-Wallpapers") to projectHtml(111, "Gradient-Wallpapers", "Gradient Wallpapers"),
                    ),
                )
            val feed = provider.popular(1, Filters.None).getOrThrow()
            val galleryFetches = client.requests.count { it.first == galleryUrl(111, "Gradient-Wallpapers") }
            val id = feed.wallpapers.first().id

            val details = provider.details(id).getOrThrow()

            // No second project fetch: the drill's parse is cached.
            assertEquals(galleryFetches, client.requests.count { it.first == galleryUrl(111, "Gradient-Wallpapers") })
            assertEquals("Gradient Wallpapers", details.wallpaper.title)
            assertEquals("Trey C.", details.author)
            assertEquals("4500 × 3000", details.resolution)
            assertEquals(galleryUrl(111, "Gradient-Wallpapers"), details.sourceUrl)
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/project_modules/source/6a409a111.63d2c8a9c7b29.png",
                details.wallpaper.fullUrl,
            )
        }

    @Test
    fun `details degrades to the id-derived record when the page is gone`() =
        runTest {
            val id = "111-6a409a111.63d2c8a9c7b29.png-Gradient-Wallpapers"
            val client =
                FakeClient().apply {
                    pages[galleryUrl(111, "Gradient-Wallpapers")] =
                        ProviderHttpResponse(404, emptyMap(), ByteArray(0))
                    provider.configure(this, ProviderSettings { null })
                }
            val details = provider.details(id).getOrThrow()
            assertNull(details.wallpaper.title)
            assertNull(details.author)
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/project_modules/max_632/6a409a111.63d2c8a9c7b29.png",
                details.wallpaper.thumbUrl,
            )
            assertEquals(
                "https://mir-s3-cdn-cf.behance.net/project_modules/source/6a409a111.63d2c8a9c7b29.png",
                details.wallpaper.fullUrl,
            )
            assertEquals(galleryUrl(111, "Gradient-Wallpapers"), details.sourceUrl)
        }

    @Test
    fun `transport failures propagate untouched`() =
        runTest {
            val id = "111-6a409a111.63d2c8a9c7b29.png-Gradient-Wallpapers"
            val failing =
                object : ProviderHttpClient {
                    override suspend fun get(
                        url: String,
                        headers: Map<String, String>,
                    ): ProviderHttpResponse = throw ProviderHttpException("connection reset")
                }
            provider.configure(failing, ProviderSettings { null })
            val failure = provider.details(id).exceptionOrNull()
            assertTrue(failure is ProviderHttpException)
        }

    @Test
    fun `suggestTags answers from tags the feed has already seen`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.behance.net/search/projects/wallpaper?sort=appreciations" to
                            searchHtml(
                                listOf(searchNode(111, "Gradient-Wallpapers", "Gradient Wallpapers")),
                                hasMore = false,
                                cursor = null,
                            ),
                        galleryUrl(111, "Gradient-Wallpapers") to projectHtml(111, "Gradient-Wallpapers", "Gradient Wallpapers"),
                    ),
                )
            // Nothing seen yet: no suggestions, no requests.
            assertEquals(emptyList<String>(), provider.suggestTags("gra").getOrThrow())
            assertEquals(0, client.requests.size)

            provider.popular(1, Filters.None).getOrThrow()
            assertEquals(listOf("gradient"), provider.suggestTags("gra").getOrThrow())
            assertEquals(emptyList<String>(), provider.suggestTags("nope").getOrThrow())
        }

    @Test
    fun `twelve shelves ride query and sorting presets`() =
        runTest {
            val sections = provider.sections()
            assertEquals(12, sections.size)
            assertEquals("popular", sections[0].id)
            assertEquals("wallpaper", sections[0].filters.valuesFor("query").first())
            assertEquals("toplist", sections[0].filters.valuesFor("sorting").first())
            assertEquals("date", sections[1].filters.valuesFor("sorting").first())
            assertEquals(
                "anime wallpaper",
                sections
                    .first { it.id == "anime" }
                    .filters
                    .valuesFor("query")
                    .first(),
            )
            assertTrue(sections.drop(2).all { it.filters.valuesFor("sorting").isEmpty() })
        }

    @Test
    fun `a blank query is the default feed`() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://www.behance.net/search/projects/wallpaper?sort=appreciations" to searchHtml(emptyList(), false, null)),
                )
            provider.search("   ", 1, Filters.None).getOrThrow()
            assertTrue(client.requests.any { it.first == "https://www.behance.net/search/projects/wallpaper?sort=appreciations" })
        }

    @Test
    fun `deep pages past the cap answer empty without touching the network`() =
        runTest {
            val client = configureWith(emptyMap())
            val page = provider.search("sunset", 11, Filters.None).getOrThrow()
            assertEquals(0, page.wallpapers.size)
            assertNull(page.nextPage)
            assertEquals(0, client.requests.size)
        }

    @Test
    fun `a fresh feed restart forgets what the earlier session served`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.behance.net/search/projects/wallpaper?sort=appreciations" to
                            searchHtml(
                                listOf(searchNode(111, "Gradient-Wallpapers", "Gradient Wallpapers")),
                                hasMore = false,
                                cursor = null,
                            ),
                        galleryUrl(111, "Gradient-Wallpapers") to projectHtml(111, "Gradient-Wallpapers", "Gradient Wallpapers"),
                    ),
                )
            val first = provider.popular(1, Filters.None).getOrThrow()
            val again = provider.popular(1, Filters.None).getOrThrow()
            assertEquals(first.wallpapers.map { it.id }, again.wallpapers.map { it.id })
        }

    @Test
    fun `random answers the default feed to keep the contract total`() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.behance.net/search/projects/wallpaper?sort=appreciations" to
                            searchHtml(
                                listOf(searchNode(111, "Gradient-Wallpapers", "Gradient Wallpapers")),
                                hasMore = false,
                                cursor = null,
                            ),
                        galleryUrl(111, "Gradient-Wallpapers") to projectHtml(111, "Gradient-Wallpapers", "Gradient Wallpapers"),
                    ),
                )
            val wallpapers = provider.random().getOrThrow()
            assertEquals(2, wallpapers.size)
            assertTrue(client.requests.any { it.first.contains("/search/projects/wallpaper") })
        }
}
