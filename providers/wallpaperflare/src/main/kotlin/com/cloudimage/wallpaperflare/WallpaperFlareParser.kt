package com.cloudimage.wallpaperflare

/**
 * Hand-rolled HTML mining for wallpaperflare.com — the scraping half of
 * this provider, kept free of any parsing library on purpose.
 *
 * Extension packages carry ONLY their own classes: the build dexes the
 * module jar and nothing else, so a dependency like Jsoup would be missing
 * at load time (the host supplies the contract and
 * kotlinx.serialization, nothing more). Everything below is therefore
 * stdlib string and regex work over the site's server-rendered markup —
 * exactly the technique the CloudStream extension ecosystem uses against
 * page-embedded data.
 *
 * ## Where the shapes come from
 *
 * The site sits behind a Cloudflare firewall that blocks datacenter
 * addresses outright, so these anchors were pinned from the site's own
 * pages as Google indexes them (server-rendered, which is why the index
 * has them at all) plus the wallpaperflare.com URL family as it appears
 * in the wild:
 *
 * - grid images:
 *   `https://c{1..4}.wallpaperflare.com/wallpaper/{a}/{b}/{c}/{slug}-preview.jpg`
 *   (and a smaller `-thumb.jpg` variant) — every listing carries them;
 * - grid cells read `TITLE « »; {W}x{H}px {license} · tag · tag` — the
 *   separator, the `px` dimensions and the middot tag row are stable
 *   across the site's pages;
 * - search pages live at `/search?wallpaper={query}` with `+` for
 *   spaces, and every tag chip on the site links back into that same
 *   search shape — which is what tag harvesting keys on.
 *
 * The parsers are kept narrow on purpose: every one keys on those
 * semantic anchors rather than document order, so cosmetic redesigns
 * degrade parsing to "nothing found" instead of producing garbage. All
 * functions are pure and total: bad input yields empty lists and nulls,
 * never exceptions — callers decide what a miss means.
 */
