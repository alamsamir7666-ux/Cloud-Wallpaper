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
 * popular ranking, `/search?wallpaper={query}` (`+` for spaces — the URL
 * the site's own search form and tag chips build) answers every query with
 * the SAME microdata grid, and every grid cell self-describes completely:
 * the anchor's href IS the wallpaper's page (`https://www.wallpaperflare.com/{slug}`
 * — v1.3.0's anchor, captured live; v1.2.0 guessed `/wallpaper/{a}/{b}/{c}/{slug}`
 * from the CDN path and every tap answered the site's 404 page), the item's
 * schema.org microdata publishes the TRUE pixel dimensions (`Original
 * wallpaper dimensions is {W}x{H}px`), the keyword row, and the display
 * name, and the image CDN (`https://c{1..4}.wallpaperflare.com/wallpaper/{a}/{b}/{c}/{slug}-preview.jpg`)
 * serves the previews and thumbs the grids carry.
 *
 * ## The original's URL (v1.2.7's lesson)
 *
 * The original file is NOT a derivable URL — v1.3.0 shipped the preview's
 * suffix-stripped stem and the CDN answered every such request with a
 * 404, which is exactly what installs in the wild reported as previews
 * that never load and saves that end in "server error". The site MINTS
 * the original's URL per wallpaper: the download page
 * (`/{slug}/download`) embeds a Cloudflare Turnstile widget whose
 * callback exchanges a token for a 32-hex hash and then writes
 * `https://r{N}.wallpaperflare.com/…/…-{hash}.jpg` into the page's
 * `show_img` — a URL that IS publicly fetchable afterwards. Listings
 * therefore carry a BLANK [Wallpaper.fullUrl], and [details] fetches the
 * download page (through the host's WebView document lane, which
 * executes that JavaScript exactly as a browser would) and reads the
 * minted URL out of the rendered DOM. The hash is stable per wallpaper,
 * so a resolved record is remembered for the instance's lifetime and a
 * second open of the same wallpaper costs nothing.
 *
 * ## Cloudflare
 *
 * The site sits behind a Cloudflare firewall whose bot management
 * decides what to serve by fingerprint: real browsers on phone IPs get
 * the catalogue, and everything else gets answered with a 403 — and, as
 * v1.2.5 learned, this zone binds its clearance to the WebView's TLS
 * fingerprint too, so no OkHttp replay ever passes: the host's ladder
 * fetches every document through the WebView engine (see
 * [com.cloudimage.core.network]). Nothing here duplicates that machinery;
 * this plugin only parses what comes back.
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
 *   one URL shape the site's own pages link;
 * - [sections] offers query-preset shelves (Nature, Anime, Abstract,
 *   Cars, Games, Movies, Space, Animals, Marvel) — each a term the
 *   site's own search addresses precisely;
 * - [details] fetches `/{slug}/download` and reads the MINTED original
 *   URL (plus the true dimensions and file size) out of the rendered
 *   page — the one place the site discloses it; a miss is a source
 *   failure and the host keeps the grid item's own values, with the
 *   preview honestly reporting an unresolved image rather than loading
 *   a URL that never existed;
 * - [suggestTags] serves tags harvested from grids already seen — the
 *   cells' own keyword rows, never a third-party suggest service;
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
            versionName = "1.4.0",
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
     * The wallpaper's definitive record, minted off its DOWNLOAD page —
     * the one page whose JavaScript writes the original's URL into the
     * DOM. The response carries the minted file as [Wallpaper.fullUrl]
     * (a BLANK thumb: the grid item's own preview is better than
     * anything this page states, and the host overlays non-blank values
     * only), the page's TRUE dimensions and file size for the info sheet,
     * and the wallpaper page as the source URL. Resolutions are remembered
     * per id — the hash is stable per wallpaper, so the second open of
     * anything already seen costs no trip at all.
     *
     * A miss — a page the widget never minted (its token flow was still
     * mid-flight when the host read the DOM, or the site changed its
     * plumbing) — is the source failure it is: the host keeps the grid
     * item's values and the preview reports an unresolved image rather
     * than pretending a derivable URL exists.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            synchronized(resolutionLock) { resolved[id] }?.let { return@runCatching it }
            val response = get("$BASE_URL/$id/download")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record =
                WallpaperFlareParser.parseDownloadPage(response.bodyText)
                    ?: error("download page stated no original for '$id'")
            val details =
                WallpaperDetails(
                    wallpaper =
                        Wallpaper(
                            id = id,
                            providerId = ID,
                            // Blank on purpose: the grid item's preview is
                            // the better thumb, and the host keeps it.
                            thumbUrl = "",
                            fullUrl = record.originalUrl,
                            title = null,
                            width = record.width,
                            height = record.height,
                        ),
                    resolution =
                        if (record.width != null && record.height != null) "${record.width}x${record.height}" else null,
                    fileSizeBytes = record.fileSizeBytes,
                    sourceUrl = "$BASE_URL/$id",
                )
            synchronized(resolutionLock) {
                if (resolved.size < RESOLUTION_CACHE_LIMIT) resolved[id] = details
            }
            details
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
        // The host client's own identity rides the request — see the class
        // KDoc's Cloudflare section for why this plugin impersonates nothing.
        val response = get(requestUrl)
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

    // ------------------------------------------------------------- plumbing

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url, emptyMap())
            ?: error("configure() was not called")

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("wallpaperflare answered HTTP $statusCode")

    /** Form-style encoding: spaces as `+`, exactly how the site's own links are built. */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val ID = "cloudimage.wallpaperflare"
        const val BASE_URL = "https://www.wallpaperflare.com"
        const val HOME_URL = "$BASE_URL/"

        /** Deep-pagination cap: a hundred pages of one feed is plenty. */
        const val MAX_PAGES = 100

        /** Tag pool caps and the suggestion list size. */
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /** How many minted originals to remember — a browsing session's worth. */
        const val RESOLUTION_CACHE_LIMIT = 200

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

    /**
     * Minted originals remembered per id — the site's hashes are stable,
     * so a resolution outlives the instance's every later use of it.
     */
    private val resolved = LinkedHashMap<String, WallpaperDetails>()

    /** One lock over the resolution cache. */
    private val resolutionLock = Any()
}
