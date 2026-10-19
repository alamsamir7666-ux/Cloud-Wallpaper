package com.cloudimage.behance

import com.cloudimage.behance.BehanceParser.ProjectRecord
import com.cloudimage.behance.BehanceParser.ProjectRef
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
 * Behance (https://www.behance.net) as a Cloudimage provider package —
 * the site's own server-rendered state read the way its browser does, no
 * API, no key, no login.
 *
 * ## Site model
 *
 * Behance is Adobe's design-portfolio network: content lives in PROJECTS
 * (`/gallery/{id}/{slug}`), each a scroll of MODULES — for wallpaper
 * collections those modules are the wallpapers themselves, each published
 * as a complete ladder of renders on an open CDN
 * (`mir-s3-cdn-cf.behance.net/project_modules/{family}/{file}`): the
 * original in `source`, a 1920-px render in `fs`, grid thumbs in
 * `max_632`. Project COVERS are often collages or mockups, so this
 * provider never treats a cover as a wallpaper — every grid item is a
 * real module image, drilled out of its project page.
 *
 * Discovery rides the site's search (`/search/projects/{term}`), which
 * answers server-rendered: 24 project nodes plus a GraphQL cursor that
 * paginates as a plain `?after=` parameter, with `?sort=` choosing
 * recommended / most-appreciated / newest ordering. One search page
 * therefore feeds four grid pages of drilled modules.
 *
 * ## The bot gate
 *
 * Behance fronts its HTML with a Varnish JS-cookie challenge: a 403 whose
 * body literally sets `document.cookie = "js_challenge_value=<hex>"` and
 * reloads. The value is in the markup, so the provider replays it as a
 * Cookie header — one to five rotations open the gate. The gate also
 * demands a browser-shaped identity (a desktop User-Agent, Accept and
 * Accept-Language headers, and HTTP/2 — the app's OkHttp negotiates h2
 * natively). The challenge page is Varnish's own furniture, not
 * Cloudflare's, so the host's challenge detector never wakes and the 403
 * flows through to this plugin to solve — the image CDN needs none of
 * this and loads bare.
 *
 * ## How the contract maps onto it
 *
 * - [popular] is the site's answer to "wallpapers, best first": the
 *   `wallpaper` search with the host's sorting filter honored
 *   (`toplist` — the host's ubiquitous default — rides Behance's
 *   most-appreciated order; `date` its newest; `relevance` its
 *   recommended). Every grid page drills [PROJECTS_PER_PAGE] projects out
 *   of that term's cursor-paginated feed.
 * - [search] runs the same machinery for any user query — the two-step
 *   CloudStream pattern: the search page's project list first, then the
 *   first projects' module wallpapers merged, six projects per grid page,
 *   ten pages deep at most. One user query costs at most one search fetch
 *   plus six project fetches per grid page — a browser tab on the site's
 *   own results costs more.
 * - [sections] offers twelve shelves: the wallpaper term under toplist
 *   and date orders, then ten style presets (digital art, abstract,
 *   minimal, gradient, nature, space, anime, mobile, aesthetic, desktop)
 *   the host routes through [search] as tag-style query presets.
 * - [details] re-reads the module's project page — the definitive record:
 *   the exact source URL (a grid id's family guess corrected), the title,
 *   the author, aspect-true dimensions and the file's true width for the
 *   resolution line. ANY page failure degrades to the id-derived record:
 *   the id embeds the CDN file token, so thumb and original URLs are
 *   derivable with no fetch at all. Transport failures are the one
 *   exception: they propagate untouched, per the facade contract.
 * - [suggestTags] answers ONLY from tags this instance has already seen
 *   in drilled projects — no keystroke-driven scraping of the site.
 *
 * ## Politeness
 *
 * The site's robots.txt excludes `/v2/`, `/v3/` and filter-parameterized
 * search URLs from crawling; this provider never crawls: it fetches on
 * explicit user actions (open feed, type query, open wallpaper),
 * sequentially, in small bounded batches, with pagination caps —
 * per-user browser-equivalent traffic, the same judgment every
 * CloudStream provider makes. All URLs it walks (`/search/projects/…`,
 * `/gallery/…`) are the site's public pages, and the image CDN is open.
 *
 * ## State
 *
 * The contract asks plugins to be stateless; correctness here never
 * depends on the caches below — they only spare a search re-download per
 * pagination step and a project re-read per detail view. A fresh instance
 * answers identically, at worst with one repeated request. All mutable
 * state is guarded by one lock; sections load in parallel on the host
 * side.
 *
 * ## Dimensions
 *
 * Module dimensions are the project canvas's layout values (the canvas
 * is 1400 px wide) — aspect-true but not file-true, exactly like the grid
 * thumbnails of other sources. The masonry grid needs only the ratio,
 * which is exact; `details` upgrades the record with the file's true
 * width (published in the module's render ladder) to build the
 * resolution line.
 */
class BehanceWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Behance",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "Design-grade wallpapers from behance.net project collections - scraped, keyless.",
            // Logged-out search runs the site's own safe-browsing defaults and
            // mature-flagged projects are skipped whole; the host's rating
            // switch still applies on top of every item.
            contentRating = ContentRating.SFW,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.SEARCH, Capability.FILTERS, Capability.TAGS)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    /**
     * The default feed: the `wallpaper` search in the host's chosen order
     * — most appreciated for the host's ubiquitous `toplist` default, the
     * site's newest for `date`, its recommended order for `relevance`.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> = runCatching { feedPage(FEED_TERM, sortFor(filters), page, MAX_FEED_PAGES) }

    /**
     * Any user query: the search page's project list, then the first
     * projects' module wallpapers merged — six projects per grid page, ten
     * pages deep at most. A blank query is the default feed, the same
     * blanket every provider pulls over an empty field.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val term = query.trim()
            if (term.isEmpty()) {
                return@runCatching feedPage(FEED_TERM, sortFor(filters), page, MAX_FEED_PAGES)
            }
            feedPage(term, sortFor(filters), page, MAX_SEARCH_PAGES)
        }

    /**
     * Suggestions come ONLY from tags this instance has already read off
     * drilled projects — no keystroke-driven scraping of the site's
     * search. An uncached or fresh instance answers nothing, which the
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
     * The twelve shelves, offline as the contract asks: the wallpaper term
     * under toplist and date orders, then ten style presets the host
     * routes through [search] as tag-style query presets.
     */
    override suspend fun sections(): List<HomeSection> = shelves

    /**
     * The definitive record for an id. The module's project page carries
     * the exact source URL, the title, the author, aspect-true dimensions
     * and the file's true width — everything the detail screen shows.
     *
     * The page is enrichment, not a gate: the id alone already discloses
     * working thumb and original URLs (the CDN file token travels inside
     * it), so ANY page failure (deleted project, redesigned page, non-2xx)
     * degrades to that derived record instead of killing the viewer.
     * Transport failures are the one exception: they propagate untouched,
     * per the facade contract, so the host can report its own typed errors.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val parts = parseWallpaperId(id) ?: error("unrecognized wallpaper id '$id'")
            // The page fetch is enrichment, not a gate — but transport
            // failures must survive the degrade, per the facade contract.
            // (An explicit try/catch: a rethrow inside recoverCatching is
            // itself caught and folded back into the Result, so the
            // mid-chain rethrow pattern would silently degrade instead.)
            val record: ProjectRecord? =
                try {
                    projectRecord(ProjectRef(parts.projectId, parts.slug, "", null))
                } catch (failure: Throwable) {
                    if (failure is ProviderHttpException) throw failure
                    null
                }
            val module = record?.modules?.firstOrNull { it.file == parts.file }
            if (record != null && module != null) {
                WallpaperDetails(
                    wallpaper =
                        Wallpaper(
                            id = id,
                            providerId = ID,
                            thumbUrl = module.thumbUrl,
                            fullUrl = module.fullUrl,
                            title = record.name.ifBlank { null },
                            width = module.width,
                            height = module.height,
                            tags = record.tags,
                        ),
                    author = record.author,
                    resolution = resolutionOf(module),
                    sourceUrl = record.sourceUrl ?: "$BASE_URL/gallery/${parts.projectId}/${parts.slug}",
                )
            } else {
                derivedDetails(parts)
            }
        }

    /**
     * No random ordering exists on the site; the honest answer is the
     * default feed's first page. RANDOM is deliberately not declared, so
     * the host never asks — this exists to keep the contract total.
     */
    override suspend fun random(): Result<List<Wallpaper>> =
        runCatching { feedPage(FEED_TERM, SORT_APPRECIATED, 1, MAX_FEED_PAGES).wallpapers }

    // ------------------------------------------------------------------ feed

    /**
     * One grid page of a term's feed: tops the session's project list up
     * from the search's cursor pagination, drills the page's six-project
     * batch one by one, and merges their module wallpapers — capped at
     * [MODULES_PER_PROJECT] per project so a fifty-image collection feeds
     * the grid without drowning it. Dead, private or mature projects drop
     * here (their wallpapers, not the whole page); a batch that yields
     * nothing consumes the next instead of stranding the feed.
     */
    private suspend fun feedPage(
        term: String,
        sort: String,
        page: Int,
        maxPages: Int,
    ): Page {
        if (page < 1 || page > maxPages) return Page(emptyList(), nextPage = null)
        val key = "${term.lowercase()}|$sort"
        if (page == 1) {
            // A feed restart begins a new session: forget what an earlier
            // session served so the first batch comes back whole.
            synchronized(lock) { sessions.remove(key) }
        }
        val session = synchronized(lock) { sessions.getOrPut(key) { FeedSession() } }

        // Top the project list up until this page's batch is covered.
        var fetches = 0
        while (true) {
            val covered =
                synchronized(lock) { session.refs.size >= page * PROJECTS_PER_PAGE }
            if (covered) break
            if (fetches > 0 && !synchronized(lock) { session.hasMore }) break
            if (fetches >= MAX_SEARCH_FETCHES_PER_PAGE) break
            val cursor = synchronized(lock) { session.endCursor }
            val response = document(searchUrl(term, sort, cursor))
            if (!response.isSuccessful) throw httpError(response.statusCode)
            val parsed = BehanceParser.parseSearch(response.bodyText)
            if (parsed == null) {
                error("behance.net served a page without its embedded state")
            }
            synchronized(lock) {
                session.refs += parsed.projects
                session.hasMore = parsed.hasMore
                session.endCursor = parsed.endCursor
            }
            fetches++
            if (parsed.projects.isEmpty() && !parsed.hasMore) break
        }

        // Drill the page's batch, advancing past batches that yield nothing.
        val fresh = mutableListOf<Wallpaper>()
        var consumed = (page - 1) * PROJECTS_PER_PAGE
        var advances = 0
        while (advances <= MAX_BATCH_ADVANCES) {
            val batch =
                synchronized(lock) {
                    session.refs
                        .drop(consumed)
                        .take(PROJECTS_PER_PAGE)
                        .toList()
                }
            if (batch.isEmpty()) break
            consumed += batch.size
            for (ref in batch) {
                val record = runCatching { projectRecord(ref) }.getOrNull() ?: continue
                if (record.mature) continue
                for (image in record.modules.take(MODULES_PER_PROJECT)) {
                    val id = wallpaperId(ref, image)
                    val freshNow =
                        synchronized(lock) {
                            if (session.servedIds.add(id)) {
                                true
                            } else {
                                false
                            }
                        }
                    if (freshNow) {
                        fresh += wallpaperOf(id, ref, record, image)
                    }
                }
                rememberTags(record)
            }
            if (fresh.isNotEmpty()) break
            advances++
        }
        if (fresh.isNotEmpty()) {
            synchronized(lock) {
                while (session.servedIds.size > SERVED_IDS_LIMIT) {
                    session.servedIds.remove(session.servedIds.first())
                }
            }
        }

        val moreRemain =
            synchronized(lock) { consumed < session.refs.size || session.hasMore }
        return Page(fresh, nextPage = if (moreRemain && page < maxPages) page + 1 else null)
    }

    /** One drilled project page, parsed — cached briefly for detail views. */
    private suspend fun projectRecord(ref: ProjectRef): ProjectRecord {
        synchronized(lock) {
            projectCache.remove(ref.id)?.let { cached ->
                projectCache[ref.id] = cached
                return cached
            }
        }
        val response = document("$BASE_URL/gallery/${ref.id}/${encodePath(ref.slug)}")
        if (!response.isSuccessful) throw httpError(response.statusCode)
        val record =
            BehanceParser.parseProject(response.bodyText)
                ?: error("project page carried no embedded state")
        synchronized(lock) {
            projectCache[ref.id] = record
            while (projectCache.size > PROJECT_CACHE_SLOTS) {
                projectCache.remove(projectCache.keys.first())
            }
        }
        return record
    }

    /** A module image as a grid citizen: the project's title and tags, the file's URLs. */
    private fun wallpaperOf(
        id: String,
        ref: ProjectRef,
        record: ProjectRecord,
        image: BehanceParser.ModuleImage,
    ): Wallpaper =
        Wallpaper(
            id = id,
            providerId = ID,
            thumbUrl = image.thumbUrl,
            fullUrl = image.fullUrl,
            title = (record.name.ifBlank { ref.name }).ifBlank { null },
            width = image.width,
            height = image.height,
            tags = record.tags,
        )

    /**
     * The resolution line: the file's true width (from the render ladder)
     * times the layout ratio's height — aspect-true, so `4500 × 3000` for
     * a 600×400 module whose source is 4500 px wide. Null when the site
     * published neither piece.
     */
    private fun resolutionOf(module: BehanceParser.ModuleImage): String? {
        val width = module.sourceWidth ?: return null
        val ratioHeight = module.height ?: return null
        val ratioWidth = module.width ?: return null
        if (ratioWidth <= 0) return null
        val height = width.toLong() * ratioHeight / ratioWidth
        return "$width × $height"
    }

    /** Best-effort record when the project page cannot be read: the id discloses the file. */
    private fun derivedDetails(parts: WallpaperId): WallpaperDetails =
        WallpaperDetails(
            wallpaper =
                Wallpaper(
                    id = parts.wallpaperId,
                    providerId = ID,
                    thumbUrl = "${BehanceParser.CDN_HOST}/project_modules/$THUMB_FAMILY/${parts.file}",
                    fullUrl = "${BehanceParser.CDN_HOST}/project_modules/$ORIGINAL_FAMILY/${parts.file}",
                ),
            sourceUrl = "$BASE_URL/gallery/${parts.projectId}/${parts.slug}",
        )

    // ------------------------------------------------------- the bot gate

    /**
     * Fetches an HTML page through Behance's JS-cookie gate: a 403 whose
     * body sets `js_challenge_value` is replayed with that cookie — one to
     * five rotations open the gate, and a full restart of the dance
     * recovers the runs that lose it. A non-2xx that is NOT a challenge
     * (a 404, a 500) returns as-is: the caller phrases it. A stuck gate
     * surfaces the 403 honestly.
     */
    private suspend fun document(url: String): ProviderHttpResponse {
        var last: ProviderHttpResponse? = null
        repeat(CHALLENGE_ATTEMPTS) {
            var cookie: String? = null
            repeat(CHALLENGE_ROUNDS) {
                val response =
                    get(url) { headers ->
                        cookie?.let { headers["Cookie"] = "js_challenge_value=$it" }
                    }
                last = response
                if (response.isSuccessful) return response
                val next = BehanceParser.challengeCookie(response.bodyText)
                if (next == null || next == cookie) return response
                cookie = next
            }
        }
        return requireNotNull(last)
    }

    /** One gated GET: browser identity on every request, cookie when one is earned. */
    private suspend fun get(
        url: String,
        cookie: (MutableMap<String, String>) -> Unit,
    ): ProviderHttpResponse =
        httpClient?.get(
            url,
            buildMap {
                put("User-Agent", BROWSER_UA)
                put("Accept", BROWSER_ACCEPT)
                put("Accept-Language", BROWSER_ACCEPT_LANGUAGE)
                cookie(this)
            },
        ) ?: error("configure() was not called")

    // ------------------------------------------------------------- mapping

    /** The host's sorting vocabulary in Behance's own terms. */
    private fun sortFor(filters: Filters): String =
        when (filters.valuesFor("sorting").firstOrNull()) {
            "date" -> SORT_NEWEST
            "relevance", "random" -> SORT_RECOMMENDED
            // toplist is the host's ubiquitous default and the best wallpaper
            // order Behance offers; Behance has no random order to honor.
            else -> SORT_APPRECIATED
        }

    /** The search page URL for one term, sort and cursor position. */
    private fun searchUrl(
        term: String,
        sort: String,
        cursor: String?,
    ): String =
        buildString {
            append(BASE_URL)
            append("/search/projects/")
            append(encodePath(term))
            var query = false
            if (sort != SORT_RECOMMENDED) {
                append("?sort=").append(sort)
                query = true
            }
            if (cursor != null) {
                append(if (query) '&' else '?')
                append("after=")
                append(encodeQuery(cursor))
            }
        }

    /**
     * The wallpaper id: `{projectId}-{file token}-{project slug}`. The
     * project id and slug re-open the project page for the definitive
     * record — the slug is REQUIRED, Behance only serves embedded state
     * for the canonical URL — and the file token derives working thumb
     * and original URLs with no fetch at all.
     */
    private fun wallpaperId(
        ref: ProjectRef,
        image: BehanceParser.ModuleImage,
    ): String = "${ref.id}-${image.file}-${ref.slug}"

    /** Splits a wallpaper id back into its three parts; null when malformed. */
    private fun parseWallpaperId(id: String): WallpaperId? {
        val match = WALLPAPER_ID.find(id) ?: return null
        val (projectId, file, slug) = match.destructured
        if (slug.isBlank()) return null
        return WallpaperId(id, projectId, file, slug)
    }

    /** Harvests seen tags for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTags(record: ProjectRecord) {
        if (record.tags.isEmpty()) return
        synchronized(lock) {
            record.tags.forEach { tag ->
                if (tagPool.size < TAG_POOL_LIMIT) tagPool.add(tag)
            }
        }
    }

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("behance.net answered HTTP $statusCode")

    /** Path-segment encoding: spaces as %20 (the site's search requires it). */
    private fun encodePath(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    /** Query-value encoding for the opaque cursor (`MjQ=` → `MjQ%3D`). */
    private fun encodeQuery(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    /** The three parts a wallpaper id embeds. */
    private data class WallpaperId(
        val wallpaperId: String,
        val projectId: String,
        val file: String,
        val slug: String,
    )

    /** One term's pagination session: the walked project list and its cursor. */
    private class FeedSession {
        val refs = mutableListOf<ProjectRef>()
        var endCursor: String? = null
        var hasMore = false
        val servedIds = LinkedHashSet<String>()
    }

    private companion object {
        const val ID = "cloudimage.behance"
        const val BASE_URL = "https://www.behance.net"

        /** The default feed's search term — the site's own wallpaper shelf. */
        const val FEED_TERM = "wallpaper"

        /** Behance sort orders, as the `?sort=` parameter spells them. */
        const val SORT_RECOMMENDED = "recommended"
        const val SORT_APPRECIATED = "appreciations"
        const val SORT_NEWEST = "published_date"

        /** Module render families: the grid thumb and the original. */
        const val THUMB_FAMILY = "max_632"
        const val ORIGINAL_FAMILY = "source"

        /** The identity that passes the gate — verified live. */
        const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        const val BROWSER_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        const val BROWSER_ACCEPT_LANGUAGE = "en-US,en;q=0.9"

        /** `{projectId}-{cdn file token}-{project slug}`, split at its first two hyphens. */
        val WALLPAPER_ID = Regex("""^(\d+)-([\w.]+)-(.+)$""")

        /**
         * The twelve shelves: the wallpaper term in toplist and date
         * orders, then ten style presets (every term verified to carry
         * wallpaper-grade collections on the live site).
         */
        val shelves =
            listOf(
                shelf("popular", "Popular", FEED_TERM, "toplist"),
                shelf("newest", "Newest", FEED_TERM, "date"),
                shelf("digital-art", "Digital Art", "digital art wallpaper"),
                shelf("abstract", "Abstract", "abstract wallpaper"),
                shelf("minimal", "Minimal", "minimal wallpaper"),
                shelf("gradient", "Gradient", "gradient wallpaper"),
                shelf("nature", "Nature", "nature wallpaper"),
                shelf("space", "Space", "space wallpaper"),
                shelf("anime", "Anime", "anime wallpaper"),
                shelf("mobile", "Mobile", "mobile wallpaper"),
                shelf("aesthetic", "Aesthetic", "aesthetic wallpaper"),
                shelf("desktop", "Desktop", "desktop wallpaper"),
            )

        /** One shelf: a query preset, optionally with the host's sorting preset. */
        fun shelf(
            id: String,
            title: String,
            term: String,
            sorting: String? = null,
        ): HomeSection =
            if (sorting == null) {
                HomeSection(id = id, title = title, filters = Filters.of("query" to term))
            } else {
                HomeSection(id = id, title = title, filters = Filters.of("query" to term, "sorting" to sorting))
            }

        /** Feed batching: six projects drilled per grid page. */
        const val PROJECTS_PER_PAGE = 6

        /** One project contributes at most ten wallpapers to a page. */
        const val MODULES_PER_PROJECT = 10

        /** Search pages fetched to cover one grid page (24 projects each). */
        const val MAX_SEARCH_FETCHES_PER_PAGE = 4

        /** How far an empty batch walks before the page answers empty. */
        const val MAX_BATCH_ADVANCES = 2

        /** Feed depth caps: the default feed deeper than a query's. */
        const val MAX_FEED_PAGES = 16
        const val MAX_SEARCH_PAGES = 10

        /** Per-session dedupe window and the detail-view project cache. */
        const val SERVED_IDS_LIMIT = 600
        const val PROJECT_CACHE_SLOTS = 16

        /** The bot gate: cookie rotations per dance, dances per document. */
        const val CHALLENGE_ROUNDS = 5
        const val CHALLENGE_ATTEMPTS = 2

        /** The tag pool feeding suggestions. */
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /** One lock over every mutable member; sections load in parallel. */
        val lock = Any()
    }

    private val sessions = LinkedHashMap<String, FeedSession>()
    private val projectCache = LinkedHashMap<String, ProjectRecord>()
    private val tagPool = LinkedHashSet<String>()
}
