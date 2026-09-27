package com.cloudimage.hdqwalls

/**
 * Hand-rolled HTML mining for hdqwalls.com — the scraping half of this
 * provider, kept free of any parsing library on purpose.
 *
 * Extension packages carry ONLY their own classes: the build dexes the module
 * jar and nothing else, so a dependency like Jsoup would be missing at load
 * time (the host supplies the contract and kotlinx.serialization, nothing
 * more). Everything below is therefore stdlib string and regex work over the
 * site's server-rendered markup — exactly the technique the CloudStream
 * extension ecosystem uses against page-embedded data.
 *
 * The markup shapes were captured from the live site and are kept narrow on
 * purpose: every parser keys on stable, semantic anchors (the `wall-resp`
 * grid cell, the `pagination` bar, the blockquote's "Original Resolution"
 * line, `data-original-url`) rather than document order, so cosmetic
 * redesigns degrade parsing to "nothing found" instead of producing garbage.
 * The site uses BOTH quote styles (single in grids, double in pagination),
 * so every attribute reader accepts either. All functions are pure and
 * total: bad input yields empty lists and nulls, never exceptions — callers
 * decide what a miss means.
 */
internal object HdqWallsParser {
    /** One grid cell: a wallpaper page link with its thumbnail. */
    data class GridItem(
        /** The wallpaper's page slug, e.g. `the-batman-…-wallpaper` — the provider's stable id. */
        val id: String,
        /** Absolute thumbnail URL (`…/wallpapers/bthumb/{file}.jpg`). */
        val thumbUrl: String,
        /** Absolute original URL (`…/wallpapers/{file}.jpg`). */
        val originalUrl: String,
        /** Display title, ` Wallpaper` suffix already stripped. */
        val title: String,
        // No width/height here, on purpose: the listing markup hard-codes
        // the site's uniform card crop (`width='602' height='339'`) on
        // EVERY cell — identical for a 4K landscape and a portrait phone
        // wallpaper, so publishing them would label every item with the
        // thumbnail's size. The file's TRUE dimensions exist only on the
        // wallpaper's own page; see [parseDetail], which is where they are
        // read. Grid items therefore carry no dimensions at all — honest
        // emptiness over a number that is wrong for every wallpaper.
    )

    /** The definitive record of a wallpaper page. */
    data class DetailRecord(
        val originalUrl: String,
        /** The file's TRUE dimensions, the only place the site publishes them. */
        val width: Int?,
        val height: Int?,
        val title: String?,
        val author: String?,
        /** The `Download Original (X.XXMB)` label, converted to bytes. */
        val fileSizeBytes: Long?,
        /** The site's own tags (`/batman-wallpapers` anchors), slugs as-is. */
        val tags: List<String>,
    )

    /** Attribute splitter: `name="value"` and `name='value'` pairs, any order. */
    private val ATTR =
        Regex(
            """([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""",
        )

    /** Opening tag of one grid cell — the site's every listing uses this one shape. */
    private val CELL_START = Regex("""<div[^>]*class=["']wall-resp[^"']*["'][^>]*>""")

    /** Any anchor; the cell's first anchor is the caption link with the page href. */
    private val ANCHOR = Regex("""<a\s[^>]*href=["']([^"']+)["'][^>]*>""")

    /** Any `<img …>` tag. */
    private val ANY_IMG = Regex("""<img\b[^>]*>""")

