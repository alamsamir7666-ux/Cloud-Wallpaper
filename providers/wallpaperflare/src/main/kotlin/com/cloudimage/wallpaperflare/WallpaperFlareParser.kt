package com.cloudimage.wallpaperflare

/**
 * Hand-rolled HTML mining for wallpaperflare.com — the scraping half of
 * this provider, kept free of any parsing library on purpose.
 *
 * Extension packages carry ONLY their own classes: the build dexes the
 * module jar and nothing else, so a dependency like Jsoup would be missing
 * at load time (the host supplies the contract and kotlinx.serialization,
 * nothing more). Everything below is therefore stdlib string and regex work
 * over the site's server-rendered markup — exactly the technique the
 * CloudStream extension ecosystem uses against page-embedded data.
 *
 * ## Where the shapes come from
 *
 * v1.2.0 pinned its anchors from search-engine caches and guessed the rest —
 * and the guesses were wrong in exactly one place that mattered: the
 * wallpaper page URL. v1.3.0's anchors are captured from the site's OWN
 * live markup (fetched through a real browser context):
 *
 * - every listing — homepage, search, a wallpaper page's related row —
 *   renders the SAME grid: one `<li itemprop="associatedMedia"
 *   itemscope itemtype="http://schema.org/ImageObject">` per wallpaper,
 *   carrying the whole record in schema.org microdata:
 *   - `<meta itemprop="keywords" content="landscape, anime, …">` — the
 *     tag row, comma-separated;
 *   - `<meta itemprop="description" content="This HD wallpaper is about
 *     …, Original wallpaper dimensions is 2560x1440px, file size is …">`
 *     — the TRUE pixel dimensions, stated in plain copy;
 *   - `<div class="res">` — `itemprop="width"`/`"height"` value spans
 *     printing the same numbers again;
 *   - `<a itemprop="url" href="https://www.wallpaperflare.com/{slug}">` —
 *     THE wallpaper's own page, a bare slug at the site root (NOT the
 *     `/wallpaper/{a}/{b}/{c}/{slug}` path v1.2.0 guessed — that shape
 *     answers the site's 404 page, which is what installs in the wild
 *     flashed at every wallpaper tap);
 *   - inside the anchor, `<img itemprop="contentUrl">` with the image on
 *     the CDN (`https://c{1..4}.wallpaperflare.com/wallpaper/{a}/{b}/{c}/
 *     {slug}-thumb.jpg`, a `-preview.jpg` sibling in `data-srcset`/
 *     `srcset`, the original at the suffix-stripped stem), plus
 *     `title`/`alt` carrying the display name;
 *   - `<figcaption itemprop="caption">` — the name again, clean.
 * - the search form posts GET to `/search` with `name="wallpaper"`, and
 *   every tag chip on the site links into `/search?wallpaper={query}`
 *   with `+` for spaces — the one true query surface;
 * - a wallpaper page prints `<h1>HD wallpaper: {name}…</h1>`, the same
 *   description/keywords microdata, and shows the image as
 *   `<img class="view_img" src="…-preview.jpg">`; its download routes
 *   (`/{slug}/download[/{W}x{H}]`) confirm the slug's authority.
 *
 * v1.2.7's hard-won anchor: THE ORIGINAL FILE'S URL. The CDN stem
 * (`…/{base}.jpg`, the preview's suffix-stripped form v1.3.0 shipped)
 * answers **404** — it never existed. The real original is MINTED per
 * wallpaper by the download page's own flow: the page embeds
 * `code='{W}_{H}_{ts}'` and a Cloudflare Turnstile widget; the widget's
 * callback POSTs `token`+`code` to `/captcha`; the response text is a
 * 32-hex hash; and the page's JavaScript rewrites the preview URL into
 * `https://r{N}.wallpaperflare.com/wallpaper/{a}/{b}/{c}/{base}-{hash}.jpg`
 * — the `r` host, same path, hash-suffixed filename. The hash is stable
 * per wallpaper (verified by refetch), and the URL it builds is publicly
 * fetchable like any preview. Because all of that runs as page
 * JavaScript, the HOST's WebView document lane is the only place the
 * minted URL can be observed — the host fetch already executes the
 * page's scripts (that is how it passes the zone), and the download
 * page's `show_img` carries the resolved URL once the flow lands:
 * `<img … id="show_img" src="https://r4…-{hash}.jpg">`. The provider
 * therefore lists wallpapers with a BLANK full URL (nothing honest to
 * put there) and mints it in [details] off the download page — see
 * [WallpaperFlareWallpaperProvider].
 *
 * The parsers stay narrow on purpose: every one keys on those semantic
 * anchors rather than document order, so cosmetic redesigns degrade
 * parsing to "nothing found" instead of producing garbage. All functions
 * are pure and total: bad input yields empty lists and nulls, never
 * exceptions — callers decide what a miss means. A generalized
 * anchor-with-image fallback covers markup the microdata pass cannot
 * recognize.
 */