internal object WallpaperFlareParser {
    /** One grid cell: everything the app needs, straight off the listing. */
    data class GridItem(
        /**
         * The wallpaper's identity as the site's own URLs carry it:
         * `{a}/{b}/{c}/{slug}` — the detail page path AND the image path
         * stem in one token, so no separate id->URL mapping is needed.
         */
        val id: String,
        /** Absolute preview URL (`…/wallpaper/{a}/{b}/{c}/{slug}-preview.jpg`). */
        val thumbUrl: String,
        /** Absolute full-size URL (`…/{slug}.jpg`) — the suffix-stripped stem. */
        val fullUrl: String,
        /** Display title, `« ` furniture and license labels already stripped. */
        val title: String,
        /** TRUE pixel dimensions, published in the cell's own text. */
        val width: Int?,
        val height: Int?,
        /** The cell's own middot-separated tags, cleaned and capped. */
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

    // ------------------------------------------------------------- patterns

    /**
     * One grid cell: an anchor whose first child is the preview image.
     * The image URL is the site's most stable anchor — every listing,
     * every related-wallpapers row carries exactly this shape.
     */
    private val GRID_ITEM =
        Regex(
            """<a\b[^>]*>\s*<img\b[^>]*?src=["'](https?://c\d+\.wallpaperflare\.com/wallpaper/(\d+)/(\d+)/(\d+)/([a-z0-9][a-z0-9-]*?)(?:-(?:preview|thumb))?\.jpg)["'][^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** The cell's separator furniture, trimmed off titles. */
    private val TITLE_FURNITURE = Regex("""[«»;]""")

    /** TRUE dimensions as the cell text publishes them: `{W}x{H}px`. */
    private val DIMENSIONS = Regex("""(\d{2,5})\s*[x×]\s*(\d{2,5})px""")

    /** License labels the site prints in cells and on wallpaper pages. */
    private val LICENSES =
        Regex(
            """Public\s+Domain|CC0|CC\s?BY(?:-SA|-NC)?\s?(?:\d\.\d)?|Free\s+for\s+(?:personal|commercial)\s+use""",
            RegexOption.IGNORE_CASE,
        )

    /** The middot that separates a cell's tag row. */
    private val TAG_SEPARATOR = Regex("""\s*[·,]\s*""")

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
        )

    /**
     * One pagination link: a `page=N` query param or `/page/N` path,
     * either quote style — the two shapes this platform family uses.
     * Input is entity-unescaped, so an `&amp;page=` bar link reads right.
     */
    private val PAGE_LINK =
        Regex("""<a\b[^>]*href=["']([^"']*?(?:[?&]page=|/page/)(\d{1,4})[^"']*)["'][^>]*>""")

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

    /** A wallpaper page's `og:image` — the definitive image URL. */
    private val OG_IMAGE =
        Regex(
            """<meta\b[^>]*(?:property|name)=["']og:image["'][^>]*content=["']([^"']+)["'][^>]*>""",
        )

    /** Same, attribute order flipped — the two orders both occur in the wild. */
    private val OG_IMAGE_FLIPPED =
        Regex(
            """<meta\b[^>]*content=["']([^"']+)["'][^>]*(?:property|name)=["']og:image["'][^>]*>""",
        )

    /** The page `<title>`, carrying the wallpaper's name. */
    private val TITLE_TAG =
        Regex("""<title\b[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)

    /** The `« »` the detail page prints around its license line. */
    private val DETAIL_TITLE = Regex("""<h1\b[^>]*>(.*?)</h1>""", RegexOption.DOT_MATCHES_ALL)

    /** A tag chip: every tag on this site links into its own search. */
    private val TAG_LINK = Regex("""[?&]wallpaper=([a-z0-9+%*-]+)["']""")

    /** The preview/thumb suffix, stripped to reach the full-size stem. */
    private val PREVIEW_SUFFIX = Regex("""-(?:preview|thumb)(?=\.jpg)""")

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
    private fun isTagWord(word: String): Boolean = word.length in 2..24 && word[0].isLetter() && word.lowercase() !in TAG_STOP_WORDS

    // ---------------------------------------------------------------- grid

    /**
     * Every grid cell of a listing, in document order, deduped by id —
     * the same wallpaper can appear with both `-preview` and `-thumb`
     * images (listing grid vs. related row), and the preview wins.
     */
    fun parseGrid(html: String): List<GridItem> {
        val byId = LinkedHashMap<String, GridItem>()
        for (match in GRID_ITEM.findAll(html)) {
            val (imageUrl, a, b, c, slug, tail) = match.destructured
            val item = gridItem(imageUrl, a, b, c, slug, tail)
            val existing = byId[item.id]
            if (existing == null || (existing.thumbUrl.contains("-thumb.") && !item.thumbUrl.contains("-thumb."))) {
                byId[item.id] = item
            }
        }
        return byId.values.toList()
    }

    private fun gridItem(
        imageUrl: String,
        a: String,
        b: String,
        c: String,
        slug: String,
        tailHtml: String,
    ): GridItem {
        val tail = textOf(tailHtml)
        val dims = DIMENSIONS.find(tail)
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
            id = "$a/$b/$c/$slug",
            thumbUrl = imageUrl,
            fullUrl = PREVIEW_SUFFIX.replace(imageUrl, ""),
            title = title,
            width = dims?.groupValues?.get(1)?.toIntOrNull(),
            height = dims?.groupValues?.get(2)?.toIntOrNull(),
            tags = tags,
        )
    }

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
                Regex("""((?:[?&]page=|/page/))\d{1,4}"""),
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
        val image = OG_IMAGE.find(unescaped)?.groupValues?.get(1) ?: OG_IMAGE_FLIPPED.find(unescaped)?.groupValues?.get(1)
        val heading = DETAIL_TITLE.find(unescaped)?.groupValues?.get(1) ?: TITLE_TAG.find(unescaped)?.groupValues?.get(1)
        if (image == null && heading == null) return null
        val text = textOf(unescaped)
        val dims = Regex("""(\d{3,5})\s*[x×]\s*(\d{3,5})\s*px""").find(text)
        val license = LICENSES.find(text)?.value
        val title =
            heading
                ?.let { textOf(it) }
                // The h1 carries the whole license line — the name is the
                // part before the « furniture.
                ?.substringBefore("«")
                // The <title> carries the site's SEO suffix instead.
                ?.replace(Regex("""\s*1080P,\s*2K,\s*4K,\s*5K.*$"""), "")
                ?.replace(TITLE_FURNITURE, " ")
                ?.trim()
                ?.ifBlank { null }
        val tags =
            TAG_LINK
                .findAll(unescaped)
                .map { it.groupValues[1].replace("+", " ").lowercase() }
                .filter { it.length in 2..24 && it !in TAG_STOP_WORDS }
                .distinct()
                .take(MAX_TAGS)
                .toList()
        return DetailRecord(
            imageUrl = image?.let { PREVIEW_SUFFIX.replace(it, "") },
            width = dims?.groupValues?.get(1)?.toIntOrNull(),
            height = dims?.groupValues?.get(2)?.toIntOrNull(),
            title = title,
            license = license,
            tags = tags,
        )
    }

    /** The full-size stem of any wallpaperflare image URL. */
    fun toFullUrl(imageUrl: String): String = PREVIEW_SUFFIX.replace(imageUrl, "")

    private const val MAX_TAGS = 8
}
