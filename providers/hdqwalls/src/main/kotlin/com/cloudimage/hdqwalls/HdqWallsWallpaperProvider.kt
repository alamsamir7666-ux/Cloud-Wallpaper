package com.cloudimage.hdqwalls

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
 * HDQWalls (https://hdqwalls.com) as a Cloudimage provider package — the
 * second scraping source in the repository set: no API, no key, just the
 * site's own server-rendered pages read the way its browser UI does.
 *
 * ## Site model
 *
 * The site is listing-first and refreshingly uniform: popular
 * (`/popular-wallpapers`), latest (`/latest-wallpapers`), every category
 * (`/category/{slug}-wallpapers`) and search (`/search?q=`) serve the SAME
 * grid markup, eighteen items per page, with REAL pagination
 * (`/page/N`, `?page=N`) — a pagination bar whose `Next »` link is the
 * site's own "more exists" signal, absent on the last page. Thumbnails are
 * real JPEGs (602x339, the site's uniform card ratio) served from
 * `images.hdqwalls.com/wallpapers/bthumb/{file}.jpg`, and every one
 * discloses its multi-megabyte original by directory alone — the same
 * filename back under `/wallpapers/`. Wallpaper pages carry the file's
 * TRUE resolution, an author credit, a size label and the site's own tag
 * row.
 *
 * ## How the contract maps onto it
 *
 * - [popular] rides the host's filter vocabulary: a recognized `category`
 *   walks that category's listing (`anime` natively; `people` through the
 *   site's Celebrities category — the honest nearest expression of a
 *   vocabulary value the site has no equivalent of), `sorting=date` walks
 *   the latest feed, `sorting=random` serves one batch of the site's own
 *   random page, and everything else lands on the site's popular ranking —
 *   the default feed the browse tab shows first.
 * - [search] is one request per page: the site's search answers with
 *   wallpapers directly, paginated as deep as the site itself goes, and
 *   its tag matching means a category name searched returns that
 *   category's content (verified live: `cars` — 12,303 results).
 * - [sections] offers fourteen shelves: Popular, Latest, Anime (a host
 *   `category` preset), Celebrities (the `people` one), and tag-style
 *   `query` presets for Girls, Cars, Superheroes, Games, Movies, Nature,
 *   Abstract, Animals, Bikes and Sports — each rides the site's own
 *   search, which those terms address precisely.
 * - [details] fetches `/{id}` and reads the blockquote's `Original
 *   Resolution` line: the definitive URL, the file's true dimensions, the
 *   author, the download size and the site's own tags.
 * - [random] serves the site's `/random-wallpapers` batch as-is.
 *
 * ## Politeness
 *
 * The site's robots.txt excludes only `/ajax?s=` (the autocomplete this
 * provider never calls) and `/addauthor`. Every path used here — the
 * listings, the categories, the search, the wallpaper pages — is left
 * open. Fetches happen on explicit user actions, one request per page,
 * with a deep-pagination cap; a browser tab on the same pages costs the
 * same or more. The site blocks known-bot User-Agents but serves the
 * app's own identification (verified live against every path this
 * provider touches), so no browser impersonation is needed.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; the only mutable state is
 * the tag pool feeding [suggestTags] — a bonus, never a dependency. A
 * fresh instance answers identically, at worst without suggestions. The
 * pool is guarded by one lock; sections load in parallel on the host side.
 *
 * ## Dimensions
 *
 * Grid items carry NONE, on purpose. The site's listing markup hard-codes
 * `width='602' height='339'` — its uniform card crop — on every single
 * cell, whatever the file's true size, so those attributes describe the
 * THUMBNAIL, never the wallpaper (a 3840x2159 original and a 1080x1920
 * portrait one both arrive labeled 602x339). Publishing them made the
 * app's info sheet claim "602x339" for every HDQWalls wallpaper — the bug
 * this version fixes. [details] is where the TRUE resolution arrives:
 * each wallpaper page's `Original Resolution` line, the only place the
 * site publishes it.
 */
class HdqWallsWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "HDQWalls",
            versionName = "1.0.1",
            author = "Cloudimage",
            description = "HD, 4K, 5K and 8K wallpapers from hdqwalls.com - scraped, keyless.",
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
     * One request per page: the site's search answers with wallpapers
     * directly — no album indirection — paginated as deep as the site
     * itself goes. Its tag matching is what the query-preset shelves ride:
     * a category name searched returns that category's content. A blank
     * query (the contract's escape hatch) lands on the latest feed's
     * first page, the same default the blank popular feed would show.
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
            listingPage("/search?q=${encode(query)}", page)
        }

    /**
     * Suggestions come ONLY from tags and title words this instance has
     * already seen in fetched pages — no keystroke-driven scraping of the
     * site's autocomplete (its `/ajax?s=` endpoint is the one path the
     * site's robots.txt excludes). A fresh instance answers nothing,
     * which the host treats as "no suggestions" rather than an error.
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
     * the site's search addresses each precisely. Cheap and offline, as
     * the contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = "popular", title = "Popular"),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("category" to "anime")),
            HomeSection(id = "girls", title = "Girls", filters = Filters.of("query" to "girls")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "cars")),
            HomeSection(id = "superheroes", title = "Superheroes", filters = Filters.of("query" to "superheroes")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "games")),
            HomeSection(id = "movies", title = "Movies", filters = Filters.of("query" to "movies")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "celebrities", title = "Celebrities", filters = Filters.of("category" to "people")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
            HomeSection(id = "bikes", title = "Bikes", filters = Filters.of("query" to "bikes")),
            HomeSection(id = "sports", title = "Sports", filters = Filters.of("query" to "sports")),
        )

    /**
     * The definitive record for an id: the page's blockquote carries the
     * original's exact URL and TRUE dimensions, the author credit, the
     * download size and the site's own tags. Transport failures propagate
     * untouched, per the facade contract; a non-2xx answer (deleted
     * wallpaper, redesigned page) is a source failure, reported as such —
     * the page slug alone discloses no image URL to degrade to (the
     * image filename carries a random suffix), and the host's viewer
     * already holds the grid item's own URLs.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/${encode(id)}")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record = HdqWallsParser.parseDetail(response.bodyText) ?: error("unrecognized wallpaper page for '$id'")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        thumbUrl = HdqWallsParser.toThumbUrl(record.originalUrl) ?: record.originalUrl,
                        fullUrl = record.originalUrl,
                        title = record.title,
                        width = record.width,
                        height = record.height,
                        tags = record.tags.take(MAX_TAGS),
                    ),
                author = record.author,
                resolution =
                    if (record.width != null && record.height != null) "${record.width}x${record.height}" else null,
                fileSizeBytes = record.fileSizeBytes,
                sourceUrl = "$BASE_URL/$id",
            )
        }

    /** The site's own random page: one fresh batch of eighteen, no stable page 2. */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { randomBatch() }

    // ---------------------------------------------------------------- feed

    /**
     * Any listing page: fetch, parse the grid, read the pagination bar's
     * `Next »` link for what follows. The link is the site's own
     * "more exists" signal — past-the-end page requests clamp to the last
     * page's content on this site, so a blindly incremented cursor would
     * serve the final batch forever; the bar is the stopper. A
     * [MAX_PAGES] cap bounds the deepest scroll the same politeness way.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val url =
            if (path.startsWith("/search")) {
                "$BASE_URL$path${if (page > 1) "&page=$page" else ""}"
            } else if (page > 1) {
                "$BASE_URL$path/page/$page"
            } else {
                "$BASE_URL$path"
            }
        val response = get(url)
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val wallpapers =
            HdqWallsParser.parseGrid(response.bodyText).map(::gridWallpaper).let(::rememberTagsIn)
        return Page(wallpapers, nextPage = HdqWallsParser.parseNextPage(response.bodyText))
    }

    /** The random batch — same grid, no pagination signal worth reading. */
    private suspend fun randomBatch(): List<Wallpaper> {
        val response = get("$BASE_URL$RANDOM_PATH")
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return HdqWallsParser.parseGrid(response.bodyText).map(::gridWallpaper).let(::rememberTagsIn)
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the thumbnail is a real JPEG every
     * Android decodes (unlike WallpaperCave's AVIF trap), so the grid
     * carries it in [Wallpaper.thumbUrl] and the disclosed original — the
     * same filename back under `/wallpapers/` — in [Wallpaper.fullUrl].
     * Dimensions are deliberately absent: the listing's `width`/`height`
     * attributes are the site's uniform 602x339 card crop, identical on
     * every cell and true of no wallpaper — a value that wrong is worse
     * than no value, and the app renders null dimensions as "—". Tags
     * derive from the title's words so the detail screen's "More like
     * this" row has a query to work with: the site's search matches
     * them, returning the same wallpaper family.
     */
    private fun gridWallpaper(item: HdqWallsParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.id,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.originalUrl,
            title = item.title.ifBlank { null },
            tags = tagsFromTitle(item.title),
        )

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

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("hdqwalls answered HTTP $statusCode")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val ID = "cloudimage.hdqwalls"
        const val BASE_URL = "https://hdqwalls.com"

        /** The ranked and time-ordered feeds; the random page behind [random]. */
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
                "anime" to "/category/anime-wallpapers",
                "people" to "/category/celebrities-wallpapers",
            )

        /** Deep-pagination cap: a hundred pages, eighteen items each. */
        const val MAX_PAGES = 100

        /** Title-derived tags: word floor, noise list, per-item and pool caps. */
        const val MIN_TAG_LENGTH = 3
        const val MAX_TAGS = 6
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

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
            )

        /** One lock over the tag pool; sections load in parallel. */
        val lock = Any()
    }

    private val tagPool = LinkedHashSet<String>()
}