internal object WallpaperFlareParser {
    /** One grid cell: everything the app needs, straight off the listing. */
    data class GridItem(
        /**
         * The wallpaper's identity as the SITE'S OWN page URL carries it:
         * the bare slug of `https://www.wallpaperflare.com/{slug}` — one
         * token that IS the detail page path, so no id-to-URL mapping is
         * ever needed. (v1.2.0 used the image's CDN path instead; those
         * are DIFFERENT slugs — the page slug cannot be derived from the
         * image path — and the constructed detail URLs 404'd.)
         */
        val id: String,
        /** Absolute preview/thumb URL (`…/wallpaper/{a}/{b}/{c}/{slug}-preview.jpg`). */
        val thumbUrl: String,
        /**
         * BLANK since v1.2.7: the listing cannot know the original's URL —
         * the site mints it per wallpaper on its download page (see the
         * class KDoc). The host treats a blank file URL as "resolve through
         * [WallpaperFlareWallpaperProvider.details]" and overlays the minted
         * URL onto the grid record when it lands.
         */
        val fullUrl: String,
        /** Display title, `HD wallpaper` furniture already stripped. */
        val title: String,
        /** TRUE pixel dimensions, published in the item's own microdata. */
        val width: Int?,
        val height: Int?,
        /** The item's own keyword row, cleaned and capped. */
        val tags: List<String>,
    )

    /** What a listing's pagination bar says about what comes next. */
    data class Pagination(
        /** The page number the bar marks as CURRENT, when it marks one. */
        val currentPage: Int?,
        /** The highest page number any bar link carries. */
        val maxPage: Int,
        /**
         * A bar link to `page+1` or beyond with the page number swapped
         * for the `__PAGE__` marker — the site's own URL shape for deep
         * pages, learned from the page instead of assumed.
         */
        val nextUrlTemplate: String?,
    )

    /** The record of a wallpaper page. */
    data class DetailRecord(
        val imageUrl: String?,
        val width: Int?,
        val height: Int?,
        val title: String?,
        val license: String?,
        val tags: List<String>,
    )

    /**
     * The download page's minted record (v1.2.7): the original's URL as
     * the page's own JavaScript wrote it into `show_img`, plus the true
     * dimensions and file size the page states in plain copy —
     * `Original wallpaper dimensions is 2560x1440px, file size is 232.07KB`.
     */
    data class DownloadRecord(
        /** The minted original: `https://r{N}.wallpaperflare.com/…/…-{32-hex}.jpg`. */
        val originalUrl: String,
        val width: Int?,
        val height: Int?,
        /** The page's stated file size, decoded from KB/MB copy into bytes. */
        val fileSizeBytes: Long?,
    )

    // ------------------------------------------------------------- patterns

