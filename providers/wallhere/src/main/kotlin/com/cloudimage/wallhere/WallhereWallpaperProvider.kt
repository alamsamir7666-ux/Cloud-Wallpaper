package com.cloudimage.wallhere

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
 * WallHere (https://wallhere.com) as a Cloudimage provider package — a
 * keyless scraper over the site's server-rendered pages, the same way
 * every scraping source in this set works.
 *
 * ## Site model
 *
 * WallHere is a tag-first, multi-language wallpaper catalogue: one flat
 * masonry grid renders EVERY listing — the popular and latest orders of
 * `/en/wallpapers`, the search the top form submits (`/en/wallpapers?q=`),
 * the tag pages (`/en/tag/{id}`) and `/en/random` — twenty-four items per
 * page, paginated with a classic bar whose numbered links carry the
 * `?page=N` dialect and whose `<li class="active">` marks the current
 * page. The grid's `current-item-photo` anchors disclose each
 * wallpaper's numeric id, its tag row (the img's own `title` attribute)
 * and its CDN thumbnail.
 *
 * ## The image URL families — and the original
 *
 * The image CDN (`c.wallhere.com`) serves three derivable suffixes per
 * file: `!s1` (1x thumb), `!s2` (2x thumb) and `!d` (the display
 * original, ~1200px) — the same path with the suffix swapped, so a grid
 * cell's thumbnail discloses a working full image. The TRUE
 * full-resolution original is a DIFFERENT URL the site mints onto the
 * wallpaper page's download anchor (`https://get.wallhere.com/photo/
 * {slug}-{id}.jpg`) — not derivable from any CDN path, only stated by
 * `/en/wallpaper/{id}`. Listings therefore carry the display original
 * as [Wallpaper.fullUrl] (real, publicly served, honest ~1200px), and
 * [details] upgrades the record to the true original when the page
 * states it.
 *
 * ## Content rating
 *
 * The site mixes adult-tagged wallpapers into its DEFAULT feeds — no
 * purity toggle exists for anonymous requests — so the provider
 * declares SKETCHY and classifies every item by the site's OWN tag
 * vocabulary: an item whose tag row carries an adult word is NSFW, and
 * the host's rating switch filters it exactly as it does Wallhaven's
 * purity labels. Everything else is SFW.
 *
 * ## Cloudflare
 *
 * The zone Cloudflare-challenges datacenter addresses on every document
 * path, while the image CDN serves every client unchallenged. The host's
 * HTTP facade already owns that problem (per-host clearances, the
 * WebView document lane) — this plugin sends its requests bare and
 * parses what comes back.
 *
 * ## Contract mapping
 *
 * - [popular] rides the host vocabulary: a recognized `category` walks
 *   that term's own search (the site's tag search addresses `anime` and
 *   `people`-style content precisely), `sorting=date` walks the latest
 *   order, `sorting=random` serves one fresh `/en/random` batch (the
 *   page reshuffles on every load — there is no stable page 2), and
 *   everything else lands on the popular order;
 * - [search] rides `/en/wallpapers?q=` — the URL the site's own top
 *   form submits — one request per page, as deep as the site's bar goes;
 * - [sections] offers the two ranked shelves plus tag-style `query`
 *   presets the site's search addresses precisely;
 * - [details] fetches `/en/wallpaper/{id}` and reads the definitive
 *   record: the true original's URL and resolution, the display
 *   fallback, the uploader and the page's fuller tag row;
 * - [suggestTags] serves tags harvested from grids already seen — the
 *   cells' own tag rows, never a third-party suggest service;
 * - [random] serves the site's own `/en/random` batch as-is — a real
 *   random feed, advertised with [Capability.RANDOM].
 *
 * ## Politeness
 *
 * The site's robots.txt excludes only its login and signup paths; every
 * path used here — the listings, the search, the tag pages, the random
 * page, the wallpaper pages — is left open. Fetches happen on explicit
 * user actions, one request per page, with a deep-pagination cap.
 */
class WallhereWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "WallHere",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "Free HD wallpapers from wallhere.com - scraped, keyless.",
            // The site mixes adult-tagged content into its default feeds
            // with no anonymous purity toggle; SKETCHY is the honest
            // floor, and every item is classified by its own tag row so
            // the host's rating switch filters precisely.
            contentRating = ContentRating.SKETCHY,
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
     * `category` walks that term's own search, `sorting=date` walks the
     * latest order, `sorting=random` serves one fresh random batch (the
     * site's random page reshuffles on every load), and everything else
     * lands on the popular order — the feed the browse tab shows first.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val category = filters.valuesFor("category").firstNotNullOfOrNull(hostCategoryQuery::get)
            when {
                category != null -> listingPage(searchPath(category), page)
                filters.isSelected("sorting", "date") -> listingPage(LATEST_PATH, page)
                filters.isSelected("sorting", "random") -> Page(randomBatch(), nextPage = null)
                else -> listingPage(POPULAR_PATH, page)
            }
        }

    /**
     * The search the site's own top form submits: `/en/wallpapers?q=`,
     * one request per page. A blank query (the contract's escape hatch)
     * lands on the latest order's first page, the same default the blank
     * popular feed would show.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                listingPage(LATEST_PATH, 1)
            } else {
                listingPage(searchPath(query.trim()), page)
            }
        }

    /**
     * Suggestions come ONLY from tag rows this instance has already seen
     * in fetched pages — the cells' own comma-separated rows. A fresh
     * instance answers nothing, which the host renders as "no
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
     * The home shelves: the two ranked feeds first (Popular is the
     * default row the host's merged home picks), then tag-style `query`
     * presets the site's search addresses precisely. Cheap and offline,
     * as the contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = HomeSection.DEFAULT_ID, title = "Popular"),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "landscape", title = "Landscape", filters = Filters.of("query" to "landscape")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("query" to "anime")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "cars")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "games")),
            HomeSection(id = "movies", title = "Movies", filters = Filters.of("query" to "movies")),
            HomeSection(id = "space", title = "Space", filters = Filters.of("query" to "space")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
            HomeSection(id = "city", title = "City", filters = Filters.of("query" to "city")),
        )

    /**
     * The definitive record for an id: the wallpaper page states the
     * TRUE original's URL (the `get.wallhere.com` download anchor) and
     * its resolution (the meta description's tail), the display image's
     * own dimensions when the description omits them, the uploader, and
     * a fuller tag row than any grid cell. The thumb stays BLANK on
     * purpose — the grid item's own thumbnail is better than anything
     * this page states, and the host keeps it. When the download anchor
     * is missing, the display original stands in as the file URL: still
     * a real, servable file, honestly lower-resolution. A page that
     * states neither image anchor is the source failure it is; the host
     * keeps the grid item's values.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/en/wallpaper/${encode(id)}")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record =
                WallhereParser.parseDetail(response.bodyText)
                    ?: error("unrecognized wallpaper page for '$id'")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        // Blank on purpose: the grid item's thumbnail is
                        // the better thumb, and the host keeps it.
                        thumbUrl = "",
                        fullUrl = record.originalUrl,
                        title = null,
                        width = record.width,
                        height = record.height,
                        tags = record.tags.take(MAX_TAGS),
                        contentRating = ratingFor(record.tags),
                    ),
                author = record.author,
                resolution =
                    if (record.width != null && record.height != null) "${record.width}x${record.height}" else null,
                sourceUrl = "$BASE_URL/en/wallpaper/$id",
            )
        }

    /**
     * The site's own random page: one fresh batch of twenty-four, served
     * as-is. The page reshuffles on every load — there is no page 2.
     */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { randomBatch() }

    // ---------------------------------------------------------------- feed

    /**
     * Any listing page: fetch, parse the grid, read the pagination bar.
     * The bar is the site's own "more exists" signal — `nextPage` exists
     * only when the bar advertises a page beyond the one requested. And
     * when the bar marks a CURRENT page that disagrees with the page
     * requested (a parameter the site ignored), the walk stops instead
     * of looping the first page forever. A [MAX_PAGES] cap bounds the
     * deepest scroll the same politeness way.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val response = get("$BASE_URL${pagedPath(path, page)}")
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val items = WallhereParser.parseGrid(response.bodyText)
        rememberTagsFrom(items)
        val bar = WallhereParser.parsePagination(response.bodyText)
        if (page > 1 && bar.currentPage != null && bar.currentPage != page) {
            // The site served a different page than requested — the deep
            // URL shape did not take. Serve nothing further rather than
            // looping page one forever.
            return Page(items.map(::gridWallpaper), nextPage = null)
        }
        val nextPage = if (bar.maxPage > page) page + 1 else null
        return Page(items.map(::gridWallpaper), nextPage = nextPage)
    }

    /** The random batch — same grid, no pagination signal worth reading. */
    private suspend fun randomBatch(): List<Wallpaper> {
        val response = get("$BASE_URL$RANDOM_PATH")
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val items = WallhereParser.parseGrid(response.bodyText)
        rememberTagsFrom(items)
        return items.map(::gridWallpaper)
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the 1x thumbnail as [Wallpaper.thumbUrl]
     * and the display original — the thumbnail's suffix swapped to `!d`,
     * a real publicly served file — as [Wallpaper.fullUrl]. Dimensions
     * are deliberately absent: the listing's `width`/`height` attributes
     * are the masonry BOX proportions, true of no wallpaper's pixels — a
     * value that wrong is worse than no value, and the app renders null
     * dimensions as "—". [details] is where the TRUE resolution arrives.
     * The item's rating is classified from its own tag row so the host's
     * switch filters the adult content this site mixes into its feeds.
     */
    private fun gridWallpaper(item: WallhereParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.id,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.fullUrl,
            title = null,
            tags = item.tags.take(MAX_TAGS),
            contentRating = ratingFor(item.tags),
        )

    /**
     * An item's rating, from the site's own vocabulary: NSFW when the
     * tag row carries an adult word, SFW otherwise. The host's rating
     * switch does the filtering; this classification is what makes it
     * honest for a source with no server-side purity toggle.
     */
    private fun ratingFor(tags: List<String>): ContentRating =
        if (tags.any { it.lowercase() in WallhereParser.ADULT_TAG_WORDS }) {
            ContentRating.NSFW
        } else {
            ContentRating.SFW
        }

    /** Harvests seen tags for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTagsFrom(items: List<WallhereParser.GridItem>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            items.flatMap { it.tags }.forEach { tag ->
                if (tagPool.size < TAG_POOL_LIMIT) tagPool.add(tag)
            }
        }
    }

    // ------------------------------------------------------------- plumbing

    /** The search listing path for [query], the site's own form encoding. */
    private fun searchPath(query: String): String = "/en/wallpapers?q=${encode(query)}"

    /**
     * The URL of page [page] of a listing: the path as given on page 1,
     * `&page=N` appended when the path already carries a query, `?page=N`
     * when it does not — the dialect the site's own bar links use.
     */
    private fun pagedPath(
        path: String,
        page: Int,
    ): String =
        when {
            page <= 1 -> path
            path.contains('?') -> "$path&page=$page"
            else -> "$path?page=$page"
        }

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url)
            ?: error("configure() was not called")

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("wallhere answered HTTP $statusCode")

    /** Form-style encoding: spaces as `+`, exactly how the site's own form submits. */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val ID = "cloudimage.wallhere"
        const val BASE_URL = "https://wallhere.com"

        /** The ranked feeds; the random page behind [random]. */
        const val POPULAR_PATH = "/en/wallpapers?order=popular"
        const val LATEST_PATH = "/en/wallpapers?order=latest"
        const val RANDOM_PATH = "/en/random"

        /**
         * The host `category` vocabulary this provider can express as
         * the site's own tag searches: `anime` natively (one of the
         * catalogue's deepest tag families), `people` through the
         * `portrait` tag — the honest nearest expression of a value the
         * site files no single category under. `general` needs no
         * mapping: it is the default feed.
         */
        val hostCategoryQuery =
            mapOf(
                "anime" to "anime",
                "people" to "portrait",
            )

        /** Deep-pagination cap: a hundred pages, twenty-four items each. */
        const val MAX_PAGES = 100

        /** Tag caps and the suggestion list size. */
        const val MAX_TAGS = 6
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /** One lock over the tag pool. */
        val lock = Any()
    }

    private val tagPool = LinkedHashSet<String>()
}
