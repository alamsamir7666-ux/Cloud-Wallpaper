package com.cloudimage.wallpapercave

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpException
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider
import java.net.URLEncoder

/**
 * WallpaperCave (https://wallpapercave.com) as a Cloudimage provider package
 * — the first scraping source in the official set: no API, no key, just the
 * site's own server-rendered pages read the way its browser UI does.
 *
 * ## Site model
 *
 * The site is album-first: content lives in topic albums (`/{slug}`), each
 * holding all of its wallpapers on a single page; `/latest-uploads` is the
 * community upload feed; `/search?q=` answers with ALBUMS, never with single
 * wallpapers. Original files are served from two deterministic directories —
 * `/wp/{id}.*` for site wallpapers, `/uwp/{id}.*` for user uploads — and
 * every thumbnail path discloses its original (same filename, family
 * directory), so grid items already carry a working download URL. The
 * thumbnail directories themselves are served as AVIF whatever the client
 * negotiates — undecodable below Android 12 — so items carry the ORIGINAL
 * in both fields: the one image URL every device renders.
 *
 * ## How the contract maps onto it
 *
 * - [popular] is the latest feed: page 1 is `/latest-uploads`, page 2 is the
 *   one batch the site's load-more endpoint serves to a plain GET. A
 *   `category` filter walks that category's feed: the curated topic first
 *   (the site's own best-of page for it), then the category's album stream
 *   (`/categories/anime-manga`, `/categories/people`) merged three albums
 *   per page — the same machinery search uses, so a category row scrolls as
 *   deep as search does instead of ending at one topic.
 * - [sections] offers the site's shelves as tabs: the latest feed plus
 *   twelve categories (anime, girls, cars, people, games, movies, nature,
 *   space, animals, bikes, sports, abstract). Anime and people ride the
 *   host's own `category` vocabulary; the rest are tag-style `query`
 *   presets the host routes through [search], where an exact term walks its
 *   category feed the same way — the site itself answers such terms with
 *   its category pages (`/search?q=cars` redirects to the cars one), so
 *   this is its own behavior with a better first page.
 * - [search] runs the CloudStream two-step for every other query: albums
 *   from `/search`, then the first few albums' topic pages fetched one by
 *   one and merged, three albums per page, ten pages deep at most. One user
 *   query therefore costs at most 1 + 3 requests per page — a browser tab
 *   on the site's own search results costs more.
 * - [details] fetches `/w/{id}` and reads `img.wpimg`: the definitive URL
 *   (a grid's extension-derived guess is corrected here), the title, and
 *   aspect-true dimensions.
 *
 * ## Politeness
 *
 * The site's robots.txt excludes `/search`, `/w/` and `/download/` from
 * crawling. This provider never crawls: it fetches on explicit user actions
 * (open feed, type query, open wallpaper), sequentially, in small bounded
 * batches, with deep-pagination caps — per-user browser-equivalent traffic,
 * the same judgment every CloudStream provider makes. Originals come from
 * `/wp/` and `/uwp/`, and the category stream rides `/categories/{slug}` and
 * topic pages — all paths the site leaves open. Should the site ever turn
 * on Cloudflare challenges, the host's WebView clearance machinery already
 * sits between this plugin and the network.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; correctness here never depends
 * on the caches below — they only deduplicate the latest feed's page
 * boundary and spare `/search` a re-download per pagination step. A fresh
 * instance answers identically, at worst with one repeated request or a
 * boundary duplicate. All mutable state is guarded by one lock; sections
 * load in parallel on the host side.
 *
 * ## Dimensions
 *
 * The site publishes true file dimensions only on topic pages; grid
 * thumbnails are scaled proportionally (ratio-true, values are the
 * thumbnail's), and wallpaper pages carry a 700px-wide display size. Items
 * report exactly what the site published wherever it came from — the
 * masonry grid needs only the ratio, which is exact in all three cases.
 */
class WallpaperCaveWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "WallpaperCave",
            versionName = "1.2.0",
            author = "Cloudimage",
            description = "Wallpapers from wallpapercave.com - scraped, keyless.",
            // The site's upload rules only allow SFW content and it carries no
            // per-item rating metadata; SFW is both the site's own claim and
            // the honest floor. The host's rating switch still applies on top.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.LATEST, Capability.SEARCH, Capability.FILTERS, Capability.TAGS)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    /**
     * The default feed. No category selected: the latest uploads (two pages
     * — the site's load-more endpoint only paginates for POST, so a GET-only
     * plugin sees the first two batches). A recognized `category` value —
     * the host vocabulary offers `anime` and `people` — walks that
     * category's feed: the curated topic first, then the category's album
     * stream, three albums per page.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val category = filters.valuesFor("category").firstOrNull()?.let(categoryByTerm::get)
            if (category != null) categoryFeed(category, page) else latestPage(page)
        }

    /**
     * Album-first search: `/search` answers with topic albums, so the query
     * runs the CloudStream two-step — take [ALBUMS_PER_SEARCH_PAGE] albums,
     * fetch their topic pages one by one, merge the wallpapers. A failing
     * album is skipped (its wallpapers, not the whole page); the deep-
     * pagination cap keeps a long scroll from walking the entire result set.
     *
     * A query naming a browse tab exactly walks that tab's category feed
     * instead — the tag-style sections arrive here as their `query` presets,
     * and the site itself answers such terms with its category pages
     * (`/search?q=cars` redirects to the cars one), so this rides the site's
     * own behavior with a curated first page. Exact match only; anything
     * longer is a query like any other.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (query.isBlank()) {
                return@runCatching latestPage(1)
            }
            categoryByTerm[query.trim().lowercase()]?.let { category ->
                return@runCatching categoryFeed(category, page)
            }
            if (page < 1 || page > MAX_SEARCH_PAGES) {
                return@runCatching Page(emptyList(), nextPage = null)
            }
            val albums = albumsFor(query)
            val batch = albums.drop((page - 1) * ALBUMS_PER_SEARCH_PAGE).take(ALBUMS_PER_SEARCH_PAGE)
            if (batch.isEmpty()) {
                return@runCatching Page(emptyList(), nextPage = null)
            }
            val wallpapers =
                batch
                    .flatMap { album ->
                        runCatching { wallpapersOfTopic(album.slug) }.getOrDefault(emptyList())
                    }.distinctBy { it.id }
            val moreAlbumsRemain = page * ALBUMS_PER_SEARCH_PAGE < albums.size
            Page(wallpapers, nextPage = if (moreAlbumsRemain) page + 1 else null)
        }

    /**
     * Suggestions come ONLY from tags this instance has already seen in
     * fetched pages — no keystroke-driven scraping of the site's search. The
     * honest per-contract alternative (the site's own tag endpoint) does not
     * exist, so an uncached or fresh instance answers nothing, which the
     * host treats as "no suggestions" rather than an error.
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
     * The site's shelves as tabs, one feed per browse category: the latest
     * uploads first, then the categories in bar order. Anime and People ride
     * the host's own `category` vocabulary; every other shelf is a tag-style
     * `query` preset the host routes through [search] with that term, where
     * it walks the category feed like any other. Cheap and offline, as the
     * contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(HomeSection(id = "latest", title = "Latest Uploads")) +
            browseTabs.map { category ->
                HomeSection(id = category.term, title = category.title, filters = category.sectionFilters())
            }

    /** A tab's preset: the host's own value where it has one, a query preset otherwise. */
    private fun CategorySpec.sectionFilters(): Filters =
        if (asHostCategory) {
            Filters.of("category" to term)
        } else {
            Filters.of("query" to term)
        }

    /**
     * The definitive record for an id. The `/w/{id}` page's `img.wpimg`
     * carries the original's exact URL — correcting whatever extension a
     * grid-derived guess made — plus title and aspect-true dimensions.
     *
     * The page is enrichment, not a gate: the id alone already discloses a
     * working image URL, so ANY page failure (deleted, redesigned, non-2xx)
     * degrades to that derived record instead of killing the viewer — the
     * image itself is served from a different path and usually survives.
     * Transport failures are the one exception: they propagate untouched,
     * per the facade contract, so the host can report its own typed errors.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/w/${encode(id)}")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val image = WallpaperCaveParser.parseDetailImage(response.bodyText)
            if (image != null) {
                WallpaperDetails(
                    wallpaper =
                        Wallpaper(
                            id = image.id.ifBlank { id },
                            providerId = ID,
                            thumbUrl = image.src,
                            fullUrl = image.src,
                            title = image.title.ifBlank { null },
                            width = image.width,
                            height = image.height,
                        ),
                    sourceUrl = "$BASE_URL/w/$id",
                )
            } else {
                derivedDetails(id)
            }
        }.recoverCatching { failure ->
            if (failure is ProviderHttpException) throw failure
            derivedDetails(id)
        }

    /**
     * No random endpoint exists on the site; the honest answer is the
     * newest feed. RANDOM is deliberately not declared, so the host never
     * asks — this exists to keep the contract total.
     */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { latestPage(1).wallpapers }

    // ---------------------------------------------------------------- feed

    /** The latest uploads: page 1 from the feed, page 2 from load-more's GET batch. */
    private suspend fun latestPage(page: Int): Page {
        if (page < 1) return Page(emptyList(), nextPage = null)
        if (page == 1) {
            val wallpapers =
                fetchAndParse(WallpaperCaveParser::parseGridAnchors, "$BASE_URL/latest-uploads")
                    .mapNotNull(::gridWallpaper)
                    .let(::withoutRecentlyServed)
            return Page(wallpapers, nextPage = if (wallpapers.isEmpty()) null else 2)
        }
        if (page == 2) {
            // POST-only for real paging; a bare GET still answers the batch
            // that follows the first page, overlapping it by a couple of
            // items — the recent-ids set absorbs the seam.
            val wallpapers =
                fetchAndParse(WallpaperCaveParser::parseMoreLatest, "$BASE_URL/morelatest")
                    .mapNotNull(::gridWallpaper)
                    .let(::withoutRecentlyServed)
            return Page(wallpapers, nextPage = null)
        }
        return Page(emptyList(), nextPage = null)
    }

    /**
     * One category tab's feed: the curated topic first (the site's best-of
     * page for the category), then the category's album stream. A stream is
     * the site's category page (`/categories/anime-manga`,
     * `/categories/vehicles/cars`) or, for shelves the site files under no
     * category of their own, its search listing (`/search?q=girls`) — both
     * answer with the same album anchors. The feed walks that list with the
     * same merge machinery as search, three albums per page, and drops ids
     * an earlier page of this session already served. A page whose whole
     * batch was duplicates consumes the next batch instead of stranding the
     * feed — the host appends pages one by one and an empty-but-not-final
     * page would stall the carousel.
     */
    private suspend fun categoryFeed(
        category: CategorySpec,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_CATEGORY_PAGES) return Page(emptyList(), nextPage = null)
        if (page == 1) {
            // A feed restart begins a new session: forget what an earlier
            // session served so the curated topic comes back whole.
            synchronized(lock) {
                categorySeenIds.remove(category.term)
                categoryCursor.remove(category.term)
            }
            val curated = freshInCategory(category.term, wallpapersOfTopic(category.curatedTopic))
            return Page(curated, nextPage = 2)
        }
        val albums = categoryAlbums(category)
        var cursor = synchronized(lock) { categoryCursor[category.term] ?: 0 }
        val fresh = mutableListOf<Wallpaper>()
        while (fresh.isEmpty() && cursor < albums.size) {
            albums
                .drop(cursor)
                .take(ALBUMS_PER_SEARCH_PAGE)
                .forEach { album ->
                    runCatching { wallpapersOfTopic(album.slug) }
                        .getOrNull()
                        ?.let { items -> fresh += freshInCategory(category.term, items) }
                }
            cursor += ALBUMS_PER_SEARCH_PAGE
        }
        synchronized(lock) { categoryCursor[category.term] = cursor }
        val moreRemain = cursor < albums.size && page < MAX_CATEGORY_PAGES
        return Page(fresh, nextPage = if (moreRemain) page + 1 else null)
    }

    /**
     * Drops ids this category's session already served; remembers the rest,
     * oldest first out past the cap. Pages never revisit albums, so the set
     * only guards overlap between recently walked ones — past the cap a
     * duplicate slips through rather than the set growing without end.
     */
    private fun freshInCategory(
        term: String,
        wallpapers: List<Wallpaper>,
    ): List<Wallpaper> {
        if (wallpapers.isEmpty()) return wallpapers
        synchronized(lock) {
            val seen = categorySeenIds.getOrPut(term) { LinkedHashSet() }
            val fresh = wallpapers.filter { it.id !in seen }
            fresh.forEach { seen.add(it.id) }
            while (seen.size > CATEGORY_SEEN_LIMIT) {
                seen.remove(seen.first())
            }
            return fresh
        }
    }

    /** All wallpapers of one album topic — also the workhorse behind search. */
    private suspend fun wallpapersOfTopic(slug: String): List<Wallpaper> {
        val wallpapers =
            fetchAndParse(WallpaperCaveParser::parseTopicWallpapers, "$BASE_URL/$slug")
                .mapNotNull { item ->
                    val full = WallpaperCaveParser.absolute(item.originalPath)
                    Wallpaper(
                        id = item.id,
                        providerId = ID,
                        thumbUrl = full,
                        fullUrl = full,
                        title = item.title.ifBlank { null },
                        width = item.width,
                        height = item.height,
                        tags = tagsFromTitle(item.title),
                    )
                }
        rememberTags(wallpapers)
        return wallpapers
    }

    /** Search results with their album list cached briefly for pagination. */
    private suspend fun albumsFor(query: String): List<WallpaperCaveParser.Album> =
        cachedAlbums("q:${query.trim().lowercase()}") { "$BASE_URL/search?q=${encode(query)}" }

    /** The album list a category's stream walks, cached the same way. */
    private suspend fun categoryAlbums(category: CategorySpec): List<WallpaperCaveParser.Album> =
        cachedAlbums("cat:${category.term}") { "$BASE_URL${category.streamPath}" }

    /** One album list, fetched on demand and cached for one pagination session. */
    private suspend fun cachedAlbums(
        key: String,
        url: () -> String,
    ): List<WallpaperCaveParser.Album> {
        synchronized(lock) {
            albumCache.remove(key)?.let { cached ->
                if (now() - cached.at < SEARCH_CACHE_MS) return cached.albums
            }
        }
        val albums = fetchAndParse(WallpaperCaveParser::parseAlbums, url())
        synchronized(lock) {
            albumCache[key] = CachedAlbums(albums, now())
            while (albumCache.size > SEARCH_CACHE_SLOTS) {
                albumCache.remove(albumCache.keys.first())
            }
        }
        return albums
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper; items whose original path is unrecognizable
     * drop. Both fields carry the ORIGINAL: the grid's own `/uwpr/` thumbs
     * are served as AVIF whatever the client negotiates, which nothing below
     * Android 12 decodes — the disclosed original is the one URL every
     * device renders, the same choice topic items already make.
     */
    private fun gridWallpaper(item: WallpaperCaveParser.GridItem): Wallpaper? {
        val original = WallpaperCaveParser.toOriginalPath(item.thumbPath) ?: return null
        val full = WallpaperCaveParser.absolute(original)
        return Wallpaper(
            id = item.id,
            providerId = ID,
            thumbUrl = full,
            fullUrl = full,
            title = item.title.ifBlank { null },
            width = item.width,
            height = item.height,
            tags = tagsFromTitle(item.title),
        )
    }

    /**
     * Grid titles are comma-separated tag lists (`"planet, space,
     * universe"`); a phrase without commas becomes one tag so the detail
     * screen's "More like this" row has a query to work with.
     */
    private fun tagsFromTitle(title: String): List<String> {
        if (title.isBlank()) return emptyList()
        val tags =
            title
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(MAX_TAGS)
        return tags.ifEmpty { listOf(title.trim()) }
    }

    /** Best-effort details when the wallpaper page cannot be read: the id discloses the original's path. */
    private fun derivedDetails(id: String): WallpaperDetails {
        val full =
            when {
                id.startsWith("uwp") -> "$BASE_URL/uwp/$id.jpeg"
                else -> "$BASE_URL/wp/$id.webp"
            }
        return WallpaperDetails(
            wallpaper =
                Wallpaper(
                    id = id,
                    providerId = ID,
                    thumbUrl = full,
                    fullUrl = full,
                ),
            sourceUrl = "$BASE_URL/w/$id",
        )
    }

    // ------------------------------------------------------------- plumbing

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url)
            ?: error("configure() was not called")

    /** GETs [url], demands 2xx, hands the body to [parser]. */
    private suspend fun <T> fetchAndParse(
        parser: (String) -> List<T>,
        url: String,
    ): List<T> {
        val response = get(url)
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        return parser(response.bodyText)
    }

    /** Drops ids already served by an earlier latest-feed page (bounded memory). */
    private fun withoutRecentlyServed(wallpapers: List<Wallpaper>): List<Wallpaper> {
        if (wallpapers.isEmpty()) return wallpapers
        val fresh: List<Wallpaper>
        synchronized(lock) {
            fresh = wallpapers.filter { it.id !in recentIds }
            recentIds.addAll(fresh.map { it.id })
            while (recentIds.size > RECENT_IDS_LIMIT) {
                recentIds.removeFirst()
            }
        }
        rememberTags(fresh)
        return fresh
    }

    /** Harvests seen tags for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTags(wallpapers: List<Wallpaper>) {
        if (wallpapers.isEmpty()) return
        synchronized(lock) {
            wallpapers.flatMapTo(LinkedHashSet()) { it.tags }.forEach { tag ->
                if (tagPool.size < TAG_POOL_LIMIT) tagPool.add(tag)
            }
        }
    }

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("wallpapercave answered HTTP $statusCode")

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun now(): Long = System.currentTimeMillis()

    /**
     * One browse tab's category: the term its section carries, the curated
     * topic behind page one, the album stream behind pages 2+, and whether
     * the host's own filter vocabulary has a `category` value for it (only
     * `anime` and `people` do — the rest reach the feed as tag-style query
     * presets the host routes through [search]).
     */
    private data class CategorySpec(
        val term: String,
        val title: String,
        val curatedTopic: String,
        val streamPath: String,
        val asHostCategory: Boolean = false,
    )

    private data class CachedAlbums(
        val albums: List<WallpaperCaveParser.Album>,
        val at: Long,
    )

    private companion object {
        const val ID = "cloudimage.wallpapercave"
        const val BASE_URL = "https://wallpapercave.com"

        /**
         * The browse tabs beyond the latest feed, in tab-bar order: the
         * curated topic behind page one and the album stream behind pages
         * 2+ (every slug and path verified live). `anime` and `people` also
         * ride the host's category vocabulary; the rest reach [search] as
         * tag-style query presets.
         */
        val browseTabs =
            listOf(
                CategorySpec("anime", "Anime", "anime-wallpapers", "/categories/anime-manga", asHostCategory = true),
                CategorySpec("girls", "Girls", "girls-wallpapers", "/search?q=girls"),
                CategorySpec("cars", "Cars", "cars-wallpapers", "/categories/vehicles/cars"),
                CategorySpec("people", "People", "people-wallpapers", "/categories/people", asHostCategory = true),
                CategorySpec("games", "Games", "games-wallpapers", "/categories/games"),
                CategorySpec("movies", "Movies", "movies-wallpapers", "/categories/movies"),
                CategorySpec("nature", "Nature", "nature-wallpapers", "/categories/nature"),
                CategorySpec("space", "Space", "space-wallpapers", "/categories/universe"),
                CategorySpec("animals", "Animals", "animals-wallpapers", "/categories/nature/animals"),
                CategorySpec("bikes", "Bikes", "motorcycles-wallpapers", "/categories/vehicles/motorcycles"),
                CategorySpec("sports", "Sports", "sports-wallpapers", "/categories/sports"),
                CategorySpec("abstract", "Abstract", "abstract-wallpapers", "/categories/abstract"),
            )

        /** The same tabs keyed by the exact term a query preset carries. */
        val categoryByTerm: Map<String, CategorySpec> = browseTabs.associateBy { it.term }

        /** Search batching: three albums per page, ten pages deep at most. */
        const val ALBUMS_PER_SEARCH_PAGE = 3
        const val MAX_SEARCH_PAGES = 10

        /** Category feeds: curated topic, then the album stream, this deep at most. */
        const val MAX_CATEGORY_PAGES = 15

        /** Served-id window per category; overlap never spans this far. */
        const val CATEGORY_SEEN_LIMIT = 1_500

        /** Album-list cache: one pagination session, interleaved tabs. */
        const val SEARCH_CACHE_MS = 60_000L
        const val SEARCH_CACHE_SLOTS = 8

        /** Latest-feed dedupe window and the tag pool feeding suggestions. */
        const val RECENT_IDS_LIMIT = 500
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8
        const val MAX_TAGS = 8

        /** One lock over every mutable member; sections load in parallel. */
        val lock = Any()
    }

    private val albumCache = LinkedHashMap<String, CachedAlbums>()
    private val recentIds = ArrayDeque<String>()
    private val tagPool = LinkedHashSet<String>()

    /** Per-category pagination sessions: served ids and the album cursor. */
    private val categorySeenIds = LinkedHashMap<String, MutableSet<String>>()
    private val categoryCursor = mutableMapOf<String, Int>()
}