    /**
     * One microdata grid item: the `li` the site stamps
     * `itemprop="associatedMedia"` on, whole. The region is mined for the
     * schema.org metas, the page anchor and the CDN image — every anchor
     * is semantic, none positional.
     */
    private val ITEM_REGION =
        Regex(
            """<li\b[^>]*itemprop=["']associatedMedia["'][^>]*>(.*?)</li>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** The item's page anchor: `itemprop="url"` carrying the href. */
    private val ITEM_ANCHOR = Regex("""<a\b[^>]*href=["']([^"']+)["'][^>]*>""")

    /** The item's keyword row: `<meta itemprop="keywords" content="…">`. */
    private val ITEM_KEYWORDS =
        Regex(
            """<meta\b[^>]*(?:itemprop|name)=["']keywords["'][^>]*content=["']([^"']*)["'][^>]*>""",
        )

    /** Same, attribute order flipped. */
    private val ITEM_KEYWORDS_FLIPPED =
        Regex(
            """<meta\b[^>]*content=["']([^"']*)["'][^>]*(?:itemprop|name)=["']keywords["'][^>]*>""",
        )

    /**
     * The TRUE dimensions as the item's own description copy states them:
     * `Original wallpaper dimensions is 2560x1440px`.
     */
    private val DESCRIPTION_DIMENSIONS =
        Regex("""dimensions\s+is\s+(\d{2,5})\s*[x×]\s*(\d{2,5})\s*px""", RegexOption.IGNORE_CASE)

    /** The schema.org width/height value spans, in document order. */
    private val VALUE_SPAN = Regex("""itemprop=["']value["'][^>]*>\s*(\d{2,5})\s*<""")

    /** Dimensions in flattened item text: `{W}x{H}px`, whitespace-tolerant. */
    private val TEXT_DIMENSIONS = Regex("""(\d{2,5})\s*[x×]\s*(\d{2,5})\s*px""", RegexOption.IGNORE_CASE)

    /** The item's caption: `<figcaption …>{name}</figcaption>`. */
    private val CAPTION =
        Regex("""<figcaption\b[^>]*>(.*?)</figcaption>""", RegexOption.DOT_MATCHES_ALL)

    /**
     * The generalized fallback cell: an anchor wrapping a CDN image, the
     * shape every pre-microdata variant of the grid used. The href is the
     * id's source; the anchor's text carries dimensions and tags when the
     * site still printed them there.
     */
    private val FALLBACK_CELL =
        Regex(
            """<a\b[^>]*href=["']([^"']+)["'][^>]*>\s*<img\b[^>]*?src=["'](https?://c\d+\.wallpaperflare\.com/wallpaper/(\d+)/(\d+)/(\d+)/([a-z0-9][a-z0-9-]*?)(?:-(?:preview|thumb))?\.jpg)["'][^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** The cell's separator furniture, trimmed off titles. */
    private val TITLE_FURNITURE = Regex("""[«»;]""")

    /** License labels the site prints in cells and on wallpaper pages. */
    private val LICENSES =
        Regex(
            """Public\s+Domain|CC0|CC\s?BY(?:-SA|-NC)?\s?(?:\d\.\d)?|Free\s+for\s+(?:personal|commercial)\s+use""",
            RegexOption.IGNORE_CASE,
        )

    /** The middot that separates a legacy cell's tag row. */
    private val TAG_SEPARATOR = Regex("""\s*[·,]\s*""")

    /** `HD wallpaper` furniture the image alt/title attributes carry. */
    private val TITLE_SUFFIX = Regex("""[,\s]+HD\s+wallpapers?\s*$""", RegexOption.IGNORE_CASE)

    /** Words never worth carrying as tags. */
    private val TAG_STOP_WORDS =
        setOf(
            "wallpaper",
            "wallpapers",
            "hd",
            "2k",
            "4k",
            "5k",
            "8k",
            "1080p",
            "px",
            "free",
            "download",
            "desktop",
            "mobile",
            "tablet",
            "pc",
            "laptop",
            "iphone",
            "android",
            "ipad",
            "sort",
            "popular",
            "relevance",
            "related",
            "search",
            "original",
        )

    /** Tag phrases whose lowercase spelling is pure site furniture. */
    private val TAG_NOISE_SUBSTRINGS = listOf("wallpaper", "download", "desktop", "1080p")

    /**
     * One pagination link: a `page=N` query param, a `/page/N` path, or
     * the site's path-keyed `/page=N` spelling — the three shapes this
     * platform family uses. Input is entity-unescaped, so an `&amp;page=`
     * bar link reads right.
     */
    private val PAGE_LINK =
        Regex("""<a\b[^>]*href=["']([^"']*?(?:[?&]page=|/page[=/])(\d{1,4})[^"']*)["'][^>]*>""")

    /** The pagination bar's own region, bounding link and current-page scans. */
    private val PAGINATION_REGION =
        Regex(
            """<ul[^>]*class=["'][^"']*pagination[^"']*["'][^>]*>(.*?)</ul>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** A page number rendered as the bar's current/active item (not a link). */
    private val CURRENT_PAGE =
        Regex(
            """<(?:li|span|a)\b[^>]*(?:class=["'][^"']*(?:active|current)[^"']*["']|aria-current=["'][^"']*["'])[^>]*>\s*(?:<[^>]+>\s*)*(?:Page\s*)?(\d{1,4})\b""",
        )

    /** A wallpaper page's `og:image` — some mirrors of the template still carry it. */
    private val OG_IMAGE =
        Regex(
            """<meta\b[^>]*(?:property|name)=["']og:image["'][^>]*content=["']([^"']+)["'][^>]*>""",
        )

    /** Same, attribute order flipped. */
    private val OG_IMAGE_FLIPPED =
        Regex(
            """<meta\b[^>]*content=["']([^"']+)["'][^>]*(?:property|name)=["']og:image["'][^>]*>""",
        )

    /**
     * The wallpaper page's own display image: the `view_img` element the
     * page shows the wallpaper in — the one `og:image`-less page that
     * still always states its image.
     */
    private val VIEW_IMG = Regex("""<img\b[^>]*class=["'][^"']*view_img[^"']*["'][^>]*>""")

    /** The `src` of any img tag. */
    private val IMG_SRC = Regex("""\ssrc=["']([^"']+)["']""")

    /** The `data-src`/`data-srcset`/`srcset` candidates of any img tag. */
    private val IMG_DATA_SRC = Regex("""\sdata-src=["']([^"']+)["']""")

    private val IMG_SRCSET = Regex("""\s(?:data-)?srcset=["']([^"']+)["']""")

    /** The page `<title>`, carrying the wallpaper's name. */
    private val TITLE_TAG =
        Regex("""<title\b[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)

    /** The wallpaper page's h1: `HD wallpaper: {name} …`. */
    private val DETAIL_TITLE = Regex("""<h1\b[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)

    /** A tag chip: every tag on this site links into its own search. */
    private val TAG_LINK = Regex("""[?&]wallpaper=([a-z0-9+%*-]+)["']""")

    /** Strips tags, entities and runs of whitespace — the text miner's soap. */
    private fun textOf(html: String): String =
        html
            .replace(Regex("""<[^>]+>"""), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("""\s+"""), " ")
            .trim()

    /** True when [word] is worth carrying as a tag. */
    private fun isTagWord(word: String): Boolean =
        word.length in 2..24 &&
            word[0].isLetter() &&
            word.lowercase() !in TAG_STOP_WORDS &&
            TAG_NOISE_SUBSTRINGS.none { word.lowercase().contains(it) }

    // ---------------------------------------------------------------- grid

    /**
     * Every grid cell of a listing, in document order, deduped by id —
     * the same wallpaper can appear with both `-preview` and `-thumb`
     * images (listing grid vs. related row), and the preview wins. The
     * microdata pass runs first; only a listing it cannot read at all
     * falls back to the generalized anchor-with-image scan.
     */
    fun parseGrid(html: String): List<GridItem> {
        val byId = LinkedHashMap<String, GridItem>()
        for (region in ITEM_REGION.findAll(html)) {
            val item = microdataItem(region.groupValues[1]) ?: continue
            mergeById(byId, item)
        }
        if (byId.isEmpty()) {
            for (match in FALLBACK_CELL.findAll(html)) {
                val (href, imageUrl) = match.destructured
                // Group 7 is the anchor's tail text — the legacy record.
                val item = fallbackItem(href, imageUrl, match.groupValues[7]) ?: continue
                mergeById(byId, item)
            }
        }
        return byId.values.toList()
    }

    /** Keeps the better image variant when both survive a dedupe key. */
    private fun mergeById(
        byId: LinkedHashMap<String, GridItem>,
        item: GridItem,
    ) {
        val existing = byId[item.id]
        if (existing == null || (existing.thumbUrl.contains("-thumb.") && !item.thumbUrl.contains("-thumb."))) {
            byId[item.id] = item
        }
    }

    /**
     * One microdata `li` region to a grid item. The page anchor's href is
     * the identity; the CDN image is preferred at its biggest variant
     * (`data-srcset`'s preview, then `srcset`, then `data-src`, then the
     * rendered `src`); dimensions come from the item's own copy (the
     * description's `dimensions is {W}x{H}px`, the schema.org value
     * spans, then any flattened `{W}x{H}px` text); the name from the
     * caption, then the image's own `title`/`alt`.
     */
    private fun microdataItem(region: String): GridItem? {
        val href =
            ITEM_ANCHOR
                .findAll(region)
                .map { it.groupValues[1] }
                .firstOrNull(::isPageHref) ?: return null
        val imgTag =
            IMG_TAG
                .findAll(region)
                .firstOrNull { bestImageOf(it.value) != null } ?: return null
        val thumbUrl = bestImageOf(imgTag.value) ?: return null
        val id = pageSlugOf(href) ?: return null
        val keywords = metaContent(region, ITEM_KEYWORDS, ITEM_KEYWORDS_FLIPPED)
        val tags = tagsOf(keywords)
        val dims = dimensionsOf(region)
        val title =
            captionTitleOf(region, imgTag.value)
                ?.removeSuffix(",")
                ?.trim()
                .orEmpty()
        return GridItem(
            id = id,
            thumbUrl = thumbUrl,
            // Blank: the original's URL does not exist until the download
            // page mints it — see the class KDoc. The host resolves it
            // through details() on first open.
            fullUrl = "",
            title = title,
            width = dims?.first,
            height = dims?.second,
            tags = tags,
        )
    }

    /**
     * The legacy/unknown cell: the href supplies the id, the anchor text
     * (which older markup printed beside the image) supplies dimensions
     * and tags. Region text that says nothing keeps them empty — an item
     * with an id and honest URLs is still worth showing.
     */
    private fun fallbackItem(
        href: String,
        imageUrl: String,
        tailHtml: String,
    ): GridItem? {
        val id = pageSlugOf(href) ?: return null
        val tail = textOf(tailHtml)
        val dims = TEXT_DIMENSIONS.find(tail)
        val dimsRange = dims?.range
        val headText =
            if (dimsRange != null) {
                tail.substring(0, dimsRange.first)
            } else {
                tail
            }
        val title =
            headText
                .replace(TITLE_FURNITURE, " ")
                .replace(LICENSES, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
                .removeSuffix(",")
                .trim()
        val tagSource =
            if (dimsRange != null) {
                tail.substring(dimsRange.last + 1)
            } else {
                ""
            }
        val tags =
            tagSource
                .replace(LICENSES, " ")
                .split(TAG_SEPARATOR)
                .map { it.trim().lowercase() }
                .filter(::isTagWord)
                .distinct()
                .take(MAX_TAGS)
        return GridItem(
            id = id,
            thumbUrl = imageUrl,
            // Blank — the minted original is unknowable at listing time.
            fullUrl = "",
            title = title,
            width = dims?.groupValues?.get(1)?.toIntOrNull(),
            height = dims?.groupValues?.get(2)?.toIntOrNull(),
            tags = tags,
        )
    }

    /** True when an href points at the site's own wallpaper pages. */
    private fun isPageHref(href: String): Boolean = pageSlugOf(href) != null

    /**
     * The bare page slug of a site URL — the id. Absolute
     * `https://www.wallpaperflare.com/{slug}` and relative `/{slug}` both
     * read, unicode letters and resolution marks included (`…-tanjirō-udebl`,
     * `…-3840×2400-peajp` — the site's own hrefs carry them); site routes
     * (search, download — no dash, too short), file names (`opensearch.xml`)
     * and foreign hosts are not wallpaper pages.
     */
    private fun pageSlugOf(href: String): String? {
        val path =
            href
                .substringAfter("://", href)
                .substringAfter('/', "")
                .substringBefore('?')
                .substringBefore('#')
                .trim('/')
        if (path.length < 8 || !path.contains('-')) return null
        if (Regex("""[\s/?.:]""").containsMatchIn(path)) return null
        if (path in SITE_ROUTES) return null
        return path
    }

    /** The site's own route words — never a wallpaper page's slug. */
    private val SITE_ROUTES =
        setOf(
            "search",
            "download",
            "about",
            "contact",
            "login",
            "register",
            "terms",
            "privacy",
            "dmca",
            "feed",
            "sitemap",
        )

    /**
     * The best CDN image in an img tag: the preview a `srcset`/`data-srcset`
     * names first, then `data-src`, then whatever `src` renders — the
     * bigger variant always wins over the lazy-loading thumb.
     */
    private fun bestImageOf(imgTag: String): String? {
        val candidates =
            buildList {
                IMG_SRCSET.findAll(imgTag).forEach { add(it.groupValues[1].substringBefore(' ')) }
                IMG_DATA_SRC.findAll(imgTag).forEach { add(it.groupValues[1]) }
                IMG_SRC.findAll(imgTag).forEach { add(it.groupValues[1]) }
            }
        return candidates
            .filter { it.startsWith("http") && CDN_IMAGE.containsMatchIn(it) }
            .minByOrNull { if (it.contains("-preview.")) 0 else 1 }
    }

    /** The CDN image host pattern every grid cell discloses. */
    private val CDN_IMAGE = Regex("""https?://c\d+\.wallpaperflare\.com/wallpaper/""")

    /** Any complete img tag. */
    private val IMG_TAG = Regex("""<img\b[^>]*>""")

    /** The item's dimensions: description copy, then value spans, then text. */
    private fun dimensionsOf(region: String): Pair<Int, Int>? {
        DESCRIPTION_DIMENSIONS.find(region)?.let { dims ->
            dims.groupValues[1].toIntOrNull()?.let { w ->
                dims.groupValues[2].toIntOrNull()?.let { h -> return w to h }
            }
        }
        val values =
            VALUE_SPAN
                .findAll(region)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toList()
        if (values.size >= 2) return values[0] to values[1]
        TEXT_DIMENSIONS.find(textOf(region))?.let { dims ->
            dims.groupValues[1].toIntOrNull()?.let { w ->
                dims.groupValues[2].toIntOrNull()?.let { h -> return w to h }
            }
        }
        return null
    }

    /** The item's name: the caption, else the image's `title`/`alt`. */
    private fun captionTitleOf(
        region: String,
        imgTag: String,
    ): String? {
        CAPTION.find(region)?.let { return textOf(it.groupValues[1]) }
        for (attr in listOf("title", "alt")) {
            val value =
                Regex("""\s$attr=["']([^"']*)["']""")
                    .find(imgTag)
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()
            if (value.isNotBlank()) return value
        }
        return null
    }

    /** The keywords meta's comma row, cleaned and capped. */
    private fun tagsOf(keywords: String?): List<String> =
        keywords
            ?.split(',')
            ?.map { it.trim() }
            ?.filter(::isTagWord)
            ?.map { it.lowercase() }
            ?.distinct()
            ?.take(MAX_TAGS)
            ?: emptyList()

    /** A meta's content by either attribute order. */
    private fun metaContent(
        html: String,
        primary: Regex,
        flipped: Regex,
    ): String? = primary.find(html)?.groupValues?.get(1) ?: flipped.find(html)?.groupValues?.get(1)

    // ---------------------------------------------------------- pagination

    /**
     * What the pagination bar says. [PAGE_LINK] collects every numbered
     * link; [CURRENT_PAGE] finds the bar's own current-page marker when
     * it prints one. The template swaps the FIRST page number in the
     * best link for a `__PAGE__` marker, so the provider requests deep pages through
     * the site's own URL shape instead of a guessed parameter.
     */
    fun parsePagination(html: String): Pagination {
        val unescaped = html.replace("&amp;", "&")
        // Prefer the bar's own region — it bounds the current-page scan away
        // from nav menus that also carry "active" classes; fall back to the
        // whole document so a bar-less redesign still finds numbered links.
        val region = PAGINATION_REGION.find(unescaped)?.groupValues?.get(1) ?: unescaped
        val links =
            PAGE_LINK
                .findAll(region)
                .map { match ->
                    val (url, number) = match.destructured
                    url to number
                }.toList()
        if (links.isEmpty()) return Pagination(currentPage = null, maxPage = 0, nextUrlTemplate = null)
        val current = CURRENT_PAGE.findAll(region).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull()
        val best = links.maxByOrNull { it.second.toIntOrNull() ?: 0 } ?: return Pagination(current, 0, null)
        val maxPage = best.second.toIntOrNull() ?: 0
        // The link already carries a `page=` param or `/page/N` path (that is
        // what PAGE_LINK keys on), so the number-swap always lands. The token
        // is a plain marker, not a format specifier — URLs are full of `%XX`
        // encodings that String.format would choke on.
        val template =
            best.first.replaceFirst(
                Regex("""((?:[?&]page=|/page[=/]))\d{1,4}"""),
                "$1__PAGE__",
            )
        return Pagination(currentPage = current, maxPage = maxPage, nextUrlTemplate = template)
    }

    // --------------------------------------------------------------- detail

    /**
     * The wallpaper page's own record, or null when the page is not
     * recognizable as one — the caller turns that into a source failure
     * and the host falls back to the grid item's own URLs.
     */
    fun parseDetail(html: String): DetailRecord? {
        val unescaped = html.replace("&amp;", "&")
        val image =
            viewImageOf(unescaped)
                ?: OG_IMAGE.find(unescaped)?.groupValues?.get(1)
                ?: OG_IMAGE_FLIPPED.find(unescaped)?.groupValues?.get(1)
        val heading = DETAIL_TITLE.find(unescaped)?.groupValues?.get(1) ?: TITLE_TAG.find(unescaped)?.groupValues?.get(1)
        if (image == null && heading == null) return null
        val text = textOf(unescaped)
        // The dimension sentence lives in meta CONTENT attributes — tag
        // stripping eats it — so the raw document is scanned first; the
        // flattened-text fallback is the last resort.
        val dims = DESCRIPTION_DIMENSIONS.find(unescaped) ?: TEXT_DIMENSIONS.find(text)
        val license = LICENSES.find(text)?.value
        val title =
            heading
                ?.let { textOf(it) }
                // The h1 carries the page's own prefix — `HD wallpaper: …`.
                ?.removePrefix("HD wallpaper:")
                // The <title> carries the site's SEO furniture instead —
                // either tail, whichever the page prints.
                ?.replace(Regex("""\s*1080P,\s*2K,\s*4K,\s*5K.*$"""), "")
                ?.replace(Regex("""[\s|]*(HD\s+wallpapers?\s+free\s+download)?[\s|~-]*WallpaperFlare\s*$"""), "")
                ?.replace(TITLE_FURNITURE, " ")
                ?.replace(TITLE_SUFFIX, "")
                ?.trim()
                ?.ifBlank { null }
        val tags =
            buildList {
                addAll(tagsOf(metaContent(unescaped, ITEM_KEYWORDS, ITEM_KEYWORDS_FLIPPED)))
                addAll(
                    TAG_LINK
                        .findAll(unescaped)
                        .map { it.groupValues[1].replace("+", " ").lowercase() }
                        .filter { it.length in 2..24 && it !in TAG_STOP_WORDS }
                        .distinct()
                        .take(MAX_TAGS),
                )
            }.distinct().take(MAX_TAGS)
        return DetailRecord(
            // The raw display URL — the preview the page shows; the provider
            // derives both the thumb and the full-size stem from it.
            imageUrl = image,
            width = dims?.groupValues?.get(1)?.toIntOrNull(),
            height = dims?.groupValues?.get(2)?.toIntOrNull(),
            title = title,
            license = license,
            tags = tags,
        )
    }

    /** The page's own display image — the `view_img` element's `src`. */
    private fun viewImageOf(html: String): String? =
        VIEW_IMG
            .findAll(html)
            .mapNotNull { tag -> IMG_SRC.find(tag.value)?.groupValues?.get(1) }
            .firstOrNull { it.startsWith("http") }

    /**
     * The download page's minted record (v1.2.7). The original's URL is
     * read from the `show_img` element once the page's Turnstile flow has
     * assigned it — attribute order agnostic, because the host hands back
     * the DOM as the page's JavaScript left it — with a whole-document
     * scan for the minted-URL SHAPE as the fallback (a 32-hex-suffixed
     * original on the `r` host is distinctive enough to trust wherever it
     * appears). Dimensions and file size ride the page's own copy. Null
     * when none of the minted shape is present — the caller decides what
     * an unminted page means.
     */
    fun parseDownloadPage(html: String): DownloadRecord? {
        val unescaped = html.replace("&amp;", "&")
        val minted =
            IMG_TAG
                .findAll(unescaped)
                .filter { tag -> SHOW_IMG_ID.containsMatchIn(tag.value) }
                .mapNotNull { tag -> IMG_SRC.find(tag.value)?.groupValues?.get(1) }
                .firstOrNull { MINTED_ORIGINAL.matches(it) }
                ?: MINTED_ORIGINAL.findAll(unescaped).map { it.value }.firstOrNull()
                ?: return null
        val dims = DESCRIPTION_DIMENSIONS.find(unescaped)
        return DownloadRecord(
            originalUrl = minted,
            width = dims?.groupValues?.get(1)?.toIntOrNull(),
            height = dims?.groupValues?.get(2)?.toIntOrNull(),
            fileSizeBytes = fileSizeOf(unescaped),
        )
    }

    /** The `show_img` id attribute the download page's display image carries. */
    private val SHOW_IMG_ID = Regex("""\bid=["']show_img["']""")

    /**
     * A minted original URL, whole: the `r`-host CDN, either directory
     * shape the site's download JavaScript builds (`/wallpaper/{a}/{b}/{c}/`
     * and `/path/`), any slug, the 32-hex hash, the extension.
     */
    private val MINTED_ORIGINAL =
        Regex(
            """https://r\d+\.wallpaperflare\.com/(?:wallpaper/\d+/\d+/\d+/|path/)[^\"'\s<>]+?-[0-9a-f]{32}\.(?:jpg|jpeg|png|webp)""",
        )

    /**
     * The file size the page's copy states — `file size is 232.07KB`,
     * `4.07MB` — decoded into bytes. Null when the page says nothing.
     */
    private fun fileSizeOf(html: String): Long? {
        val match = FILE_SIZE.find(html) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        return when (match.groupValues[2].uppercase()) {
            "KB" -> (value * 1024).toLong()
            "MB" -> (value * 1024 * 1024).toLong()
            "B" -> value.toLong()
            else -> null
        }
    }

    /** `file size is 232.07KB` — the unit riding along. */
    private val FILE_SIZE = Regex("""file\s+size\s+is\s+([\d.]+)\s*(KB|MB|B)\b""", RegexOption.IGNORE_CASE)

    private const val MAX_TAGS = 8
}
