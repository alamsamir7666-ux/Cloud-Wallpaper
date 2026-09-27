package com.cloudimage.wallpapercave

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hand-rolled HTML mining for wallpapercave.com — the scraping half of this
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
 * purpose: every parser keys on stable, semantic anchors (`/w/{id}` hrefs,
 * the `albumthumbnail` class, `img.wpimg`) rather than document order, so
 * cosmetic redesigns degrade parsing to "nothing found" instead of producing
 * garbage. All functions are pure and total: bad input yields empty lists
 * and nulls, never exceptions — callers decide what a miss means.
 */
internal object WallpaperCaveParser {
    /** One grid cell: a wallpaper link with its thumbnail. */
    data class GridItem(
        val id: String,
        /** Site-relative thumbnail path, e.g. `/uwpr/uwp123.jpeg`. */
        val thumbPath: String,
        val title: String,
        /** Thumbnail dimensions as published — aspect-true, not file-true. */
        val width: Int?,
        val height: Int?,
    )

    /** One wallpaper inside a topic album; [width]/[height] are the file's true size. */
    data class TopicItem(
        val id: String,
        /** Path of the original file, e.g. `/wp/wp123.webp`. */
        val originalPath: String,
        val title: String,
        val width: Int?,
        val height: Int?,
    )

    /** One search hit: a topic album, addressed by its URL slug. */
    data class Album(
        val slug: String,
        val title: String,
        val photoCount: Int,
    )

    /** The `img.wpimg` of a wallpaper page — the definitive record for an id. */
    data class DetailImage(
        val id: String,
        /** Absolute URL of the original file. */
        val src: String,
        val title: String,
        /** Display dimensions — aspect-true, scaled to the page's width. */
        val width: Int?,
        val height: Int?,
    )