    /** The pagination bar — the site's own signal for what comes next. */
    private val PAGINATION =
        Regex(
            """<ul[^>]*class=["']pagination["'][^>]*>(.*?)</ul>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** The `Next »` link inside a pagination bar. */
    private val NEXT_LINK = Regex("""<a\s[^>]*href=["']([^"']+)["'][^>]*>\s*Next""")

    /** A page number inside a pagination/query href, after entity unescaping. */
    private val PAGE_IN_QUERY = Regex("""[?&]page=(\d+)""")

    private val PAGE_IN_PATH = Regex("""/page/(\d+)""")

    /** The blockquote line carrying the original URL and its true dimensions. */
    private val ORIGINAL_RESOLUTION =
        Regex(
            """Original\s+Resolution:\s*<a[^>]*href=["']([^"']+)["'][^>]*>\s*(\d+)\s*[x×]\s*(\d+)""",
        )

    /** The download button's machine-readable original URL. */
    private val DATA_ORIGINAL_URL = Regex("""data-original-url=["']([^"']+)["']""")

    /** The `og:image` meta — the original file on wallpaper pages. */
    private val OG_IMAGE = Regex("""<meta\b[^>]*og:image[^>]*>""")

    /** The author credit, an anchor-wrapped italic inside the blockquote. */
    private val AUTHOR = Regex("""Author\s*:\s*<a[^>]*>\s*<i>\s*(.*?)\s*</i>""")

    /** The plain-text author fallback (no link). */
    private val AUTHOR_PLAIN = Regex("""Author\s*:\s*([^<|]+?)\s*(?:[|<]|$)""")

    /** The `Download Original (3.46MB)` label. */
    private val FILE_SIZE = Regex("""Download\s+Original\s*\(([\d.]+)\s*(B|KB|MB|GB)\)""")

    /** One tag anchor of the detail page's tag row: `/{slug}-wallpapers`. */
    private val TAG_ANCHOR =
        Regex("""<a\s[^>]*href=["'](?:https://hdqwalls\.com)?/([a-z0-9][a-z0-9-]*)-wallpapers["']""")

    /** Where the tag row starts (the marker `li`), bounding tag harvesting. */
    private val TAGS_SECTION = Regex("""<li[^>]*id=["']tags["']""")

    /** The trailing label every grid title carries. */
    private val WALLPAPER_SUFFIX = Regex("""\s+Wallpaper$""", RegexOption.IGNORE_CASE)

    private const val IMAGE_CDN = "https://images.hdqwalls.com/wallpapers/"

    /**
     * The grid of any listing page — popular, latest, a category, search
     * results: the site serves one shared markup shape for all of them.
     * Cells are parsed slice by slice (each cell's chunk runs from its own
     * opening div to the next one), so a malformed cell cannot swallow its
     * neighbors. Items whose thumbnail is not a recognizable `bthumb` CDN
     * URL drop — the original it discloses is the item's whole value.
     */
    fun parseGrid(html: String): List<GridItem> {
        val starts = CELL_START.findAll(html).map { it.range.first }.toList()
        if (starts.isEmpty()) return emptyList()
        return starts
            .indices
            .mapNotNull { i ->
                val from = starts[i]
                val to = if (i + 1 < starts.size) starts[i + 1] else html.length
                parseCell(html.substring(from, to))
            }.toList()
    }

    /** One cell: the first anchor's href is the wallpaper page, the first `bthumb` img is its preview. */
    private fun parseCell(cell: String): GridItem? {
        val href = ANCHOR.find(cell)?.groupValues?.get(1) ?: return null
        val id = pageSlug(href) ?: return null
        val img =
            ANY_IMG.findAll(cell).firstOrNull { tag -> attributes(tag.value)["src"].orEmpty().contains("/bthumb/") }
                ?: return null
        val attrs = attributes(img.value)
        val src = attrs["src"] ?: return null
        if (!src.startsWith("http")) return null
        val thumbUrl = src.substringBefore('#')
        val originalUrl = toOriginalUrl(thumbUrl) ?: return null
        val rawTitle = attrs["title"].orEmpty().ifBlank { attrs["alt"].orEmpty() }
        return GridItem(
            id = id,
            thumbUrl = thumbUrl,
            originalUrl = originalUrl,
            title = cleanTitle(unescapeEntities(rawTitle)),
        )
    }

    /**
     * The next page number, read from the pagination bar's own `Next »`
     * link — the site's authoritative "more exists" signal. The link is
     * absent on the last page (verified live), and past-the-end page
     * requests CLAMP to the last page's content, so blindly incrementing
     * would loop the final batch forever; this is the stopper.
     */
    fun parseNextPage(html: String): Int? {
        val bar = PAGINATION.find(html)?.groupValues?.get(1) ?: return null
        val href = NEXT_LINK.find(bar)?.groupValues?.get(1) ?: return null
        val url = unescapeEntities(href)
        return (PAGE_IN_QUERY.find(url) ?: PAGE_IN_PATH.find(url))?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * The definitive record of a wallpaper page: the blockquote's
     * `Original Resolution` line (URL plus TRUE dimensions — the only place
     * the site publishes them), the author credit, the `Download Original`
     * label's size, and the tag row. The original URL has two fallbacks —
     * the download button's `data-original-url`, then `og:image` — so a
     * blockquote redesign degrades before it breaks.
     */
    fun parseDetail(html: String): DetailRecord? {
        val originalUrl: String
        var width: Int? = null
        var height: Int? = null
        val resolutionLine = ORIGINAL_RESOLUTION.find(html)
        if (resolutionLine != null) {
            originalUrl = resolutionLine.groupValues[1]
            width = resolutionLine.groupValues[2].toIntOrNull()
            height = resolutionLine.groupValues[3].toIntOrNull()
        } else {
            originalUrl =
                DATA_ORIGINAL_URL.find(html)?.groupValues?.get(1)
                    ?: OG_IMAGE.find(html)?.let { meta -> attributes(meta.value)["content"] }
                    ?: return null
        }
        if (!originalUrl.startsWith("http")) return null
        val title =
            ANY_IMG
                .findAll(html)
                .firstOrNull { tag -> attributes(tag.value)["class"].orEmpty().contains("d_img_holder") }
                ?.let { tag ->
                    val attrs = attributes(tag.value)
                    cleanTitle(unescapeEntities(attrs["title"].orEmpty().ifBlank { attrs["alt"].orEmpty() }))
                }?.ifBlank { null }
        val author =
            AUTHOR.find(html)?.groupValues?.get(1)
                ?: AUTHOR_PLAIN
                    .find(html)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
                    ?.ifBlank { null }
        val fileSizeBytes =
            FILE_SIZE.find(html)?.let { match ->
                val size = match.groupValues[1].toDoubleOrNull()
                val unit = match.groupValues[2]
                when {
                    size == null -> null
                    unit == "B" -> size.toLong()
                    unit == "KB" -> (size * 1024).toLong()
                    unit == "MB" -> (size * 1024 * 1024).toLong()
                    else -> (size * 1024 * 1024 * 1024).toLong()
                }
            }
        return DetailRecord(
            originalUrl = originalUrl,
            width = width,
            height = height,
            title = title,
            author = author?.let { unescapeEntities(it) },
            fileSizeBytes = fileSizeBytes,
            tags = parseTags(html),
        )
    }

    /**
     * The tag row: anchors to `/{slug}-wallpapers` tag pages, harvested
     * only inside the detail section (from the marker `li` to its closing
     * `ul`) so navigation or footer links never leak in. Slugs are the
     * site's own tag names.
     */
    private fun parseTags(html: String): List<String> {
        val start = TAGS_SECTION.find(html)?.range?.first ?: return emptyList()
        val end = html.indexOf("</ul>", start).takeIf { it > start } ?: html.length
        val section = html.substring(start, end)
        return TAG_ANCHOR
            .findAll(section)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
    }

    /** `https://hdqwalls.com/{slug}-wallpaper` (or site-relative) to the slug itself; anything else is null. */
    private fun pageSlug(href: String): String? {
        val path =
            when {
                href.startsWith("https://hdqwalls.com/") -> href.removePrefix("https://hdqwalls.com/")
                href.startsWith("/") -> href.removePrefix("/")
                else -> return null
            }
        val slug = path.substringBefore('?').substringBefore('#').trim('/')
        return slug.takeIf { it.isNotEmpty() && !it.contains('/') }
    }

    /**
     * `…/wallpapers/bthumb/{file}.jpg` to `…/wallpapers/{file}.jpg` — the
     * original shares the thumbnail's filename, only the directory differs
     * (verified live: bthumb/ serves a 602x339 JPEG crop, wallpapers/ the
     * multi-megabyte original). Anything not a bthumb CDN path is
     * unrecognized and answered with null.
     */
    fun toOriginalUrl(thumbUrl: String): String? =
        thumbUrl
            .takeIf { it.startsWith(IMAGE_CDN + "bthumb/") }
            ?.replaceFirst("/wallpapers/bthumb/", "/wallpapers/")

    /** The preview URL of an original: the same file back under `bthumb`. */
    fun toThumbUrl(originalUrl: String): String? =
        originalUrl
            .takeIf { it.startsWith(IMAGE_CDN) && !it.contains("/bthumb/") }
            ?.replaceFirst("/wallpapers/", "/wallpapers/bthumb/")

    /** Grid titles all end in `Wallpaper`; the display title is what precedes it. */
    fun cleanTitle(title: String): String = WALLPAPER_SUFFIX.replace(title.trim(), "").trim()

    /**
     * The entities the site's markup actually uses: the five XML names,
     * `&nbsp;`, and arbitrary numeric references (decimal and hex). `&amp;`
     * resolves last so pre-escaped text stays single-escaped.
     */
    fun unescapeEntities(text: String): String {
        if ('&' !in text) return text
        var out = text
        out =
            out.replace(Regex("""&#x([0-9a-fA-F]+);""")) {
                it.groupValues[1]
                    .toInt(16)
                    .toChar()
                    .toString()
            }
        out =
            out.replace(Regex("""&#(\d+);""")) {
                it.groupValues[1]
                    .toInt()
                    .toChar()
                    .toString()
            }
        out =
            out
                .replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
        return out.replace("&amp;", "&")
    }

    /** Splits a tag's attributes (either quote style) into a lookup map. */
    private fun attributes(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { match ->
            match.groupValues[1] to match.groupValues[2].ifEmpty { match.groupValues[3] }
        }
}
