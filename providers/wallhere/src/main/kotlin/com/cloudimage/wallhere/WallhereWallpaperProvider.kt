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
 * ## Site model (v1.1.0 — the full surface, live-verified October 2026)
 *
 * WallHere is a tag-first, multi-language wallpaper catalogue. One flat
 * masonry grid renders EVERY listing — twenty-four items per page — and
 * every listing speaks the SAME query vocabulary the site's own filter
 * panel offers:
 *
 * - `/en/wallpapers` — `order=popular|latest`, `q=` keyword search (the
 *   top form's own target), `page=N` (the popular order runs 70,329
 *   pages deep), plus the panel's filters: `NSFW=off` (the site's purity
 *   switch — its own nav links it), `direction=horizontal|vertical`,
 *   `color=RRGGBB`, `min_width`/`min_height`;
 * - `/en/tag/{id}` — one strictly-tagged grid per tag, paginating
 *   thousands of pages deep (Nature 6,250, Anime 6,645), each page
 *   stating its "Similar Tags" cloud;
 * - `/en/random` — one fresh reshuffled batch per load;
 * - `/en/tags` — the tag index (54 per page, 10,267 pages).
 *
 * The grid's `current-item-photo` anchors disclose each wallpaper's
 * numeric id, its tag row (the img's own `title` attribute) and its CDN
 * thumbnail; the `item-action` div after each cell adds the uploader,
 * like and collect counts (not expressible in the V1 contract, left
 * unharvested).
 *
 * ## Purity — the honest server-side switch
 *
 * v1.0.0 believed the site had no anonymous purity control; it does:
 * `NSFW=off`, the site's own nav link. The provider now appends it to
 * every listing/search/tag/random URL whenever the host asks for
 * SFW-only content (purity = sfw, no sketchy), and omits it whenever
 * sketchy is allowed or purity is unspecified. The per-item tag-row
 * classification stays as the second layer — the site's switch is
 * trusted, but each item is still labelled by its own vocabulary so the
 * host's rating switch filters whatever slips a server-side filter.
 *
 * ## Search — the baked tag lane, the site search as fallback
 *
 * The site's `q=` search answers every query, fuzzily. The precision
 * surface is the tag pages, so [search] first asks the baked catalogue
 * ([WallhereTags] — 605 site-attested tags harvested from the index and
 * the Similar-Tags graph) whether the query NAMES a tag: an exact match
 * walks that tag's own grid, as deep as its thousands of pages go.
 * Everything else falls back to the site's own search unchanged.
 * [suggestTags] answers the search bar offline from the same catalogue
 * — a fresh instance suggests immediately, the wart v1.0.0 shipped
 * with — supplemented by tags harvested from grids already seen.
 *
 * ## The image URL families — and the original
 *
 * The image CDN (`c.wallhere.com`) serves derivable suffixes per file
 * across two path families (`/photos/{a}/{b}/{slug}-{id}.{ext}` and
 * `/images/{a}/{b}/{hash}-{id}.{ext}` — both live in the same grids):
 * `!s1` (1x thumb), `!s2` (2x thumb), `!d` (the display original,
 * ~1200px), `!as` (avatars). A grid cell's thumbnail discloses a
 * working display image by suffix swap. The TRUE full-resolution
 * original is a different URL the site mints onto the wallpaper page's
 * download anchor (`https://get.wallhere.com/photo/{slug}-{id}.{ext}`)
 * — not derivable from any CDN path, only stated by
 * `/en/wallpaper/{id}`. Listings therefore carry the display original
 * as [Wallpaper.fullUrl] (real, publicly served, honest ~1200px), and
 * [details] upgrades the record to the true original when the page
 * states it.
 *
 * ## Content rating
 *
 * The site mixes adult-tagged wallpapers into its DEFAULT feeds, so the
 * provider declares SKETCHY and classifies every item by the site's OWN
 * tag vocabulary: an item whose tag row carries an adult WORD is NSFW,
 * and the host's rating switch filters it exactly as it does Wallhaven's
 * purity labels. Everything else is SFW — and with the `NSFW=off`
 * mapping above, SFW-only users now get a server-filtered feed too. The
 * adult-word check is per WORD of each tag (v1.1.4: v1.1.2 matched whole
 * tag strings only and let compound rows like "panties down" or
 * "Lingerie Football League" through as SFW) — the SAME check the
 * suggestion filter below already rode, so the content filter and the
 * suggestion filter can no longer disagree about the same vocabulary.
 *
 * ## Cloudflare
 *
 * The zone Cloudflare-challenges datacenter addresses on every document
 * path (interactive Turnstile), while the image CDN serves every client
 * unchallenged. The host's HTTP facade already owns that problem
 * (per-host clearances, the WebView document lane, and since app
 * v1.2.19 the in-app browser escape hatch) — this plugin parses
 * whatever comes back. Its requests are bare of cookies but not of
 * identity (v1.1.4): the site serves its listings to MOBILE agents in
 * the bar-less infinite-scroll dialect (v1.1.2's field report), and on
 * a phone every fetch lane the host owns carries a mobile or
 * Android-branded agent — the descriptive host default contains
 * "Android", and the WebView document lane wears the device's own
 * mobile Chrome. Every document fetch therefore claims a DESKTOP
 * Chrome agent through the facade's sanctioned per-request User-Agent
 * override — the one lane the host honors verbatim — so an unchallenged
 * plain request comes back in the desktop dialect, with the pagination
 * bar the walk was built on. A replay riding a WebView-earned
 * clearance still travels under the clearance's own agent (clearance
 * binding wins, per the facade contract), and when that leaves page
 * two repeating page one, the first-continuation honesty below names
 * it instead of stopping in silence.
 *
 * ## Contract mapping
 *
 * - [popular] rides the host vocabulary: a recognized `category` walks
 *   that category's own TAG page (anime = the site's tag 150, people =
 *   the portrait tag 80 — precise grids instead of v1.0.0's fuzzy
 *   searches), `sorting=date` walks the latest order, `sorting=random`
 *   serves one fresh `/en/random` batch, and everything else lands on
 *   the popular order;
 * - [search] rides the baked tag lane on exact name matches, the site's
 *   own `/en/wallpapers?q=` search otherwise — one request per page,
 *   as deep as the site's bar goes; a BLANK query rides the latest
 *   order's own page (v1.1.1: the contract's escape hatch used to
 *   hardcode page one whatever page the host asked for);
 * - [sections] offers the two ranked shelves plus tag-style `query`
 *   presets whose values NAME catalogue tags (the row walks that tag's
 *   grid through [search]);
 * - [details] fetches `/en/wallpaper/{id}` and reads the definitive
 *   record: the true original's URL and resolution, the display
 *   fallback, the uploader and the page's fuller tag row;
 * - [suggestTags] answers offline from the baked catalogue plus tags
 *   harvested from grids already seen — never a third-party service;
 * - [random] serves the site's own `/en/random` batch as-is — a real
 *   random feed, advertised with [Capability.RANDOM].
 *
 * ## Continuation honesty (v1.1.1, v1.1.2)
 *
 * Every listing is a flat twenty-four-item page; the host's endless
 * scroll walks `?page=N` as deep as the site goes. A continuation that
 * comes back WRONG is reported, never papered over: a page-two-plus
 * fetch whose grid parses to nothing, and one whose bar marks a
 * CURRENT page that disagrees with the page requested (the site served
 * something other than page N — the parameter did not take), both
 * throw a source failure NAMING the mismatch instead of v1.1.0's
 * silent stops (an empty append that loops the footer forever, and a
 * duplicate append that crashes keyed grids). Page one keeps its
 * quiet shape: an unrecognized or genuinely empty first page stays an
 * empty feed — the honest "no results" surface a fresh search needs.
 *
 * v1.1.2 frees the walk from the desktop bar. The site serves its
 * MOBILE agents a different listing markup: the same 24-cell masonry
 * grid, but the classic `<ul class="pagination">` bar is replaced by
 * the page's own infinite-scroll wiring (a mobile browser auto-loads
 * page two on scroll — the field report that pinned this showed every
 * feed and search stopping at exactly 24 items with no error and no
 * continuation, on a device whose every fetch lane carries a mobile
 * or Android-branded agent). The bar therefore cannot be the only
 * "more exists" signal: when a listing carries NO parseable bar, a
 * page whose grid parsed a FULL page of fresh wallpapers (the site's
 * fixed twenty-four) advertises page N+1 itself, a partial or empty
 * page is the honest end, and a continuation that repeats wallpapers
 * the walk already served for THAT listing (the site clamped a
 * too-deep request to its last page) ends quietly with nothing
 * appended — duplicates never reach keyed grids. When a bar IS
 * present it still governs, exactly as before.
 *
 * v1.1.4 makes the FIRST continuation prove itself. A page two that
 * repeats page one's wallpapers, or that answers with no grid the
 * parser reads, is never an honest end on a site whose pages are
 * fixed at twenty-four: page one served a full page, so a dead page
 * two means the continuation dialect is broken on this connection —
 * the mobile view ignoring the page parameter, or serving markup the
 * parser no longer reads — and it now THROWS, naming the shape, so a
 * field report says WHY the walk stopped instead of merely that it
 * did. Deeper pages keep the quiet clamp-end: a listing that ran out
 * is allowed to simply end.
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
            versionName = "1.1.4",
            author = "Cloudimage",
            description = "Free HD wallpapers from wallhere.com - scraped, keyless, tag-searchable.",
            // The site mixes adult-tagged content into its default feeds;
            // SKETCHY is the honest floor, every item is classified by its
            // own tag row, and SFW-only requests additionally ride the
            // site's server-side NSFW=off switch.
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
     * `category` walks that category's own TAG page (the precise grid),
     * `sorting=date` walks the latest order, `sorting=random` serves one
     * fresh random batch (the site's random page reshuffles on every
     * load), and everything else lands on the popular order — the feed
     * the browse tab shows first. SFW-only requests carry the site's own
     * `NSFW=off` switch on every URL.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val sfwOnly = wantsSfwOnly(filters)
            val category = filters.valuesFor("category").firstNotNullOfOrNull(::categoryLane)
            when {
                category != null -> listingPage(category, page, sfwOnly)
                filters.isSelected("sorting", "date") -> listingPage(LATEST_PATH, page, sfwOnly)
                filters.isSelected("sorting", "random") -> Page(randomBatch(sfwOnly), nextPage = null)
                else -> listingPage(POPULAR_PATH, page, sfwOnly)
            }
        }

    /**
     * Search, precision-first: a query that NAMES a catalogue tag walks
     * that tag's own strictly-tagged grid (deep pagination, honest
     * purity), every other query rides the search the site's own top
     * form submits (`/en/wallpapers?q=`). A blank query (the contract's
     * escape hatch) lands on the latest order — at the page the host
     * asked for (v1.1.1: the page argument is honored, not hardcoded to
     * one).
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val sfwOnly = wantsSfwOnly(filters)
            val trimmed = query.trim()
            when {
                trimmed.isBlank() -> listingPage(LATEST_PATH, page, sfwOnly)
                else -> {
                    val lane = WallhereTags.exactTag(trimmed)?.let { "/en/tag/${it.id}" }
                    listingPage(lane ?: searchPath(trimmed), page, sfwOnly)
                }
            }
        }

    /**
     * Suggestions answer offline, immediately, from the baked catalogue —
     * exact matches first, then prefixes, then contains, adult vocabulary
     * withheld per WORD of the name — supplemented by tag rows this
     * instance has already seen in fetched grids (the v1.0.0 behavior,
     * kept as the tail, under the same word-level adult filter).
     */
    override suspend fun suggestTags(query: String): Result<List<String>> =
        runCatching {
            if (query.isBlank()) {
                emptyList()
            } else {
                val baked =
                    WallhereTags.suggestions(query, TAG_SUGGESTION_LIMIT) { it in WallhereParser.ADULT_TAG_WORDS }
                val seen =
                    synchronized(lock) { tagPool.toList() }
                        .filter { it.contains(query, ignoreCase = true) }
                        .filterNot(::isAdultTag)
                (baked + seen).distinct().take(TAG_SUGGESTION_LIMIT)
            }
        }

    /**
     * The home shelves: the two ranked feeds first (Popular is the
     * default row the host's merged home picks), then tag-style `query`
     * presets whose values NAME catalogue tags — the host routes each
     * row through [search], which walks that tag's own grid. Cheap and
     * offline, as the contract asks.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = HomeSection.DEFAULT_ID, title = "Popular"),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "landscape", title = "Landscape", filters = Filters.of("query" to "landscape")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("query" to "anime")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "car")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "video games")),
            HomeSection(id = "space", title = "Space", filters = Filters.of("query" to "space")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
            HomeSection(id = "city", title = "City", filters = Filters.of("query" to "city")),
            HomeSection(id = "night", title = "Night", filters = Filters.of("query" to "night")),
            HomeSection(id = "mountains", title = "Mountains", filters = Filters.of("query" to "mountains")),
            HomeSection(id = "sunset", title = "Sunset", filters = Filters.of("query" to "sunset")),
            HomeSection(id = "sea", title = "Sea", filters = Filters.of("query" to "sea")),
            HomeSection(id = "forest", title = "Forest", filters = Filters.of("query" to "forest")),
            HomeSection(id = "snow", title = "Snow", filters = Filters.of("query" to "snow")),
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
     * as-is (per-item classified — the bare contract call carries no
     * filters to map onto the site's purity switch). The page
     * reshuffles on every load — there is no page 2.
     */
    override suspend fun random(): Result<List<Wallpaper>> = runCatching { randomBatch(sfwOnly = false) }

    // ---------------------------------------------------------------- feed

    /**
     * Any listing page: fetch, parse the grid, read the pagination bar.
     * The bar is the site's own "more exists" signal where it exists —
     * `nextPage` is non-null only when the bar advertises a page beyond
     * the one requested — but v1.1.2 no longer depends on it: the
     * MOBILE markup carries the same 24-cell grid with no bar at all
     * (the page's own infinite-scroll wiring replaces it), so a bar-less
     * FULL page of fresh wallpapers advertises page N+1 itself, and a
     * bar-less partial or empty page is the honest end. Continuations
     * are honest (v1.1.1): a page-two-plus answer whose bar marks a
     * CURRENT page that disagrees with the page requested (the site
     * served something other than page N — the parameter did not take)
     * is a DESCRIBED source failure, and so is a continuation whose grid
     * parses to nothing while a bar is present. A continuation that
     * only repeats wallpapers the walk already served for THIS listing
     * (the site clamped a too-deep request to its last page) ends
     * quietly with nothing appended — duplicates never reach keyed
     * grids. Page one keeps the quiet shape: an unrecognized or
     * genuinely empty first page answers an empty feed, the honest "no
     * results" surface. A [MAX_PAGES] cap bounds the deepest scroll the
     * same politeness way.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
        sfwOnly: Boolean,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        val response = get(listingUrl(path, page, sfwOnly))
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val items = WallhereParser.parseGrid(response.bodyText)
        rememberTagsFrom(items)
        val bar = WallhereParser.parsePagination(response.bodyText)
        if (page > 1 && bar.currentPage != null && bar.currentPage != page) {
            // The site served a different page than requested — the deep
            // URL shape did not take. Name the mismatch instead of
            // serving page one's items again (v1.1.0 appended the
            // duplicates, which crashes keyed grids) or stopping in
            // silence.
            throw IllegalStateException(
                "wallhere served page ${bar.currentPage} where page $page was requested — the page parameter did not take",
            )
        }
        val barPresent = bar.currentPage != null || bar.maxPage > 0
        if (page > 1 && items.isEmpty()) {
            if (barPresent) {
                // A bar-carrying continuation with no grid: either the
                // markup changed or the answer is not a listing at all.
                // The walk stops HERE, with the reason said out loud —
                // an empty append would loop the host's load-more
                // footer forever.
                throw IllegalStateException("wallhere's page $page carried no recognizable wallpapers")
            }
            if (page == 2) {
                // v1.1.4: the FIRST continuation can never be an honest
                // empty end on this site's fixed-size pages — page one
                // served a full twenty-four, so a bar-less page two
                // with no grid is markup this parser no longer reads.
                // Name it; the quiet end stays reserved for deeper
                // pages, where a listing is allowed to run out.
                throw IllegalStateException(
                    "wallhere's page 2 carried no wallpapers the parser recognizes — the mobile view answered the continuation with different markup",
                )
            }
            // A bar-less continuation with no grid (the mobile markup):
            // page one parsed fine, so the markup is intact — an empty
            // page N is the honest end of a listing that ran out (v1.1.2 —
            // v1.1.1 threw here, painting a real end of results as a
            // source failure on every bar-less listing).
            return Page(emptyList(), nextPage = null)
        }
        // The overlap guard: the wallpapers this walk already served for
        // THIS listing (page one REPLACES the set — a refreshed feed
        // starts a new walk). A continuation whose every item repeats
        // them was clamped to the listing's last page; it ends quietly,
        // and a partial overlap keeps only the fresh cells.
        val listingKey = "$path|$sfwOnly"
        val seen = seenIdsFor(listingKey)
        val fresh = if (page == 1) items else items.filter { it.id !in seen }
        if (page > 1 && fresh.isEmpty()) {
            if (page == 2) {
                // v1.1.4: the first continuation repeating page one
                // verbatim is not a clamp — no listing this site serves
                // is one page deep when its first page was FULL. It is
                // the page parameter not advancing on this connection
                // (the mobile view ignoring it), and it is named so the
                // field report carries the reason. Deeper repeats stay
                // quiet clamp-ends.
                throw IllegalStateException(
                    "wallhere's page 2 repeated page 1's wallpapers — either the listing has only one page or this connection's mobile view ignores the page parameter",
                )
            }
            return Page(emptyList(), nextPage = null)
        }
        rememberSeenIds(
            listingKey,
            (if (page == 1) emptySet() else seen) + fresh.map { it.id },
        )
        val nextPage =
            when {
                page >= MAX_PAGES -> null
                barPresent -> if (bar.maxPage > page) page + 1 else null
                // No bar at all — the mobile markup. The site pads every
                // page of a continuing listing to its fixed size; a page
                // that came up short (or lost cells to the overlap
                // guard) is the last one.
                fresh.size >= FULL_PAGE -> page + 1
                else -> null
            }
        return Page(fresh.map(::gridWallpaper), nextPage = nextPage)
    }

    /** The random batch — same grid, no pagination signal worth reading. */
    private suspend fun randomBatch(sfwOnly: Boolean): List<Wallpaper> {
        val response = get(listingUrl(RANDOM_PATH, 1, sfwOnly))
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
     * tag row carries an adult WORD — checked per word, so multi-word
     * rows ("panties down", "Lingerie Football League") classify exactly
     * like the bare vocabulary while innocent words ("grass", "bass")
     * never trip their shorter selves. The host's rating switch does the
     * filtering; this classification is what makes it honest for a
     * source whose server-side switch is only asked on SFW-only
     * requests.
     */
    private fun ratingFor(tags: List<String>): ContentRating =
        if (tags.any(::isAdultTag)) {
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

    /**
     * The wallpaper ids a listing's walk has already served — the
     * overlap guard's memory, keyed by listing (path plus purity) so the
     * Popular tab, a tag lane and a search never compare notes. Page one
     * REPLACES its listing's set: a refreshed feed is a new walk, and a
     * revisit must not answer "everything already seen" with an empty
     * grid. Access-ordered so the most recently walked listing survives
     * the [SEEN_LISTINGS_LIMIT] eviction; each set is capped at the
     * deepest legal walk's worth of ids.
     */
    private val seenIdsByListing = LinkedHashMap<String, LinkedHashSet<String>>(16, 0.75f, true)

    private fun seenIdsFor(listingKey: String): Set<String> = synchronized(lock) { seenIdsByListing[listingKey]?.toSet() ?: emptySet() }

    private fun rememberSeenIds(
        listingKey: String,
        ids: Set<String>,
    ) {
        synchronized(lock) {
            val trimmed =
                if (ids.size > SEEN_IDS_LIMIT) {
                    ids.drop(ids.size - SEEN_IDS_LIMIT)
                } else {
                    ids.toList()
                }
            seenIdsByListing[listingKey] = trimmed.toCollection(LinkedHashSet())
            // Access-ordered: the first key is the least recently walked
            // listing — the one an eviction should forget. (The map's own
            // removeEldestEntry is protected Java surface, unreachable
            // from Kotlin call sites; this is the same discipline spelled
            // out.)
            while (seenIdsByListing.size > SEEN_LISTINGS_LIMIT) {
                seenIdsByListing.keys.firstOrNull()?.let { seenIdsByListing.remove(it) }
            }
        }
    }

    /**
     * True when a tag carries an adult word — checked per WORD (spaces,
     * hyphens and underscores all split), so multi-word rows ("panties
     * down", "Lingerie Football League") are caught exactly like the
     * bare vocabulary while innocent words ("grass", "bass") never trip
     * their shorter selves. THE SAME CHECK gates per-item ratings and
     * search-bar suggestions — the content filter and the suggestion
     * filter must agree about the same vocabulary.
     */
    private fun isAdultTag(tag: String): Boolean = tag.lowercase().split(' ', '-', '_').any { it in WallhereParser.ADULT_TAG_WORDS }

    // ------------------------------------------------------------- plumbing

    /**
     * True when the host asked for SFW-only content: the purity
     * selection carries `sfw` and nothing else. Sketchy-allowed and
     * unspecified requests both stay bare — the site's own default.
     */
    private fun wantsSfwOnly(filters: Filters): Boolean {
        val purity = filters.valuesFor("purity")
        return purity.isNotEmpty() && purity.none { it.equals("sketchy", ignoreCase = true) }
    }

    /**
     * The tag-page lane of a host `category` value, when the catalogue
     * names one: `anime` is the site's own deepest tag family, `people`
     * rides the portrait tag — the honest nearest expression of a value
     * the site files no single category under. `general` needs no lane:
     * it is the default feed.
     */
    private fun categoryLane(category: String): String? =
        when (category.lowercase()) {
            "anime" -> tagLanePath("anime")
            "people" -> tagLanePath("portrait")
            else -> null
        }

    /** The `/en/tag/{id}` path of a catalogue tag's exact name. */
    private fun tagLanePath(name: String): String? = WallhereTags.exactTag(name)?.let { "/en/tag/${it.id}" }

    /**
     * The full listing URL: the path as given, the site's own
     * `NSFW=off` switch prepended to the query when the host asks for
     * SFW-only content, and `page=N` appended for deep pages — the
     * dialect the site's own bar links and nav links use.
     */
    private fun listingUrl(
        path: String,
        page: Int,
        sfwOnly: Boolean,
    ): String {
        val params = mutableListOf<String>()
        if (sfwOnly) params += "NSFW=off"
        if (page > 1) params += "page=$page"
        if (params.isEmpty()) return "$BASE_URL$path"
        val joiner = if (path.contains('?')) "&" else "?"
        return "$BASE_URL$path$joiner${params.joinToString("&")}"
    }

    /** The search listing path for [query], the site's own form encoding. */
    private fun searchPath(query: String): String = "/en/wallpapers?q=${encode(query)}"

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url, DOCUMENT_HEADERS)
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

        /** Deep-pagination cap: a hundred pages, twenty-four items each. */
        const val MAX_PAGES = 100

        /** The site's fixed page size — a full page of fresh cells means more exists. */
        const val FULL_PAGE = 24

        /**
         * The desktop Chrome identity every document fetch claims. The
         * site serves its listings to mobile agents in the bar-less
         * infinite-scroll dialect, and every fetch lane the host owns on
         * a phone is mobile or Android-branded — so the provider asks
         * for the desktop markup explicitly, through the facade's
         * sanctioned per-request User-Agent override (the header rides
         * on top of the host's own; a WebView-earned clearance still
         * binds its own agent on replays, per the facade contract).
         */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        /** The headers every document fetch rides: the desktop identity alone. */
        val DOCUMENT_HEADERS = mapOf("User-Agent" to DESKTOP_USER_AGENT)

        /** How many listings' seen-id sets the overlap guard remembers. */
        const val SEEN_LISTINGS_LIMIT = 16

        /** Per listing: every id of the deepest legal walk, plus slack. */
        const val SEEN_IDS_LIMIT = MAX_PAGES * FULL_PAGE + FULL_PAGE

        /** Tag caps and the suggestion list size. */
        const val MAX_TAGS = 6
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /** One lock over the tag pool. */
        val lock = Any()
    }

    private val tagPool = LinkedHashSet<String>()
}
