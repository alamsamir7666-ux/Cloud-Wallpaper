package com.cloudimage.wallhere

/**
 * Hand-rolled HTML mining for wallhere.com — the scraping half of this
 * provider, kept free of any parsing library on purpose.
 *
 * Extension packages carry ONLY their own classes: the build dexes the
 * module jar and nothing else, so a dependency like Jsoup would be missing
 * at load time (the host supplies the contract, nothing more). Everything
 * below is therefore stdlib string and regex work over the site's
 * server-rendered markup — the same technique every scraper in this
 * provider set uses.
 *
 * ## Where the shapes come from
 *
 * Anchors captured from the site's OWN live markup (re-verified October
 * 2026 through a reader vantage that passes the zone's Cloudflare
 * challenge — the site challenges every datacenter document request,
 * so the shapes are pinned from the pages a real client receives):
 *
 * - every listing — `/en/wallpapers` in any order, search results, tag
 *   pages, `/en/random` — renders the SAME masonry grid: one
 *   `<a href="/en/wallpaper/{id}" class="current-item-photo">` per
 *   wallpaper, wrapping an `<img>` whose `title`/`alt` carries the site's
 *   own comma-separated tag row, whose `src` is the 1x thumbnail (`!s1`),
 *   whose `data-src` is the 2x one (`!s2`), and whose `srcset` restates
 *   both (`!s1 1x, !s2 2x`). After each cell, an `item-action` div adds
 *   the uploader link and the like/collect counts — real data the V1
 *   contract cannot express, left unharvested. The `width`/`height`
 *   attributes are the masonry BOX proportions — aspect-true, never
 *   file-true — so they are deliberately NOT published (the HDQWalls
 *   lesson: a wrong dimension is worse than none);
 * - the image CDN (`c.wallhere.com`) serves derivable suffix families
 *   per file across TWO live path families — `/photos/{a}/{b}/{slug}-{id}
 *   .{ext}` and `/images/{a}/{b}/{hash}-{id}.{ext}`, both mixed in the
 *   same grids: `!s1` (1x thumb), `!s2` (2x thumb), `!d` (the display
 *   original, ~1200px on its long edge) and `!as` (avatars) — no other
 *   family exists (probed). The display URL is what a grid cell's
 *   thumbnail discloses, and it is publicly served to every client,
 *   challenged or not;
 * - the pagination bar: a `<ul class="pagination">` whose
 *   `<li class="active">` marks the CURRENT page, whose numbered links
 *   carry the `?page=N` dialect (query params preserved), and whose
 *   `«`/`»` edge arrows degrade to `javascript:void(0)`;
 * - the wallpaper page `/en/wallpaper/{id}`: an
 *   `<img itemprop="contentURL">` for the display image with its true
 *   display dimensions, the download anchor
 *   `<a href="https://get.wallhere.com/photo/{slug}-{id}.{ext}"
 *   class="current-page-photo">` for the TRUE full-resolution original,
 *   the uploader's `<a class="profile-link">lrxv / 220 Wallpapers</a>`,
 *   and a `<meta name="description">` whose tail states the original's
 *   resolution — `… {W}x{H},{id}`. Both CDN path families serve the
 *   identical detail shape.
 *
 * The parsers stay narrow on purpose: every one keys on those semantic
 * anchors rather than document order, so cosmetic redesigns degrade
 * parsing to "nothing found" instead of producing garbage. All functions
 * are pure and total: bad input yields empty lists and nulls, never
 * exceptions — callers decide what a miss means.
 */
internal object WallhereParser {
    /** One grid cell: everything a listing discloses about a wallpaper. */
    data class GridItem(
        /** The wallpaper's numeric id, as the detail-page href carries it. */
        val id: String,
        /** The 1x thumbnail URL (`…!s1`), the same image the site's own grid loads first. */
        val thumbUrl: String,
        /**
         * The display original (`…!d`), derived by suffix swap — a real,
         * publicly served ~1200px file, NOT the true full-resolution
         * original (that lives on `get.wallhere.com` and is only stated
         * by the wallpaper page — see [parseDetail]).
         */
        val fullUrl: String,
        /** The cell's own tag row, cleaned and capped. */
        val tags: List<String>,
    )

