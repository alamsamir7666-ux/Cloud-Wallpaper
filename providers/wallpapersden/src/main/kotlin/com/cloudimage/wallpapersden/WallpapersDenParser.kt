package com.cloudimage.wallpapersden

import java.util.Base64

/**
 * Hand-rolled HTML mining for wallpapersden.com — the scraping half of this
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
 * purpose: every parser keys on stable, semantic anchors (the `itemprop`
 * microdata the site's own listing view emits, the `rel="next"` pagination
 * link, the `download/` image URLs) rather than document order, so cosmetic
 * redesigns degrade parsing to "nothing found" instead of producing garbage.
 * All functions are pure and total: bad input yields empty lists and nulls,
 * never exceptions — callers decide what a miss means.
 */
internal object WallpapersDenParser {
    /** One grid cell: a wallpaper link with its thumbnail. */
    data class GridItem(
        /** The page slug — the site's stable address: `/{slug}-wallpaper/`. */
        val slug: String,
        /** The numeric id the image CDN keys files by, e.g. `93384`. */
        val numericId: String,
        val title: String,
    )

    /** The definitive record of a wallpaper page. */
    data class DetailRecord(
        /** Absolute URL of the original file — the site's own download link. */
        val originalUrl: String,
        /** The wallpaper's numeric id, decoded from the download token. */
        val numericId: String,
        val title: String,
        /** The original file's TRUE dimensions, decoded from the token. */
        val width: Int?,
        val height: Int?,
    )

