package com.cloudimage.wallpapersden

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

/**
 * WallpapersDen (https://wallpapersden.com) as a Cloudimage provider package
 * — a scraping source in the official set: no API, no key, just the site's
 * own server-rendered pages read the way its browser UI does.
 *
 * ## Site model
 *
 * The site is a flat, server-rendered catalog: every listing — latest,
 * popular ("Best Wallpapers"), random, the thirty-odd categories, the tag
 * pages, the resolution pages, search — answers with the same grid of
 * twenty-odd wallpaper cards, paginated `/page/{n}/` (search appends
 * `&page={n}`), with a `rel="next"` link as the site's own "more exists"
 * signal. The detail page `/{slug}-wallpaper/` carries the preview, the
 * resolution options and the "Download Original Wallpaper" link.
 *
 * ## The image URL families — and the token
 *
 * The CDN serves three derivable families per wallpaper, all keyed by the
 * `(slug, numericId)` pair the grid's thumbnails disclose:
 * - `ws-{slug}_{id}.jpg` — the grid thumbnail, a uniform 4:3 crop;
 * - `wxl-`/`hxl-{slug}_{id}.jpg` — the detail page preview, one prefix per
 *   orientation, ~622px — display quality, not download quality;
 * - `download/{slug}_{token}.jpg` — the original, full quality.
 *
 * The download token is the site's own deterministic, UNSIGNED encoding of
 * `{id}_deexpoo_{W}x{H}` (each character +53, base64url, no padding) —
 * cracked here from live tokens and verified byte-identical across every
 * wallpaper sampled, stable across the site's pages and dates. The site
 * constructs these URLs itself for every resolution it offers (every
 * wallpaper's page links the same twenty-four standard sizes, portrait
 * originals included), which is exactly what this provider does:
 *
 * - the GRID item's `fullUrl` is the constructed download URL at 3840x2160
 *   (2160x3840 when the title names a phone wallpaper — the site's own
 *   iPhone/mobile naming convention), a size the site itself offers for
 *   every wallpaper, so the viewer, apply and save flows all stream a
 *   full-quality file on the first tap;
 * - [details] fetches the wallpaper's own page and answers the TRUE
 *   original — its exact URL and its true dimensions, decoded from the
 *   token the page carries — upgrading the info sheet's numbers.
 *
 * ## How the contract maps onto it
 *
 * - [popular] rides the host vocabulary: `category` walks that category's
 *   listing (anime natively, people through the site's Celebrities
 *   category), `sorting=date` walks the latest feed, `sorting=random`
 *   serves one batch of the site's random page, and everything else lands
 *   on the site's popular ranking — the default feed the browse tab shows.
 * - [search] runs the site's own `GET /search?q=` for free text, paginated
 *   `&page={n}` — the same Laravel paginator shape HDQWalls serves. A
 *   query naming a browse tab exactly walks that tab's listing instead
 *   (the same routing WallpaperCave's search does), so the tab presets and
 *   plain category searches ride the site's own taxonomy — verified live
 *   for every path behind them.
 * - [sections] offers fourteen shelves: Popular, Latest, Anime and
 *   Celebrities as host presets, the rest as tag-style `query` presets the
 *   exact-term routing above serves.
 * - [details] fetches `/{slug}-wallpaper/` and reads the download link, the
 *   token-decoded TRUE dimensions and the site's own title. A non-2xx or
 *   unrecognized page is an honest failure — the slug alone discloses no
 *   original to degrade to, and the host's viewer already holds the grid
 *   item's own working URLs.
 * - [random] serves the site's `/random-wallpapers` batch as-is — one
 *   fresh set per load, no stable page 2.
 *
 * ## Politeness
 *
 * The site's robots.txt leaves every path used here open — it excludes only
 * `/wall-resolution` and `/wall-viewed` (two XHR endpoints this provider
 * never calls), the favicon and `/cdn-cgi`. Fetches happen on explicit user
 * actions, one request per page, with a hundred-page deep-pagination cap;
 * a browser tab on the same pages costs the same or more. The site sits
 * behind Cloudflare bot management that hard-blocks datacenter IPs but
 * serves real devices; the host's WebView clearance machinery already sits
 * between this plugin and the network should a challenge ever appear.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the only mutable state is the
 * tag pool feeding [suggestTags] and the served-id window guarding the
 * search feed's page seams — bonuses, never dependencies. A fresh instance
 * answers identically, at worst without suggestions or with one repeated
 * item. All mutable state is guarded by one lock; sections load in parallel
 * on the host side.
 *
 * ## Dimensions
 *
 * Grid items carry NONE, on purpose. The site's listing markup hard-codes
 * `width="320px" height="240px"` — its uniform 4:3 card crop — on every
 * single cell, whatever the file's true size, so those attributes describe
 * the THUMBNAIL, never the wallpaper (a 3840x2160 landscape and a
 * 1920x2344 portrait both arrive labeled 320x240). Publishing them made
 * HDQWalls's info sheet claim one wrong number for every wallpaper — the
 * bug that provider's v1.0.1 fixed; this provider starts honest instead.
 * [details] is where the TRUE resolution arrives: decoded from the
 * original's own download token, the only place the site publishes it.
 */
class WallpapersDenWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "WallpapersDen",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "HD, 4K, 5K and 8K wallpapers from wallpapersden.com - scraped, keyless.",
            // The site curates its uploads and carries no per-item rating
            // metadata; SFW is its own claim and the honest floor. The
            // host's rating switch still applies on top.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.LATEST, Capability.SEARCH, Capability.FILTERS, Capability.TAGS, Capability.RANDOM)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    /**
     * The default feed, ridden through the host vocabulary: a recognized
     * `category` walks that category's listing, `sorting=date` walks the
     * latest feed, `sorting=random` serves one random batch (the site's
     * random page reshuffles on every load — there is no stable page 2),
     * and everything else lands on the site's popular ranking. Category
     * wins over sorting: it is the primary axis of the site's content.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val category = filters.valuesFor("category").firstNotNullOfOrNull(hostCategoryPath::get)
            when {
                category != null -> listingPage(category, page)
                filters.isSelected("sorting", "date") -> listingPage(LATEST_PATH, page)
                filters.isSelected("sorting", "random") -> Page(randomBatch(), nextPage = null)
                else -> listingPage(POPULAR_PATH, page)
            }
        }

    /**
     * The site's own search for free text, paginated as deep as the site
     * itself goes. A blank query (the contract's escape hatch) lands on
     * the latest feed's first page, the same default the blank popular
     * feed would show. A query naming a browse tab exactly walks that
     * tab's listing instead — the section presets arrive here as their
     * `query` values, and the site's own taxonomy answers such terms far
     * better than its title matcher, so this is the site's own content
     * with the browse behavior on top (the same routing WallpaperCave's
     * search performs).
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching listingPage(LATEST_PATH, 1)
            }
            val term = query.trim().lowercase()
            termPaths[term]?.let { path ->
                return@runCatching listingPage(path, page)
            }
            searchPage(query, page)
        }

    /**
     * Suggestions come ONLY from tags this instance has already seen in
     * fetched pages — no keystroke-driven scraping of the site's search. A
     * fresh instance answers nothing, which the host treats as "no
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
     * The site's shelves as tabs: the two ranked feeds first (Popular is
     * the default row the host's merged home picks), then the categories
     * in bar order. Anime rides the host's own `category` vocabulary and
     * Celebrities its `people` value; every other shelf is a tag-style
     * `query` preset the host routes through [search] with that term —
     * which the exact-term routing above walks down the site's own
     * category pages. Cheap and offline, as the contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = "popular", title = "Popular"),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("category" to "anime")),
            HomeSection(id = "girls", title = "Girls", filters = Filters.of("query" to "girls")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "cars")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "games")),
            HomeSection(id = "movies", title = "Movies", filters = Filters.of("query" to "movies")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "celebrities", title = "Celebrities", filters = Filters.of("category" to "people")),
            HomeSection(id = "superheroes", title = "Superheroes", filters = Filters.of("query" to "superheroes")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract")),
            HomeSection(id = "space", title = "Space", filters = Filters.of("query" to "space")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
            HomeSection(id = "sports", title = "Sports", filters = Filters.of("query" to "sports")),
        )

    /**
     * The definitive record for an id: the page's own download link is the
     * original's exact URL, and the token it carries decodes to the file's
     * TRUE dimensions and the wallpaper's numeric id — the only place the
     * site publishes either. Transport failures propagate untouched, per
     * the facade contract; a non-2xx answer (deleted wallpaper, redesigned
     * page) or an unrecognized shape is a source failure, reported as such
     * — the slug alone discloses no original URL to degrade to, and the
     * host's viewer already holds the grid item's own working URLs.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/${encode(id)}-wallpaper/")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record = WallpapersDenParser.parseDetail(response.bodyText) ?: error("unrecognized wallpaper page for '$id'")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl = thumbUrl(id, record.numericId),
                        fullUrl = record.originalUrl,
                        title = record.title.ifBlank { null },
                        width = record.width,
                        height = record.height,
                        tags = tagsFromTitle(record.title),
                    ),
                resolution =
                    if (record.width != null && record.height != null) "${record.width}x${record.height}" else null,
                sourceUrl = "$BASE_URL/$id-wallpaper/",
            )
        }

    /** The site's own random page: one fresh batch, no stable page 2. */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { randomBatch() }

    // ---------------------------------------------------------------- feed

    /**
     * Any listing page: fetch, parse the grid, read the pagination bar's
     * `rel="next"` link for what follows. The link is the site's own
     * "more exists" signal; a [MAX_PAGES] cap bounds the deepest scroll
     * the same politeness way every scraping provider in the set applies.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val response = get(listingUrl(path, page))
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val wallpapers =
            WallpapersDenParser
                .parseGrid(response.bodyText)
                .map(::gridWallpaper)
                .let(::rememberTagsIn)
        return Page(wallpapers, nextPage = WallpapersDenParser.parseNextPage(response.bodyText))
    }

    /**
     * The free-text search feed: the same grid, paginated through the
     * query string. The pagination's exact wire shape is the one piece of
     * the site this provider could not verify from a datacenter IP (its
     * Cloudflare config blocks them outright), so a served-id window
     * guards the seam: if a page ever answers with items an earlier page
     * of this query already served — a mis-shaped `page` parameter makes
     * Laravel re-serve page one — the repeats drop, and an entirely
     * duplicate page ends the feed instead of looping it. Correct
     * pagination never notices the window exists.
     */
    private suspend fun searchPage(
        query: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val response = get("$BASE_URL/search?q=${encode(query)}${if (page > 1) "&page=$page" else ""}")
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val wallpapers = freshInSearch(query, WallpapersDenParser.parseGrid(response.bodyText).map(::gridWallpaper))
        rememberTagsIn(wallpapers)
        val moreRemain = WallpapersDenParser.parseNextPage(response.bodyText) != null
        val endedOnRepeats = wallpapers.isEmpty() && page > 1
        return Page(wallpapers, nextPage = if (moreRemain && !endedOnRepeats) page + 1 else null)
    }

    /** The random batch — same grid, no pagination signal worth reading. */
    private suspend fun randomBatch(): List<Wallpaper> {
        val response = get("$BASE_URL$RANDOM_PATH/")
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return WallpapersDenParser.parseGrid(response.bodyText).map(::gridWallpaper).let(::rememberTagsIn)
    }

    /** A listing path at a page: path pagination, page one bare. */
    private fun listingUrl(
        path: String,
        page: Int,
    ): String =
        when {
            page == 1 -> "$BASE_URL$path/"
            else -> "$BASE_URL$path/page/$page/"
        }

    /**
     * Drops ids this query's session already served; remembers the rest,
     * oldest first out past the cap. The window belongs to one query at a
     * time — a new query starts a new session — because the host's search
     * field drives one active query, and cross-query memory would only
     * starve a legitimately repeated result.
     */
    private fun freshInSearch(
        query: String,
        wallpapers: List<Wallpaper>,
    ): List<Wallpaper> {
        if (wallpapers.isEmpty()) return wallpapers
        synchronized(lock) {
            val key = query.trim().lowercase()
            if (searchSession != key) {
                searchSession = key
                searchServedIds.clear()
            }
            val fresh = wallpapers.filter { it.id !in searchServedIds }
            fresh.forEach { searchServedIds.add(it.id) }
            while (searchServedIds.size > SEARCH_SERVED_LIMIT) {
                searchServedIds.removeFirst()
            }
            return fresh
        }
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper. The thumbnail is the site's own grid
     * image — every card carries it — and the full URL is the constructed
     * download token at 4K: a size the site itself offers for every
     * wallpaper, so the viewer, apply and save flows all stream a
     * full-quality file straight from the grid. Titles naming a phone
     * wallpaper (the site's own iPhone/mobile naming convention) select
     * the portrait 4K size instead; [details] upgrades the record to the
     * TRUE original either way.
     *
     * Dimensions are deliberately absent: the listing's `width`/`height`
     * attributes are the site's uniform 320x240 card crop, identical on
     * every cell and true of no wallpaper — a value that wrong is worse
     * than no value, and the app renders null dimensions as "—". Tags
     * derive from the title's words so the detail screen's "More like
     * this" row has a query to work with: the site's search matches them.
     */
    private fun gridWallpaper(item: WallpapersDenParser.GridItem): Wallpaper {
        val portrait = PORTRAIT_HINT.containsMatchIn("${item.title} ${item.slug}")
        val width = if (portrait) PORTRAIT_RENDER_WIDTH else LANDSCAPE_RENDER_WIDTH
        val height = if (portrait) PORTRAIT_RENDER_HEIGHT else LANDSCAPE_RENDER_HEIGHT
        return Wallpaper(
            id = item.slug,
            providerId = ID,
            thumbUrl = thumbUrl(item.slug, item.numericId),
            fullUrl = downloadUrl(item.slug, item.numericId, width, height),
            title = item.title.ifBlank { null },
            tags = tagsFromTitle(item.title),
        )
    }

    /** The site's grid thumbnail: `ws-{slug}_{id}.jpg` on the image CDN. */
    private fun thumbUrl(
        slug: String,
        numericId: String,
    ): String = "https://$IMAGE_HOST/image/ws-${slug}_$numericId.jpg"

    /** The constructed original: the site's own download URL scheme at an offered size. */
    private fun downloadUrl(
        slug: String,
        numericId: String,
        width: Int,
        height: Int,
    ): String = "https://$IMAGE_HOST/image/download/${slug}_${WallpapersDenParser.encodeDownloadToken(numericId, width, height)}.jpg"

    /**
     * Title words as tags: lowercase, three characters or more, common
     * words and resolution labels dropped. A title without a usable word
     * yields no tags — honest emptiness over noise.
     */
    private fun tagsFromTitle(title: String): List<String> =
        title
            .lowercase()
            .split(Regex("""[^a-z0-9']+"""))
            .filter { it.length >= MIN_TAG_LENGTH && it !in TITLE_STOP_WORDS }
            .distinct()
            .take(MAX_TAGS)

    /** Harvests seen tags for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTagsIn(wallpapers: List<Wallpaper>): List<Wallpaper> {
        if (wallpapers.isEmpty()) return wallpapers
        synchronized(lock) {
            wallpapers.flatMap { it.tags }.forEach { tag ->
                if (tagPool.size < TAG_POOL_LIMIT) tagPool.add(tag)
            }
        }
        return wallpapers
    }

    // ------------------------------------------------------------- plumbing

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url)
            ?: error("configure() was not called")

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("wallpapersden answered HTTP $statusCode")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val ID = "cloudimage.wallpapersden"
        const val BASE_URL = "https://wallpapersden.com"

        /** The image CDN: a sibling host of the site itself. */
        const val IMAGE_HOST = "images.wallpapersden.com"

        /** The ranked, time-ordered and random feeds. */
        const val POPULAR_PATH = "/popular-wallpapers"
        const val LATEST_PATH = "/latest-wallpapers"
        const val RANDOM_PATH = "/random-wallpapers"

        /**
         * The host `category` vocabulary this provider can express: `anime`
         * natively, `people` through the site's Celebrities category — the
         * honest nearest expression of a value the site files no category
         * under. `general` needs no mapping: it is the default feed.
         */
        val hostCategoryPath =
            mapOf(
                "anime" to "/anime-wallpapers",
                "people" to "/celebrities-wallpapers",
            )

        /**
         * Exact search terms that walk the site's own listings instead of
         * its title matcher — the section presets plus the taxonomy a
         * plain query most plausibly names. Every path is the site's own
         * sidebar menu (verified live), so a matched term rides exactly
         * what the site's browse UI would show.
         */
        val termPaths =
            mapOf(
                "girls" to "/girls-wallpapers",
                "cars" to "/cars-wallpapers",
                "games" to "/games-wallpapers",
                "movies" to "/movies-wallpapers",
                "nature" to "/nature-wallpapers",
                "superheroes" to "/superheroes-wallpapers",
                "abstract" to "/abstract-wallpapers",
                "space" to "/space-wallpapers",
                "city" to "/city-wallpapers",
                "animals" to "/animals-wallpapers",
                "sports" to "/sports-wallpapers",
                "fantasy" to "/fantasy-wallpapers",
                "minimalist" to "/minimalist-wallpapers",
                "celebrities" to "/celebrities-wallpapers",
                "anime" to "/anime-wallpapers",
                "tv series" to "/tv-series-wallpapers",
                "music" to "/music-wallpapers",
                "food" to "/food-wallpapers",
                "flowers" to "/flowers-wallpapers",
                "macro" to "/macro-wallpapers",
                "holidays" to "/holidays-wallpapers",
                "brands" to "/brands-wallpapers",
                "quotes" to "/inspirational-quotes-wallpapers",
                "3d" to "/3d-wallpapers",
                "hi tech" to "/hi-tech-wallpapers",
                "4k" to "/tag/4k-wallpapers",
                "hd" to "/tag/hd-wallpapers",
            )

        /**
         * The render sizes the grid's download URLs are constructed at:
         * both are sizes the site itself offers for every wallpaper (its
         * standard resolution menu carries them for landscape and
         * portrait originals alike), full quality on every device.
         */
        const val LANDSCAPE_RENDER_WIDTH = 3840
        const val LANDSCAPE_RENDER_HEIGHT = 2160
        const val PORTRAIT_RENDER_WIDTH = 2160
        const val PORTRAIT_RENDER_HEIGHT = 3840

        /** The site's own naming convention for phone wallpapers. */
        val PORTRAIT_HINT = Regex("""(?i)\b(iphone|mobile|phone)\b""")

        /** Deep-pagination cap: a hundred pages, twenty items each. */
        const val MAX_PAGES = 100

        /** Title-derived tags: word floor, noise list, per-item and pool caps. */
        const val MIN_TAG_LENGTH = 3
        const val MAX_TAGS = 6
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /** Served-id window guarding the search feed's page seams. */
        const val SEARCH_SERVED_LIMIT = 400

        /** Words never worth suggesting or searching as tags. */
        val TITLE_STOP_WORDS =
            setOf(
                "the",
                "and",
                "for",
                "with",
                "from",
                "part",
                "new",
                "one",
                "two",
                "wallpaper",
                "wallpapers",
                "wall",
                "art",
                "logo",
                "text",
                "poster",
                "hd",
                "qhd",
                "fhd",
                "uhd",
                "2k",
                "4k",
                "5k",
                "8k",
                "1080p",
                "1440p",
                "iphone",
                "android",
                "mobile",
                "phone",
                "desktop",
                "laptop",
            )

        /** One lock over every mutable member; sections load in parallel. */
        val lock = Any()
    }

    private val tagPool = LinkedHashSet<String>()

    /** The active search session: its query and the ids it already served. */
    private var searchSession: String? = null
    private val searchServedIds = ArrayDeque<String>()
}