    /** What a listing's pagination bar says about what comes next. */
    data class Pagination(
        /** The page number the bar marks as CURRENT, when it marks one. */
        val currentPage: Int?,
        /** The highest page number any bar link carries. */
        val maxPage: Int,
    )

    /** The record of a wallpaper page. */
    data class DetailRecord(
        /**
         * The TRUE original on `get.wallhere.com` when the page states
         * it — the site's own download target, full resolution. When the
         * anchor is absent (redesigned page), the display original stands
         * in: still a real, servable file, honestly lower-resolution.
         */
        val originalUrl: String,
        /** The display image URL (`…!d`) the page's `contentURL` img carries. */
        val displayUrl: String?,
        /** The TRUE pixel dimensions: the meta description's original pair when stated, else the display pair. */
        val width: Int?,
        val height: Int?,
        /** The uploader, ` / N Wallpapers` furniture stripped. */
        val author: String?,
        /** The page's own tag row — fuller than any grid cell's. */
        val tags: List<String>,
    )

    // ------------------------------------------------------------- patterns

    /**
     * One grid cell: the `current-item-photo` anchor wrapping its lazy
     * img, in the href-then-class attribute order the live markup uses.
     */
    private val GRID_CELL =
        Regex(
            """<a\b[^>]*href="(?:https?://[^/"]*)?/(?:[a-z]{2}/)?wallpaper/(\d+)"[^>]*class="[^"]*current-item-photo[^"]*"[^>]*>\s*(<img\b[^>]*>)""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** Same cell, class-then-href attribute order. */
    private val GRID_CELL_FLIPPED =
        Regex(
            """<a\b[^>]*class="[^"]*current-item-photo[^"]*"[^>]*href="(?:https?://[^/"]*)?/(?:[a-z]{2}/)?wallpaper/(\d+)"[^>]*>\s*(<img\b[^>]*>)""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** The `src` of any img tag — the 1x thumbnail on this site. */
    private val IMG_SRC = Regex("""\ssrc="([^"]+)"""")

    /** The `data-src` of any img tag — the 2x thumbnail the lazy loader swaps in. */
    private val IMG_DATA_SRC = Regex("""\sdata-src="([^"]+)"""")

    /** The `title` of any img tag — the site's own tag row, comma-separated. */
    private val IMG_TITLE = Regex("""\stitle="([^"]*)"""")

    /** The `alt` of any img tag — the same tag row when `title` is absent. */
    private val IMG_ALT = Regex("""\salt="([^"]*)"""")

    /** The `width` attribute of an img tag — the masonry box proportion. */
    private val IMG_WIDTH = Regex("""\swidth="(\d{1,5})"""")

    /** The `height` attribute of an img tag — the masonry box proportion. */
    private val IMG_HEIGHT = Regex("""\sheight="(\d{1,5})"""")

    /** The pagination bar's own region, bounding link and current-page scans. */
    private val PAGINATION_REGION =
        Regex(
            """<ul[^>]*class="[^"]*pagination[^"]*"[^>]*>(.*?)</ul>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** The bar's current/active item, carrying the page number as its link text. */
    private val CURRENT_PAGE =
        Regex(
            """<li\b[^>]*class="[^"]*\bactive\b[^"]*"[^>]*>\s*<(?:a|span)\b[^>]*>\s*(\d{1,5})\s*<""",
        )

    /** One bar page link: the `?page=N` dialect, query params preserved. */
    private val PAGE_LINK =
        Regex("""<a\b[^>]*href="[^"]*?[?&]page=(\d{1,5})[^"]*"[^>]*>""")

    /** The wallpaper page's display image: the img stamped `itemprop="contentURL"`. */
    private val CONTENT_URL_IMG =
        Regex("""<img\b[^>]*itemprop=["']contentURL["'][^>]*>""")

    /** The download anchor: the TRUE original, served from `get.wallhere.com`. */
    private val DOWNLOAD_ANCHOR =
        Regex("""<a\b[^>]*href="(https://get\.wallhere\.com/[^"]+)"[^>]*>""")

    /** The uploader's profile link: `lrxv / 220 Wallpapers`. */
    private val PROFILE_LINK =
        Regex(
            """<a\b[^>]*class="[^"]*profile-link[^"]*"[^>]*>([^<]*)</a>""",
        )

    /** The page's meta description, name-then-content attribute order. */
    private val META_DESCRIPTION =
        Regex("""<meta\b[^>]*name="description"[^>]*content="([^"]*)"""")

    /** Same, attribute order flipped. */
    private val META_DESCRIPTION_FLIPPED =
        Regex("""<meta\b[^>]*content="([^"]*)"[^>]*name="description"[^>]*>""")

    /**
     * A pixel-dimension pair `WxH` anywhere — the description's LAST
     * match is the original's true resolution (`… 1570x2220,{id}`).
     */
    private val DIMENSIONS = Regex("""(\d{2,5})\s*[x×]\s*(\d{2,5})""")

    /** The ` / N Wallpapers` suffix the profile link prints after the name. */
    private val PROFILE_FURNITURE = Regex("""\s*/\s*\d+\s+Wallpapers\s*$""")

    /** The tag row's separator. */
    private val TAG_SEPARATOR = Regex("""\s*,\s*""")

    /**
     * One HTML entity as an attribute may carry it — a decimal code
     * point (`&#233;`), a hex one (`&#xE0;`), or a named form. The
     * trailing semicolon is REQUIRED: raw text that merely looks
     * entity-shaped ("AT&T", "rock&roll") is not an entity and must
     * survive untouched.
     */
    private val ENTITY = Regex("""&(?:#\d{1,7};|#[xX][0-9a-fA-F]{1,6};|[a-zA-Z][a-zA-Z0-9]{1,31};)""")

    /**
     * The named entities worth decoding — the XML five, the nbsp the
     * site's own attributes use, and the typographic handful its
     * multi-language tag rows occasionally carry. Unknown named entities
     * stay literal — honest, never garbage.
     */
    private val NAMED_ENTITIES =
        mapOf(
            "amp" to "&",
            "lt" to "<",
            "gt" to ">",
            "quot" to "\"",
            "apos" to "'",
            "nbsp" to " ",
            "ndash" to "–",
            "mdash" to "—",
            "lsquo" to "‘",
            "rsquo" to "’",
            "ldquo" to "“",
            "rdquo" to "”",
            "hellip" to "…",
            "times" to "×",
            "deg" to "°",
            "middot" to "·",
            "laquo" to "«",
            "raquo" to "»",
            "bull" to "•",
            "copy" to "©",
            "reg" to "®",
            "trade" to "™",
        )

    /**
     * Words never worth carrying as tags — site furniture the tag rows
     * pick up, resolution labels, and the app's own vocabulary leaks.
     */
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
            "1440p",
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
            "latest",
            "random",
            "search",
            "original",
            "video",
        )

    /** Tag phrases whose lowercase spelling is pure site furniture. */
    private val TAG_NOISE_SUBSTRINGS = listOf("wallpaper", "download", "desktop", "1080p")

    /**
     * The site's own adult-vocabulary tag family — the words its tag
     * rows use for explicit content. An item carrying any of them is
     * classified NSFW so the host's rating switch can actually filter
     * what this site mixes into its default feeds.
     */
    val ADULT_TAG_WORDS =
        setOf(
            "nsfw",
            "nude",
            "nudes",
            "naked",
            "topless",
            "bottomless",
            "boobs",
            "boob",
            "breast",
            "breasts",
            "cleavage",
            "porn",
            "sexy",
            "sex",
            "erotic",
            "erotica",
            "lingerie",
            "bikini",
            "panties",
            "thong",
            "lesbian",
            "hentai",
            "nipple",
            "nipples",
            "areola",
            "fetish",
            "bdsm",
            "swimsuit",
            "underwear",
            "nudity",
            "ass",
        )

    /** The thumb suffix families the CDN serves. */
    private val THUMB_SUFFIXES = setOf("s1", "s2")

    /** How many tags a grid cell's row contributes. */
    private const val MAX_GRID_TAGS = 8

    /**
     * The first capture group of [regex]'s first match in [input] — the
     * one-liner every attribute mining below reduces to, kept a helper
     * so chains stay short and the reading stays declarative.
     */
    private fun firstGroupOf(
        regex: Regex,
        input: String,
    ): String? = regex.find(input)?.groupValues?.get(1)

    /** True when [tag] is worth carrying. */
    private fun isTagWord(tag: String): Boolean =
        tag.length in 2..32 &&
            tag.lowercase() !in TAG_STOP_WORDS &&
            TAG_NOISE_SUBSTRINGS.none { tag.lowercase().contains(it) }

    /** The tag row of a comma-separated attribute value, cleaned and capped. */
    private fun tagsOf(raw: String): List<String> =
        raw
            .split(TAG_SEPARATOR)
            .map { it.trim() }
            .filter(::isTagWord)
            .distinct()
            .take(MAX_GRID_TAGS)

    /**
     * The code point a numeric entity body states, when it states a
     * usable character — control garbage and lone surrogates answer
     * null and stay literal.
     */
    private fun codePointOf(body: String): Int? {
        if (!body.startsWith("#")) return null
        val code =
            if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                body.substring(2).toIntOrNull(16)
            } else {
                body.substring(1).toIntOrNull()
            }
        return code?.takeIf { it == 0x09 || it == 0x0A || it == 0x0D || (it in 0x20..0x10FFFF && it !in 0xD800..0xDFFF) }
    }

    /**
     * Decodes entities and collapses whitespace — the attribute miner's
     * soap (v1.1.4). ONE single left-to-right pass: every entity resolves
     * independently, so a double-encoded `&amp;#39;` stays the literal
     * text `&#39;` (v1.1.2's chained replaces decoded it twice), while
     * plain text that merely looks entity-shaped ("AT&T", "rock&roll")
     * keeps its ampersand untouched. Decimal and hex code points decode
     * to their characters — the accented vocabulary of the site's
     * multi-language tag rows (`café`, `città`) and the apostrophe family
     * (`&#x27;` beside `&#39;`) — and an unknown named entity stays
     * literal rather than mangling into garbage.
     */
    private fun textOf(raw: String): String =
        ENTITY
            .replace(raw) { match ->
                val body = match.value.substring(1, match.value.length - 1)
                codePointOf(body)
                    ?.let { String(Character.toChars(it)) }
                    ?: NAMED_ENTITIES[body]
                    ?: match.value
            }.replace(Regex("""\s+"""), " ")
            .trim()

    // ---------------------------------------------------------------- grid

    /**
     * Every grid cell of a listing, in document order, deduped by id —
     * the same wallpaper can appear twice (listing plus a related row),
     * and the first occurrence wins. Both attribute orders of the anchor
     * are read; a listing neither recognizes answers empty, which the
     * provider reports as a source failure rather than an empty feed.
     */
    fun parseGrid(html: String): List<GridItem> {
        val items = LinkedHashMap<String, GridItem>()
        for (pattern in listOf(GRID_CELL, GRID_CELL_FLIPPED)) {
            pattern.findAll(html).forEach { match ->
                val id = match.groupValues[1]
                if (id in items) return@forEach
                val img = match.groupValues[2]
                val thumb =
                    firstGroupOf(IMG_SRC, img)
                        ?: firstGroupOf(IMG_DATA_SRC, img)
                        ?: return@forEach
                if (!thumb.contains("!")) return@forEach
                val tagRow =
                    firstGroupOf(IMG_TITLE, img)
                        ?: firstGroupOf(IMG_ALT, img).orEmpty()
                items[id] =
                    GridItem(
                        id = id,
                        thumbUrl = thumb,
                        fullUrl = toDisplayUrl(thumb) ?: thumb,
                        tags = tagsOf(textOf(tagRow)),
                    )
            }
        }
        return items.values.toList()
    }

    /**
     * The `!d` display URL of a `!s1`/`!s2` thumbnail — the suffix
     * swapped on the same CDN path. Null when [thumbUrl] carries no
     * wallhere thumb suffix family to swap.
     */
    fun toDisplayUrl(thumbUrl: String): String? {
        val marker = thumbUrl.lastIndexOf('!')
        if (marker <= 0) return null
        val suffix = thumbUrl.substring(marker + 1)
        if (suffix !in THUMB_SUFFIXES) return null
        return thumbUrl.substring(0, marker) + "!d"
    }

    // ---------------------------------------------------------- pagination

    /**
     * What the pagination bar says: the current page the bar marks, and
     * the deepest page any of its links advertises. A page with no bar
     * at all answers current=null, max=0 — nothing more exists.
     */
    fun parsePagination(html: String): Pagination {
        val region = firstGroupOf(PAGINATION_REGION, html) ?: return Pagination(null, 0)
        val current = firstGroupOf(CURRENT_PAGE, region)?.toIntOrNull()
        val max =
            PAGE_LINK
                .findAll(region)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .maxOrNull() ?: 0
        return Pagination(current, maxOf(max, current ?: 0))
    }

    // --------------------------------------------------------------- detail

    /**
     * The wallpaper page's record: the TRUE original the page's own
     * download anchor states (the display original standing in when the
     * anchor is absent), the true dimensions (the meta description's
     * original pair preferred over the display pair), the uploader, and
     * the page's full tag row. A page that states none of the image
     * anchors answers null — the caller's source failure, with the grid
     * item's own values kept.
     */
    fun parseDetail(html: String): DetailRecord? {
        val displayImg = CONTENT_URL_IMG.find(html)?.groupValues?.get(0)
        val displayUrl = displayImg?.let { firstGroupOf(IMG_SRC, it) }
        val originalUrl = firstGroupOf(DOWNLOAD_ANCHOR, html)
        val resolved = originalUrl ?: displayUrl ?: return null

        val displayWidth = displayImg?.let { firstGroupOf(IMG_WIDTH, it) }?.toIntOrNull()
        val displayHeight = displayImg?.let { firstGroupOf(IMG_HEIGHT, it) }?.toIntOrNull()

        val description =
            firstGroupOf(META_DESCRIPTION, html)
                ?: firstGroupOf(META_DESCRIPTION_FLIPPED, html)
        val originalPair = description?.let { DIMENSIONS.findAll(it).lastOrNull() }
        val width = originalPair?.groupValues?.get(1)?.toIntOrNull() ?: displayWidth
        val height = originalPair?.groupValues?.get(2)?.toIntOrNull() ?: displayHeight

        val author =
            firstGroupOf(PROFILE_LINK, html)
                ?.let { raw ->
                    textOf(raw).replace(PROFILE_FURNITURE, "").trim()
                }?.ifBlank { null }

        val tagRow =
            displayImg
                ?.let { firstGroupOf(IMG_TITLE, it) ?: firstGroupOf(IMG_ALT, it) }
                .orEmpty()
        return DetailRecord(
            originalUrl = resolved,
            displayUrl = displayUrl,
            width = width,
            height = height,
            author = author,
            tags = tagsOf(textOf(tagRow)),
        )
    }
}