    /** Attribute splitter: `name="value"` pairs, tolerant of attribute order. */
    private val ATTR = Regex("""([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*"([^"]*)"""")

    /** Any anchor linking a wallpaper page — ids are `wp123`, `uwp123` or short alphanumerics like `qq5qUZy`. */
    private val GRID_ANCHOR = Regex("""<a\s+href="/w/([A-Za-z0-9]{3,})"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)

    /** An album block on search/home pages — `albumthumbnail` or `falbumthumbnail`, attributes in any order. */
    private val ALBUM_ANCHOR =
        Regex(
            """<a\s+href="/([a-z0-9][a-z0-9-]+)"[^>]*?\sclass="f?albumthumbnail[^"]*"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** An item anchor on topic pages: `/w/{id}` wrapping a `<picture>`. */
    private val TOPIC_ANCHOR =
        Regex(
            """<a\s+href="/w/([A-Za-z0-9]{3,})"[^>]*>\s*<picture[^>]*>(.*?)</picture>""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** Any `<img …>` tag; filtered by class where that matters. */
    private val ANY_IMG = Regex("""<img\b[^>]*>""")

    /** Thumbnail prefixes the site serves from, mapped to their original dirs. */
    private val THUMB_PREFIX =
        Regex("""^/(uwpr|fuwp|fwp|dwp1x|dwp2x|mwp|wpt)(?:-\d+)?/([^/?#]+)$""")

    private val INT = Regex("""^\d+$""")

    /** JSON facade for the site's load-more endpoint; unknown keys ignored. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Grids on `/latest-uploads`, in the load-more payload and on the home
     * page: anchors to `/w/{id}` with an `<img>` thumbnail inside. Width and
     * height, when present, are the thumbnail's — the site scales thumbnails
     * proportionally, so the ratio is the wallpaper's real ratio.
     */
    fun parseGridAnchors(html: String): List<GridItem> =
        GRID_ANCHOR
            .findAll(html)
            .mapNotNull { match ->
                val (id, inner) = match.destructured
                val img = ANY_IMG.find(inner) ?: return@mapNotNull null
                val attrs = attributes(img.value)
                val src = attrs["src"] ?: return@mapNotNull null
                GridItem(
                    id = id,
                    thumbPath = src,
                    title = unescapeEntities(anchorTitle(match.value) ?: ""),
                    width = attrs["width"]?.toIntOrNull(),
                    height = attrs["height"]?.toIntOrNull(),
                )
            }.toList()

    /**
     * The `/morelatest` payload: `[{"next_page": N, "imgs": "<a …>…"}]`. The
     * JSON string is decoded first (unescaping the embedded quotes), then the
     * HTML fragment is mined with the grid parser.
     */
    fun parseMoreLatest(body: String): List<GridItem> {
        val element =
            runCatching { json.parseToJsonElement(body) }.getOrNull()
                ?: return emptyList()
        val imgs =
            runCatching {
                element.jsonArray
                    .firstOrNull()
                    ?.jsonObject
                    ?.get("imgs")
                    ?.jsonPrimitive
                    ?.content
            }.getOrNull()
                ?: return emptyList()
        return parseGridAnchors(imgs)
    }

    /**
     * Album blocks: the site's search answers with topic albums, never with
     * single wallpapers. The slug (an href like `/91-days-wallpapers`) is the
     * address the provider fetches next.
     */
    fun parseAlbums(html: String): List<Album> =
        ALBUM_ANCHOR
            .findAll(html)
            .mapNotNull { match ->
                val (slug, inner) = match.destructured
                if (slug == "search") return@mapNotNull null
                val photos = Regex("""photos="(\d+)"""").find(inner)?.groupValues?.get(1)
                Album(
                    slug = slug,
                    title = unescapeEntities(anchorTitle(match.value) ?: ""),
                    photoCount = photos?.toIntOrNull() ?: 0,
                )
            }.toList()

    /**
     * Wallpaper items on a topic album page. The `<img>` inside the picture
     * loads the ORIGINAL file directly, so `src` is the download URL and the
     * width/height attributes are the file's true dimensions (verified
     * against the served bytes: 736x1131 on the page is 736x1131 in the
     * file) — the only place the site publishes them.
     */
    fun parseTopicWallpapers(html: String): List<TopicItem> =
        TOPIC_ANCHOR
            .findAll(html)
            .mapNotNull { match ->
                val (id, inner) = match.destructured
                val img = ANY_IMG.find(inner) ?: return@mapNotNull null
                val attrs = attributes(img.value)
                val src = attrs["src"] ?: return@mapNotNull null
                TopicItem(
                    id = id,
                    originalPath = src,
                    title = unescapeEntities(attrs["alt"].orEmpty()),
                    width = attrs["width"]?.toIntOrNull(),
                    height = attrs["height"]?.toIntOrNull(),
                )
            }.toList()

    /**
     * The main image of a `/w/{id}` page — the source of truth for an id:
     * `src` is the original file's absolute URL, whatever a grid-derived
     * guess said. Display dimensions are aspect-true but scaled to the
     * page's 700px column; user uploads carry none at all.
     */
    fun parseDetailImage(html: String): DetailImage? {
        val img =
            ANY_IMG.findAll(html).firstOrNull { tag ->
                attributes(tag.value)["class"]?.split(' ')?.contains("wpimg") == true
            } ?: return null
        val attrs = attributes(img.value)
        val src = attrs["src"] ?: return null
        return DetailImage(
            id = attrs["data-url"].orEmpty(),
            src = absolute(src),
            title = unescapeEntities(attrs["alt"].orEmpty().ifBlank { attrs["title"].orEmpty() }),
            width = attrs["width"]?.toIntOrNull(),
            height = attrs["height"]?.toIntOrNull(),
        )
    }

    /**
     * Maps a thumbnail path to the original file's path by directory family:
     * every `uwp*` prefix serves user uploads from `/uwp/`, the rest serve
     * site wallpapers from `/wp/`. The extension travels with the filename —
     * verified live (`/uwpr/uwp5093523.jpeg` -> `/uwp/uwp5093523.jpeg`,
     * `/fwp-255/wp15547868.jpg` -> `/wp/wp15547868.jpg`). Paths already
     * pointing at an original pass through unchanged; anything else is
     * unrecognized and answered with null.
     */
    fun toOriginalPath(thumbPath: String): String? {
        val match = THUMB_PREFIX.find(thumbPath)
        if (match != null) {
            val (prefix, file) = match.destructured
            val dir = if (prefix.contains("uwp")) "uwp" else "wp"
            return "/$dir/$file"
        }
        return when {
            thumbPath.startsWith("/wp/") || thumbPath.startsWith("/uwp/") -> thumbPath
            else -> null
        }
    }

    /** Site-relative paths become absolute; absolute URLs pass through. */
    fun absolute(path: String): String =
        if (path.startsWith("http://") || path.startsWith("https://")) {
            path
        } else {
            "https://wallpapercave.com$path"
        }

    /**
     * The entities the site's titles actually use: the five XML names,
     * `&nbsp;`, and arbitrary numeric references (decimal and hex). `&amp;`
     * resolves last so pre-escaped text (`&amp;lt;`) stays single-escaped.
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

    /** `title="…"` of a tag, entity-unescaped by the caller. */
    private fun anchorTitle(tag: String): String? = attributes(tag)["title"]

    /** Splits a tag's `name="value"` attributes into a lookup map. */
    private fun attributes(tag: String): Map<String, String> = ATTR.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }

    /** Digits-only parse; anything else (or absent) is null. */
    private fun String?.toIntOrNull(): Int? = this?.takeIf { INT.matches(it) }?.toInt()
}
