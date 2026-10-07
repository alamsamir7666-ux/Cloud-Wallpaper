package com.cloudimage.wallpaperflare

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider
import java.net.URLEncoder
import kotlin.random.Random

/**
 * WallpaperFlare (https://www.wallpaperflare.com) as a Cloudimage provider
 * package — a keyless scraper over the site's server-rendered pages.
 *
 * ## Site model
 *
 * WallpaperFlare is one flat, search-first catalogue: the homepage is the
 * popular ranking, `/search?wallpaper={query}` (`+` for spaces) answers
 * every query with the SAME grid markup, and every tag chip on the site
 * links back into that search — there are no category trees or albums to
 * walk. Grid cells self-describe completely: the preview image URL
 * `https://c{1..4}.wallpaperflare.com/wallpaper/{a}/{b}/{c}/{slug}-preview.jpg`
 * carries the wallpaper's identity (its path IS the id), the cell's own
 * text publishes TRUE pixel dimensions (`{W}x{H}px`), a license label and
 * a middot-separated tag row, and the original file is the preview's
 * suffix-stripped stem (`{slug}.jpg`) — so the grid alone fills
 * [Wallpaper] with everything except author and source page.
 *
 * ## Cloudflare
 *
 * The site sits behind a Cloudflare firewall whose bot management
 * challenges clients that do not look like browsers — the plain OkHttp
 * request the host sends by default (a non-browser User-Agent, no
 * Accept, no sec-fetch headers) is challenged EVEN on residential and
 * carrier IPs, which is exactly what WallpaperFlare 1.0.0 installs
 * reported: the host's WebView solver woke, Cloudflare's page loaded,
 * the Turnstile challenge never settled inside the WebView, and the
 * source failed with HTTP 403.
 *
 * The fix is to not look like a bot in the first place: every document
 * request here carries a complete, internally-coherent mobile-Chrome
 * navigation fingerprint — browser User-Agent, browser Accept and
 * Accept-Language, the full sec-fetch family, and matching sec-ch-ua
 * client hints, with same-origin Referer headers on deep navigations.
 * Cloudflare serves real browsers on phone IPs unchallenged, and a
 * request that scores like one rides the same lane. When a challenge
 * still fires (a hostile zone, a bad-IP day), the host's
 * [com.cloudimage.core.network] machinery — which every provider rides
 * through [configure] — solves it with its WebView bypass and, since
 * app v1.2.3, can fall back to fetching the document through the
 * WebView itself; nothing here duplicates that machinery, this plugin
 * only parses what comes back.
 *
 * ## Pagination honesty
 *
 * The deep-page URL shape is learned, not assumed: the pagination bar's
 * own links (read on every response) supply the deep-page template and the
 * stop signal — `nextPage` exists only when the bar advertises a page
 * beyond the one requested, and if the bar marks a CURRENT page that
 * disagrees with the page requested (a parameter the site ignored), the
 * walk stops instead of looping the first page forever.
 *
 * ## Contract mapping
 *
 * - [popular] walks the homepage ranking, paged as deep as the site's
 *   own bar allows;
 * - [search] rides `/search?wallpaper=` with `+`-encoded terms — the
 *   one URL shape the site's indexed pages confirm;
 * - [sections] offers query-preset shelves (Nature, Anime, Abstract,
 *   Cars, Games, Movies, Space, Animals, Marvel) — each a term the
 *   site's own search addresses precisely;
 * - [details] fetches `/wallpaper/{a}/{b}/{c}/{slug}` and reads
 *   og:image, the h1, the license line and the tag chips; a miss is a
 *   source failure and the host simply keeps the grid item's own URLs —
 *   browsing never depended on the detail page;
 * - [suggestTags] serves tags harvested from grids already seen — the
 *   cell text's real middot rows, never a third-party suggest service;
 * - [random] draws one term from a baked list and serves that search's
 *   first page — a lottery over the site's own ranking, honestly
 *   labeled by NOT claiming [Capability.RANDOM].
 */
class WallpaperFlareWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "WallpaperFlare",
            versionName = "1.1.0",
            author = "Cloudimage",
            description = "HD, 2K, 4K and 5K wallpapers from wallpaperflare.com - scraped, keyless.",
            // The site curates general-audience content and labels its
            // licenses; the host's rating switch still applies on top.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.LATEST, Capability.SEARCH, Capability.TAGS)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    /**
     * The homepage ranking, paged: page 1 is the bare URL, deeper pages
     * ride the template learned from the previous response's pagination
     * bar (or the `?page=` fallback on a cold instance). The bar on the
     * response itself decides whether a next page exists.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> = runCatching { listingPage(HOME_URL, page) }

    /**
     * The site's one true surface: `/search?wallpaper={query}` with `+`
     * for spaces (the form the site's own pages link). A blank query —
     * the contract's escape hatch — lands on the homepage ranking.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                listingPage(HOME_URL, page)
            } else {
                listingPage("$BASE_URL/search?wallpaper=${encode(query)}", page)
            }
        }

    /**
     * Suggestions come ONLY from tag rows this instance has already
     * fetched — the cells' own middot lists, remembered per instance. A
     * fresh instance answers nothing, which the host renders as "no
     * suggestions" rather than an error.
     */
    override suspend fun suggestTags(query: String): Result<List<String>> =
        runCatching {
            if (query.isBlank()) {
                emptyList()
            } else {
                synchronized(lock) { tagPool.toList() }
                    .filter { it.contains(query, ignoreCase = true) }
                    .take(TAG_SUGGESTION_LIMIT)
            }
        }

    /**
     * The home shelves: the popular ranking first (the default row the
     * host's merged home picks), then tag-style `query` presets the
     * site's search addresses precisely. Cheap and offline, as the
     * contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = HomeSection.DEFAULT_ID, title = "Popular"),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "landscape", title = "Landscape", filters = Filters.of("query" to "landscape")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("query" to "anime")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "cars")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "games")),
            HomeSection(id = "movies", title = "Movies", filters = Filters.of("query" to "movies")),
            HomeSection(id = "space", title = "Space", filters = Filters.of("query" to "space")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
            HomeSection(id = "marvel", title = "Marvel", filters = Filters.of("query" to "marvel")),
        )

    /**
     * The wallpaper page's own record — og:image, true dimensions,
     * license, tags — for the preview screen. A miss here (redesigned
     * page, moved wallpaper) is reported as the source failure it is:
     * the host already holds the grid item's own URLs, so the preview
     * degrades to grid data instead of breaking.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            // The id IS a path ("a/b/c/slug"), not a query value — its slashes
            // are legal path characters and must not be percent-encoded.
            // A detail page is an in-site navigation: same-origin, referred.
            val response = get("$BASE_URL/wallpaper/$id", DEEP_NAV_HEADERS)
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record = WallpaperFlareParser.parseDetail(response.bodyText) ?: error("unrecognized wallpaper page for '$id'")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl = record.imageUrl ?: gridThumbUrl(id),
                        fullUrl = record.imageUrl ?: gridFullUrl(id),
                        title = record.title,
                        width = record.width,
                        height = record.height,
                        tags = record.tags,
                    ),
                resolution =
                    if (record.width != null && record.height != null) "${record.width}x${record.height}" else null,
                sourceUrl = "$BASE_URL/wallpaper/$id",
            )
        }

    /**
     * A lottery over the site's own ranking: one baked term drawn at
     * random, that search's first page served as-is. The provider does
     * not claim [Capability.RANDOM] — the host never advertises this as
     * a random feed — but the contract's method answers honestly when
     * called.
     */
    override suspend fun random(): Result<List<Wallpaper>> =
        runCatching {
            val term = RANDOM_TERMS[Random.nextInt(RANDOM_TERMS.size)]
            listingPage("$BASE_URL/search?wallpaper=$term", 1).wallpapers
        }

    // ---------------------------------------------------------------- feed

    /**
     * Any listing page, robust to both pagination shapes the platform
     * family uses. Page 1 fetches the URL as given; deeper pages use the
     * template learned from an earlier response's bar (per
     * instance, best-effort) or append `page=N` in the matching style
     * when no template was learned yet. The response's OWN bar is the
     * authority on what follows: a higher page number must be
     * advertised, and when the bar prints a current page that disagrees
     * with the one requested, the walk stops — that is a parameter the
     * site ignored, not a next page.
     */
    private suspend fun listingPage(
        url: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val requestUrl =
            if (page == 1) {
                url
            } else {
                deepUrl(url, page)
            }
        // Deep pages navigate like a browser following the site's own
        // pagination: same-origin, referred from the site root. The first
        // page is a fresh address-bar navigation.
        val response = get(requestUrl, if (page == 1) FIRST_NAV_HEADERS else DEEP_NAV_HEADERS)
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val items = WallpaperFlareParser.parseGrid(response.bodyText)
        rememberTagsFrom(items)
        val bar = WallpaperFlareParser.parsePagination(response.bodyText)
        if (page > 1 && bar.currentPage != null && bar.currentPage != page) {
            // The site served a different page than requested — the deep
            // URL shape did not take. Serve nothing further rather than
            // looping page one forever.
            return Page(items.map(::gridWallpaper), nextPage = null)
        }
        val nextPage = if (bar.maxPage > page) page + 1 else null
        learnTemplate(bar, url)
        return Page(items.map(::gridWallpaper), nextPage = nextPage)
    }

    /**
     * The URL for a deep page: the learned template when one exists
     * (its `__PAGE__` marker swapped for the number), else the matching
     * `page=N` append — query style for search URLs, path style for the
     * bare homepage.
     */
    private fun deepUrl(
        baseUrl: String,
        page: Int,
    ): String {
        val template = synchronized(lock) { pageUrlTemplate }
        if (template != null) {
            val applied = template.replace("__PAGE__", page.toString())
            if (applied.startsWith("http") && !applied.contains("__PAGE__")) return applied
        }
        return if (baseUrl.contains('?')) {
            "$baseUrl&page=$page"
        } else if (baseUrl.removeSuffix("/") == BASE_URL) {
            "$baseUrl?page=$page"
        } else {
            "$baseUrl/page/$page"
        }
    }

    /** Records the bar's own deep-page shape for later calls, best-effort. */
    private fun learnTemplate(
        bar: WallpaperFlareParser.Pagination,
        baseUrl: String,
    ) {
        val template = bar.nextUrlTemplate ?: return
        if (!template.startsWith("http")) return
        synchronized(lock) {
            if (pageUrlTemplate == null) {
                pageUrlTemplate = template
            }
        }
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the cell already carries everything —
     * the preview as [Wallpaper.thumbUrl], the suffix-stripped stem as
     * [Wallpaper.fullUrl], TRUE dimensions from the cell's own `px` text
     * (this site publishes them in listings — rarer than it should be),
     * and the middot tag row verbatim.
     */
    private fun gridWallpaper(item: WallpaperFlareParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.id,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.fullUrl,
            title = item.title.ifBlank { null },
            width = item.width,
            height = item.height,
            tags = item.tags,
        )

    /** Harvests seen tags for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTagsFrom(items: List<WallpaperFlareParser.GridItem>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            items.flatMap { it.tags }.forEach { tag ->
                if (tagPool.size < TAG_POOL_LIMIT) tagPool.add(tag)
            }
        }
    }

    /** The preview URL an id implies — the `-preview.jpg` of its stem. */
    private fun gridThumbUrl(id: String): String = "https://$CDN_HOST/wallpaper/$id-preview.jpg"

    /** The original URL an id implies — the stem with its `.jpg`. */
    private fun gridFullUrl(id: String): String = "https://$CDN_HOST/wallpaper/$id.jpg"

    // ------------------------------------------------------------- plumbing

    private suspend fun get(
        url: String,
        headers: Map<String, String>,
    ): ProviderHttpResponse =
        httpClient?.get(url, headers)
            ?: error("configure() was not called")

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("wallpaperflare answered HTTP $statusCode")

    /** Form-style encoding: spaces as `+`, exactly how the site's own links are built. */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val ID = "cloudimage.wallpaperflare"
        const val BASE_URL = "https://www.wallpaperflare.com"
        const val HOME_URL = "$BASE_URL/"

        /**
         * The browser identity every document request wears — a current
         * mobile Chrome on Android, fully coherent: the User-Agent's
         * Chrome major (131) matches the `sec-ch-ua` brands, the platform
         * hint matches the UA's Android, and the sec-fetch family says
         * "top-level navigation" exactly like a tapped link would. No
         * `Accept-Encoding` on purpose — the host's OkHttp adds (and
         * transparently decompresses) its own.
         *
         * These ride as facade headers, which override the host's own
         * User-Agent for this provider's requests — EXCEPT when the host
         * replays under a WebView-earned clearance, whose cookies are
         * bound to the UA that earned them; that override is correct and
         * wins, and the rest of this set still rides along.
         */
        val BROWSER_NAV_HEADERS: Map<String, String> =
            mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                "Accept" to
                    "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "Accept-Language" to "en-US,en;q=0.9",
                "Upgrade-Insecure-Requests" to "1",
                "Sec-Fetch-Dest" to "document",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-User" to "?1",
                "sec-ch-ua" to
                    "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?1",
                "sec-ch-ua-platform" to "\"Android\"",
            )

        /** A fresh, address-bar-style navigation — `Sec-Fetch-Site: none`, no Referer. */
        val FIRST_NAV_HEADERS: Map<String, String> = BROWSER_NAV_HEADERS + ("Sec-Fetch-Site" to "none")

        /** An in-site navigation — same-origin with the site as the referrer. */
        val DEEP_NAV_HEADERS: Map<String, String> =
            BROWSER_NAV_HEADERS +
                mapOf(
                    "Sec-Fetch-Site" to "same-origin",
                    "Referer" to HOME_URL,
                )

        /** The image host grid cells disclose; `c4` is the one seen in the wild. */
        const val CDN_HOST = "c4.wallpaperflare.com"

        /** Deep-pagination cap: a hundred pages of one feed is plenty. */
        const val MAX_PAGES = 100

        /** Tag pool caps and the suggestion list size. */
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /** The lottery the honest [random] draws from. */
        val RANDOM_TERMS =
            listOf(
                "nature",
                "landscape",
                "mountain",
                "ocean",
                "forest",
                "space",
                "galaxy",
                "abstract",
                "anime",
                "city",
                "sunset",
                "waterfall",
            )

        /** One lock over the learned template and the tag pool. */
        val lock = Any()
    }

    private val tagPool = LinkedHashSet<String>()

    /** The deep-page URL shape learned from the first listing's bar, if any. */
    private var pageUrlTemplate: String? = null
}