    /** Attribute splitter: `name="value"` pairs, tolerant of attribute order. */
    private val ATTR = Regex("""([a-zA-Z][a-zA-Z0-9_-]*)\s*=\s*"([^"]*)"""")

    /** An anchor that wraps exactly one `<img>` — the listing card's shape. */
    private val ANCHOR_WRAPPING_IMG =
        Regex("""<a\s+([^>]+)>\s*(<img\b[^>]*>)\s*</a>""", RegexOption.DOT_MATCHES_ALL)

    /** The wallpaper-page href: `/{slug}-wallpaper/`, absolute or relative. */
    private val WALLPAPER_HREF = Regex("""(?:https://wallpapersden\.com)?/([a-z0-9][a-z0-9-]+)-wallpaper/""")

    /** A thumbnail's CDN path: `ws-{slug}_{id}.jpg` — the id the family keys on. */
    private val THUMB_SRC = Regex("""https://images\.wallpapersden\.com/image/ws-[a-z0-9-]+_(\d+)\.jpg""")

    /** The site's own "more exists" signal: the pagination bar's rel="next" link. */
    private val NEXT_LINK = Regex("""<a\s+[^>]*rel="next"[^>]*>""")

    /** A page number inside a pagination href — both path and query styles, escaped or raw. */
    private val PAGE_NUMBER = Regex("""(?:/page/(\d+)/|[?&](?:amp;)?page=(\d+))""")

    /** The original's download URL — meta, og:image and the download button all carry it. */
    private val DOWNLOAD_SRC = Regex("""https://images\.wallpapersden\.com/image/download/[a-z0-9-]+_([A-Za-z0-9]+)\.jpg""")

    /** The detail page's preview image — `wxl-` (landscape) or `hxl-` (portrait). */
    private val PREVIEW_IMG = Regex("""<img\b[^>]*\bclass="[^"]*img_flex[^"]*"[^>]*>""")

    /** The fixed separator inside every download token, observed live on every wallpaper. */
    private const val TOKEN_SEPARATOR = "deexpoo"

    /** The token's per-character shift: every plaintext byte plus this constant. */
    private const val TOKEN_SHIFT = 53

    /**
     * Grids on the latest, popular, random, category, tag and search pages:
     * anchors marked `itemprop="url"` (the site's listing view — the main
     * grid only; the Spotlight and sidebar cards carry no microdata) each
     * wrapping one thumbnail. The slug comes from the anchor's href, the
     * numeric id from the thumbnail's filename: together they disclose every
     * derivable image URL this provider ever constructs.
     */
    fun parseGrid(html: String): List<GridItem> =
        ANCHOR_WRAPPING_IMG
            .findAll(html)
            .mapNotNull { match ->
                val anchorAttrs = attributes(match.groupValues[1])
                if (anchorAttrs["itemprop"] != "url") return@mapNotNull null
                val href = anchorAttrs["href"] ?: return@mapNotNull null
                val slug = WALLPAPER_HREF.find(href)?.groupValues?.get(1) ?: return@mapNotNull null
                val imgAttrs = attributes(match.groupValues[2])
                if (imgAttrs["class"]?.split(' ')?.contains("img-thumbnail") != true) return@mapNotNull null
                val src = imgAttrs["src"] ?: return@mapNotNull null
                val numericId = THUMB_SRC.find(src)?.groupValues?.get(1) ?: return@mapNotNull null
                GridItem(
                    slug = slug,
                    numericId = numericId,
                    title = unescapeEntities(imgAttrs["alt"].orEmpty()),
                )
            }.distinctBy { it.numericId }
            .toList()

    /**
     * The pagination bar's `Next ›` link — the site's own "more exists"
     * signal. Its href carries the next page number in either style the
     * site generates (path `/page/N/` on listings, `page=N` appended on
     * search); the rel attribute is the marker, the number is the answer.
     */
    fun parseNextPage(html: String): Int? {
        val link = NEXT_LINK.find(html) ?: return null
        val numbers = PAGE_NUMBER.findAll(link.value).toList()
        return numbers
            .firstOrNull { it.groupValues[1].isNotBlank() }
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?: numbers
                .firstOrNull { it.groupValues[2].isNotBlank() }
                ?.groupValues
                ?.get(2)
                ?.toIntOrNull()
    }

    /**
     * The definitive record of a `/{slug}-wallpaper/` page. The page's own
     * download link is the original file's exact URL, and its token — the
     * site's deterministic, unsigned encoding of `{id}_{separator}_{W}x{H}`
     * — decodes to the file's TRUE dimensions and the wallpaper's numeric
     * id: the only place the site publishes either. The preview image's alt
     * carries the site's own title.
     */
    fun parseDetail(html: String): DetailRecord? {
        val download = DOWNLOAD_SRC.find(html) ?: return null
        val plain = decodeDownloadToken(download.groupValues[1]) ?: return null
        val match = Regex("""^(\d+)_${TOKEN_SEPARATOR}_(\d+)x(\d+)$""").find(plain) ?: return null
        val title =
            PREVIEW_IMG
                .findAll(html)
                .firstOrNull()
                ?.let { unescapeEntities(attributes(it.value)["alt"].orEmpty()) }
                .orEmpty()
        return DetailRecord(
            originalUrl = download.value,
            numericId = match.groupValues[1],
            title = title,
            width = match.groupValues[2].toIntOrNull(),
            height = match.groupValues[3].toIntOrNull(),
        )
    }

    /**
     * Builds the download token for a wallpaper at an arbitrary resolution —
     * the same URL the site's own resolution pages link. The encoding is the
     * site's public, deterministic scheme (observed byte-identical across
     * every live token): each plaintext character shifted up by 53, the
     * result base64url-encoded without padding. Nothing about it is signed,
     * expiring or per-request — the tokens sit statically in the site's HTML
     * and the site constructs them itself for every resolution it offers.
     */
    fun encodeDownloadToken(
        numericId: String,
        width: Int,
        height: Int,
    ): String {
        val plain = "${numericId}_${TOKEN_SEPARATOR}_${width}x$height"
        val shifted = plain.map { (it.code + TOKEN_SHIFT).toByte() }.toByteArray()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(shifted)
    }

    /** Reverses [encodeDownloadToken]; null when the token is not the site's shape. */
    fun decodeDownloadToken(token: String): String? {
        if (token.isBlank()) return null
        return runCatching {
            val padded = token + "=".repeat((4 - token.length % 4) % 4)
            val bytes = Base64.getUrlDecoder().decode(padded)
            bytes.map { ((it.toInt() and 0xFF) - TOKEN_SHIFT).toChar() }.toCharArray().concatToString()
        }.getOrNull()?.takeIf { it.isNotBlank() }
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

    /** Splits a tag's `name="value"` attributes into a lookup map. */
    private fun attributes(tag: String): Map<String, String> = ATTR.findAll(tag).associate { it.groupValues[1] to it.groupValues[2] }
}
